package com.privacymask.xposed;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.res.Configuration;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.LocaleList;
import android.os.SystemClock;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.time.ZoneId;
import java.util.Locale;
import java.util.TimeZone;
import io.github.libxposed.api.XposedInterface.HookHandle;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Entry point for the modern libxposed API (targets API_102, requires Vector 2.2+ or any
 * other libxposed-API-102-compatible framework).
 *
 * Strategy: hooks are installed from onPackageReady() — called once the target app's
 * classloader exists, i.e. before its Application.onCreate() ever runs — instead of hooking
 * Application.onCreate() ourselves the way earlier versions of this module did. That
 * indirection existed only so we'd have a Context to read the config through a
 * ContentProvider; remote preferences don't need a Context at all (getRemotePreferences() is
 * inherited straight from XposedInterface), so there's nothing left to wait for.
 *
 * There's also no "enabled" flag to check anymore. As soon as the framework has a process in
 * this module's scope, hooks go in — using whatever identity is currently in the remote
 * preferences, or the built-in defaults in FakeConfig if PrivacyMask has never been opened.
 * That's what makes a fresh install work right after a force-stop + reopen of the target app,
 * with no need to ever launch PrivacyMask itself first.
 */
public class PrivacyMaskModule extends XposedModule {

    private static final String TAG = "PrivacyMask";
    private static final String SELF_PACKAGE = "com.privacymask.xposed";

    private static final String CHROMIUM_TIMEZONE_MONITOR =
            "org.chromium.device.time_zone_monitor.TimeZoneMonitor";
    private static final int CHROMIUM_LOADER_MAX_CLASS_LOADS = 20_000;
    private static final long CHROMIUM_LOADER_MAX_AGE_MS = 30_000L;

    private enum ChromiumAdapterState {
        UNARMED,
        ARMED,
        HOOKED,
        FAILED,
        EXPIRED
    }

    // XposedModule is process-local. These fields therefore track Chromium/WebView adapter
    // state for one target process only.
    private final Object chromiumAdapterLock = new Object();
    private ChromiumAdapterState chromiumAdapterState = ChromiumAdapterState.UNARMED;
    private HookHandle[] chromiumLoaderObserverHandles;
    private HookHandle chromiumWebViewTriggerHandle;
    private HookHandle chromiumTargetHookHandle;
    private long chromiumObserverStartedAtMs;
    private int chromiumObservedClassLoads;

    // System packages that must never be hooked, even if a user mistakenly adds them to
    // scope in the framework Manager — hooking these can destabilize the whole device.
    private static final String[] ALWAYS_EXCLUDED = {
            "system",
            "android",
            "com.android.systemui",
            "com.android.phone",
            "com.android.settings",
            SELF_PACKAGE
    };

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        super.onModuleLoaded(param);
        log(Log.INFO, TAG, "PrivacyMask module attached to process " + param.getProcessName());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        super.onPackageReady(param);
        final String packageName = param.getPackageName();

        for (String excluded : ALWAYS_EXCLUDED) {
            if (excluded.equals(packageName)) {
                log(Log.INFO, TAG,
                        "skipping protected package/process scope " + packageName);
                return;
            }
        }
        // Only hook once per process, on the process's main package.
        if (!param.isFirstPackage()) return;

        try {
            FakeConfig cfg = FakeConfig.from(this);
            applyAllHooks(cfg, param.getClassLoader(), packageName);
            log(Log.INFO, TAG, "applied fake identity [" + cfg.isoCountry + "] to "
                    + packageName + " (config v" + cfg.version + ")");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "error applying hooks in " + packageName, t);
        }
    }

    private void applyAllHooks(
            FakeConfig cfg, ClassLoader appClassLoader, String packageName) {
        // Every hookXxx() method below now checks each individual hook's own switch (see
        // ConfigKeys.HOOK_* / HookCatalog), so all six groups are always visited here; whether
        // any given Android API actually gets intercepted depends entirely on that hook's flag.
        hookTelephony(cfg);
        hookSubscriptionInfo(cfg);
        hookLocation(cfg);
        hookTimeZone(cfg, appClassLoader, packageName);
        hookLocale(cfg);
        hookSystemProperties(cfg);
    }

    // -----------------------------------------------------------------
    // TelephonyManager: SIM country, network country, carrier, phone number
    // -----------------------------------------------------------------
    private void hookTelephony(FakeConfig cfg) {
        // Per-slot overloads (getNetworkCountryIso(int slotIndex) etc., added API 30+) are
        // multi-SIM-aware variants of the exact same getter, so they ride along with their
        // no-arg counterpart's switch instead of getting their own.
        boolean hasSlotOverloads = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;

        if (cfg.isHookEnabled(ConfigKeys.HOOK_TEL_SIM_COUNTRY_ISO)) {
            hookSafe(() -> hook(TelephonyManager.class.getDeclaredMethod("getSimCountryIso"))
                    .intercept(chain -> cfg.isoCountry));
            if (hasSlotOverloads) {
                hookSafe(() -> hook(TelephonyManager.class.getDeclaredMethod("getSimCountryIso", int.class))
                        .intercept(chain -> cfg.isoCountry));
            }
        }
        if (cfg.isHookEnabled(ConfigKeys.HOOK_TEL_NETWORK_COUNTRY_ISO)) {
            hookSafe(() -> hook(TelephonyManager.class.getDeclaredMethod("getNetworkCountryIso"))
                    .intercept(chain -> cfg.isoCountry));
            if (hasSlotOverloads) {
                hookSafe(() -> hook(TelephonyManager.class.getDeclaredMethod("getNetworkCountryIso", int.class))
                        .intercept(chain -> cfg.isoCountry));
            }
        }
        if (cfg.isHookEnabled(ConfigKeys.HOOK_TEL_SIM_OPERATOR)) {
            hookSafe(() -> hook(TelephonyManager.class.getDeclaredMethod("getSimOperator"))
                    .intercept(chain -> cfg.operatorNumeric()));
            if (hasSlotOverloads) {
                hookSafe(() -> hook(TelephonyManager.class.getDeclaredMethod("getSimOperator", int.class))
                        .intercept(chain -> cfg.operatorNumeric()));
            }
        }
        if (cfg.isHookEnabled(ConfigKeys.HOOK_TEL_NETWORK_OPERATOR)) {
            hookSafe(() -> hook(TelephonyManager.class.getDeclaredMethod("getNetworkOperator"))
                    .intercept(chain -> cfg.operatorNumeric()));
        }
        if (cfg.isHookEnabled(ConfigKeys.HOOK_TEL_SIM_OPERATOR_NAME)) {
            hookSafe(() -> hook(TelephonyManager.class.getDeclaredMethod("getSimOperatorName"))
                    .intercept(chain -> cfg.simOperatorName));
            if (hasSlotOverloads) {
                hookSafe(() -> hook(TelephonyManager.class.getDeclaredMethod("getSimOperatorName", int.class))
                        .intercept(chain -> cfg.simOperatorName));
            }
        }
        if (cfg.isHookEnabled(ConfigKeys.HOOK_TEL_NETWORK_OPERATOR_NAME)) {
            hookSafe(() -> hook(TelephonyManager.class.getDeclaredMethod("getNetworkOperatorName"))
                    .intercept(chain -> cfg.networkOperatorName));
        }
        if (cfg.isHookEnabled(ConfigKeys.HOOK_TEL_PHONE_NUMBER)) {
            hookSafe(() -> hook(TelephonyManager.class.getDeclaredMethod("getLine1Number"))
                    .intercept(chain -> cfg.phoneNumber));
        }
    }

    // -----------------------------------------------------------------
    // SubscriptionInfo: what SubscriptionManager#getActiveSubscriptionInfoList() (and helper
    // libraries built on it, e.g. MultiSimHelper) hand back per-SIM. Hooking the instance
    // getters directly means we don't have to intercept/rebuild the list itself, and it
    // covers every caller that walks that list regardless of how they obtained it.
    // -----------------------------------------------------------------
    private void hookSubscriptionInfo(FakeConfig cfg) {
        boolean hasStringVariants = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;

        if (cfg.isHookEnabled(ConfigKeys.HOOK_SUB_COUNTRY_ISO)) {
            hookSafe(() -> hook(SubscriptionInfo.class.getDeclaredMethod("getCountryIso"))
                    .intercept(chain -> cfg.isoCountry));
        }
        if (cfg.isHookEnabled(ConfigKeys.HOOK_SUB_CARRIER_NAME)) {
            hookSafe(() -> hook(SubscriptionInfo.class.getDeclaredMethod("getCarrierName"))
                    .intercept(chain -> cfg.simOperatorName));
        }
        if (cfg.isHookEnabled(ConfigKeys.HOOK_SUB_DISPLAY_NAME)) {
            hookSafe(() -> hook(SubscriptionInfo.class.getDeclaredMethod("getDisplayName"))
                    .intercept(chain -> cfg.simOperatorName));
        }
        if (cfg.isHookEnabled(ConfigKeys.HOOK_SUB_NUMBER)) {
            hookSafe(() -> hook(SubscriptionInfo.class.getDeclaredMethod("getNumber"))
                    .intercept(chain -> cfg.phoneNumber));
        }
        if (cfg.isHookEnabled(ConfigKeys.HOOK_SUB_MCC)) {
            hookSafe(() -> hook(SubscriptionInfo.class.getDeclaredMethod("getMcc"))
                    .intercept(chain -> Integer.parseInt(cfg.mcc)));
            // String variant of MCC, added API 29 (Q) — same switch as the int getter above.
            if (hasStringVariants) {
                hookSafe(() -> hook(SubscriptionInfo.class.getDeclaredMethod("getMccString"))
                        .intercept(chain -> cfg.mcc));
            }
        }
        if (cfg.isHookEnabled(ConfigKeys.HOOK_SUB_MNC)) {
            hookSafe(() -> hook(SubscriptionInfo.class.getDeclaredMethod("getMnc"))
                    .intercept(chain -> Integer.parseInt(cfg.mnc)));
            // String variant of MNC, added API 29 (Q) — same switch as the int getter above.
            if (hasStringVariants) {
                hookSafe(() -> hook(SubscriptionInfo.class.getDeclaredMethod("getMncString"))
                        .intercept(chain -> cfg.mnc));
            }
        }
    }

    // -----------------------------------------------------------------
    // Location: hook the Location class itself (not just LocationManager) so results coming
    // back from FusedLocationProviderClient / Google Play services are covered too, since
    // the app still reads them through the same Location getters in its own process.
    // -----------------------------------------------------------------
    private void hookLocation(FakeConfig cfg) {
        if (cfg.isHookEnabled(ConfigKeys.HOOK_LOC_LATITUDE)) {
            hookSafe(() -> hook(Location.class.getDeclaredMethod("getLatitude"))
                    .intercept(chain -> cfg.latitude));
        }
        if (cfg.isHookEnabled(ConfigKeys.HOOK_LOC_LONGITUDE)) {
            hookSafe(() -> hook(Location.class.getDeclaredMethod("getLongitude"))
                    .intercept(chain -> cfg.longitude));
        }
        if (cfg.isHookEnabled(ConfigKeys.HOOK_LOC_ACCURACY)) {
            hookSafe(() -> hook(Location.class.getDeclaredMethod("getAccuracy"))
                    .intercept(chain -> 15.0f));
        }
        if (cfg.isHookEnabled(ConfigKeys.HOOK_LOC_LAST_KNOWN)) {
            hookSafe(() -> hook(LocationManager.class.getDeclaredMethod("getLastKnownLocation", String.class))
                    .intercept(chain -> {
                        String provider = (String) chain.getArg(0);
                        Location fake = new Location(provider);
                        fake.setLatitude(cfg.latitude);
                        fake.setLongitude(cfg.longitude);
                        fake.setAccuracy(15.0f);
                        fake.setTime(System.currentTimeMillis());
                        fake.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                        return fake;
                    }));
        }
    }

    // -----------------------------------------------------------------
    // Timezone
    // -----------------------------------------------------------------
    private void hookTimeZone(
            FakeConfig cfg, ClassLoader appClassLoader, String packageName) {
        if (cfg.isHookEnabled(ConfigKeys.HOOK_TZ_DEFAULT)) {
            // Android returns independent mutable TimeZone objects from getDefault(). Returning
            // one shared fake instance lets a caller mutate PrivacyMask's process-wide result.
            // Keep an internal template, but clone it for every intercepted call.
            final TimeZone fakeTimeZoneTemplate = TimeZone.getTimeZone(cfg.timezoneId);
            hookSafe(() -> hook(TimeZone.class.getDeclaredMethod("getDefault"))
                    .intercept(chain -> (TimeZone) fakeTimeZoneTemplate.clone()));

            // ICU has the same mutable-object concern. cloneAsThawed() preserves the configured
            // zone while ensuring callers cannot mutate the template used by later callers.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                final android.icu.util.TimeZone fakeIcuTimeZoneTemplate =
                        android.icu.util.TimeZone.getTimeZone(cfg.timezoneId);
                hookSafe(() -> hook(android.icu.util.TimeZone.class.getDeclaredMethod("getDefault"))
                        .intercept(chain -> fakeIcuTimeZoneTemplate.cloneAsThawed()));
            }

            if (appClassLoader != null) {
                installLegacyChromiumTimezoneUtilsHook(appClassLoader, cfg);

                // Never place ClassLoader.loadClass() observers in every scoped process. First
                // try the target immediately. If it is not visible, only arm the bounded loader
                // observer for a process that already looks Chromium-based. Generic Apps get a
                // cold WebViewFactory trigger instead; the hot ClassLoader observer is armed
                // only if/when that App actually initializes Android WebView.
                if (!tryInstallChromiumTimeZoneMonitorHook(appClassLoader, cfg)) {
                    if (isLikelyChromiumProcess(packageName, appClassLoader)) {
                        armChromiumDeferredLoaderObserver(
                                cfg, "Chromium candidate " + packageName);
                    } else {
                        installWebViewChromiumTrigger(cfg);
                    }
                }
            }
        }

        // java.time never asks TimeZone for anything — ZoneId.systemDefault() reads the
        // default zone independently, and everything downstream (ZonedDateTime.now(),
        // DateTimeFormatter "zzz"/"zzzz" patterns, etc.) derives from whatever it returns.
        if (cfg.isHookEnabled(ConfigKeys.HOOK_TZ_ZONEID) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final ZoneId fakeZoneId = ZoneId.of(cfg.timezoneId);
            hookSafe(() -> hook(ZoneId.class.getDeclaredMethod("systemDefault"))
                    .intercept(chain -> fakeZoneId));
        }
    }


    private void installLegacyChromiumTimezoneUtilsHook(
            ClassLoader appClassLoader, FakeConfig cfg) {
        try {
            Class<?> timezoneUtils =
                    Class.forName("org.chromium.base.TimezoneUtils", false, appClassLoader);
            java.lang.reflect.Method chromiumGetDefaultTimeZoneId =
                    timezoneUtils.getDeclaredMethod("getDefaultTimeZoneId");
            HookHandle handle = hook(chromiumGetDefaultTimeZoneId)
                    .intercept(chain -> cfg.timezoneId);
            if (handle != null) {
                log(Log.INFO, TAG, "Chromium TimezoneUtils hook installed");
            }
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            // Expected on current Chromium builds.
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Chromium TimezoneUtils hook unavailable: " + t);
        }
    }

    /**
     * Installs the Chromium TimeZoneMonitor hook if the class is already visible. Success is
     * reported only when a real HookHandle has been returned.
     */
    private boolean tryInstallChromiumTimeZoneMonitorHook(ClassLoader loader, FakeConfig cfg) {
        if (loader == null) return false;
        try {
            Class<?> monitorClass =
                    Class.forName(CHROMIUM_TIMEZONE_MONITOR, false, loader);
            HookHandle handle = installChromiumTimeZoneMonitorHook(monitorClass, cfg);
            if (handle == null) return false;

            synchronized (chromiumAdapterLock) {
                chromiumTargetHookHandle = handle;
                setChromiumAdapterStateLocked(
                        ChromiumAdapterState.HOOKED,
                        "TimeZoneMonitor already visible");
                cleanupChromiumLoaderObserverLocked();
                cleanupWebViewTriggerLocked();
            }
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Chromium TimeZoneMonitor immediate hook unavailable: " + t);
            return false;
        }
    }

    private boolean isLikelyChromiumProcess(String packageName, ClassLoader loader) {
        String normalized = packageName == null ? "" : packageName.toLowerCase(Locale.US);
        if (normalized.contains("chrome")
                || normalized.contains("chromium")
                || normalized.contains("webview")) {
            return true;
        }

        // Chromium forks typically keep at least one of these public-ish base classes even
        // when their package name does not contain "chrome".
        String[] markerClasses = {
                "org.chromium.base.BuildInfo",
                "org.chromium.base.ContextUtils",
                "org.chromium.base.library_loader.LibraryLoader"
        };
        for (String marker : markerClasses) {
            try {
                Class.forName(marker, false, loader);
                return true;
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable t) {
                log(Log.DEBUG, TAG,
                        "Chromium marker probe failed for " + marker + ": " + t);
            }
        }
        return false;
    }

    /**
     * Generic Apps should not carry a permanent ClassLoader hook just because time-zone masking
     * is enabled. Instead, keep one cold trigger on WebViewFactory.getProvider(); if the App
     * actually initializes Android WebView, arm the bounded Chromium loader observer *before*
     * WebView provider loading proceeds.
     */
    private void installWebViewChromiumTrigger(FakeConfig cfg) {
        synchronized (chromiumAdapterLock) {
            if (chromiumAdapterState != ChromiumAdapterState.UNARMED
                    || chromiumWebViewTriggerHandle != null) {
                return;
            }
        }

        try {
            Class<?> webViewFactory = Class.forName("android.webkit.WebViewFactory");
            java.lang.reflect.Method getProvider =
                    webViewFactory.getDeclaredMethod("getProvider");

            HookHandle handle = hook(getProvider).intercept(chain -> {
                boolean shouldArm = false;
                synchronized (chromiumAdapterLock) {
                    shouldArm = chromiumAdapterState == ChromiumAdapterState.UNARMED;
                }
                if (shouldArm) {
                    armChromiumDeferredLoaderObserver(
                            cfg, "Android WebView provider initialization");
                }

                Object result = chain.proceed();

                synchronized (chromiumAdapterLock) {
                    cleanupWebViewTriggerLocked();
                }
                return result;
            });

            synchronized (chromiumAdapterLock) {
                if (handle != null && chromiumAdapterState == ChromiumAdapterState.UNARMED) {
                    chromiumWebViewTriggerHandle = handle;
                    log(Log.INFO, TAG,
                            "Chromium adapter UNARMED; WebView trigger installed");
                } else if (handle != null) {
                    try {
                        handle.unhook();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "WebView Chromium trigger unavailable: " + t);
        }
    }

    /**
     * Installs a bounded ClassLoader observer. It exists only in confirmed Chromium/WebView
     * candidates and removes itself on success, failure, age limit, or class-load-count limit.
     */
    private void armChromiumDeferredLoaderObserver(FakeConfig cfg, String reason) {
        synchronized (chromiumAdapterLock) {
            if (chromiumAdapterState == ChromiumAdapterState.HOOKED
                    || chromiumAdapterState == ChromiumAdapterState.ARMED) {
                return;
            }
            if (chromiumAdapterState == ChromiumAdapterState.FAILED
                    || chromiumAdapterState == ChromiumAdapterState.EXPIRED) {
                log(Log.INFO, TAG,
                        "Chromium adapter not re-armed after terminal state "
                                + chromiumAdapterState);
                return;
            }
            chromiumObserverStartedAtMs = SystemClock.elapsedRealtime();
            chromiumObservedClassLoads = 0;
            setChromiumAdapterStateLocked(ChromiumAdapterState.ARMED, reason);
        }

        final HookHandle[] handles = new HookHandle[2];
        try {
            java.lang.reflect.Method loadClassOne =
                    ClassLoader.class.getDeclaredMethod("loadClass", String.class);
            java.lang.reflect.Method loadClassTwo =
                    ClassLoader.class.getDeclaredMethod(
                            "loadClass", String.class, boolean.class);

            handles[0] = hook(loadClassOne).intercept(chain -> {
                Object result = chain.proceed();
                onChromiumClassObserved(result, cfg, handles, "loadClass(String)");
                return result;
            });

            handles[1] = hook(loadClassTwo).intercept(chain -> {
                Object result = chain.proceed();
                onChromiumClassObserved(
                        result, cfg, handles, "loadClass(String,boolean)");
                return result;
            });

            synchronized (chromiumAdapterLock) {
                if (chromiumAdapterState == ChromiumAdapterState.ARMED) {
                    chromiumLoaderObserverHandles = handles;
                    log(Log.INFO, TAG,
                            "Chromium TimeZoneMonitor loader observer ARMED"
                                    + " (maxLoads=" + CHROMIUM_LOADER_MAX_CLASS_LOADS
                                    + ", maxAgeMs=" + CHROMIUM_LOADER_MAX_AGE_MS + ")");
                } else {
                    unhookQuietly(handles);
                }
            }
        } catch (Throwable t) {
            unhookQuietly(handles);
            synchronized (chromiumAdapterLock) {
                setChromiumAdapterStateLocked(
                        ChromiumAdapterState.FAILED,
                        "loader observer installation failed: " + t);
                cleanupChromiumLoaderObserverLocked();
            }
        }
    }

    private void onChromiumClassObserved(
            Object result,
            FakeConfig cfg,
            HookHandle[] localHandles,
            String source) {
        if (!(result instanceof Class<?>)) return;

        Class<?> loadedClass = (Class<?>) result;
        synchronized (chromiumAdapterLock) {
            if (chromiumAdapterState != ChromiumAdapterState.ARMED) return;

            chromiumObservedClassLoads++;
            long ageMs = SystemClock.elapsedRealtime() - chromiumObserverStartedAtMs;

            if (CHROMIUM_TIMEZONE_MONITOR.equals(loadedClass.getName())) {
                HookHandle target = installChromiumTimeZoneMonitorHook(loadedClass, cfg);
                if (target != null) {
                    chromiumTargetHookHandle = target;
                    setChromiumAdapterStateLocked(
                            ChromiumAdapterState.HOOKED,
                            "target loaded via " + source);
                    cleanupChromiumLoaderObserverLocked();
                    cleanupWebViewTriggerLocked();
                } else {
                    setChromiumAdapterStateLocked(
                            ChromiumAdapterState.FAILED,
                            "target class found but hook installation failed");
                    cleanupChromiumLoaderObserverLocked();
                    cleanupWebViewTriggerLocked();
                }
                return;
            }

            if (chromiumObservedClassLoads >= CHROMIUM_LOADER_MAX_CLASS_LOADS
                    || ageMs >= CHROMIUM_LOADER_MAX_AGE_MS) {
                setChromiumAdapterStateLocked(
                        ChromiumAdapterState.EXPIRED,
                        "observer bounds reached after "
                                + chromiumObservedClassLoads + " class loads / "
                                + ageMs + " ms");
                cleanupChromiumLoaderObserverLocked();
                cleanupWebViewTriggerLocked();
            }
        }
    }

    private void setChromiumAdapterStateLocked(
            ChromiumAdapterState next, String reason) {
        ChromiumAdapterState previous = chromiumAdapterState;
        chromiumAdapterState = next;
        if (previous != next) {
            log(Log.INFO, TAG,
                    "Chromium adapter " + previous + " -> " + next
                            + " (" + reason + ")");
        }
    }

    private void cleanupChromiumLoaderObserverLocked() {
        HookHandle[] handles = chromiumLoaderObserverHandles;
        chromiumLoaderObserverHandles = null;
        if (handles != null) {
            unhookQuietly(handles);
        }
    }

    private void cleanupWebViewTriggerLocked() {
        HookHandle handle = chromiumWebViewTriggerHandle;
        chromiumWebViewTriggerHandle = null;
        if (handle != null) {
            try {
                handle.unhook();
            } catch (Throwable ignored) {
            }
        }
    }

    private HookHandle installChromiumTimeZoneMonitorHook(
            Class<?> monitorClass, FakeConfig cfg) {
        try {
            java.lang.reflect.Method getInstance =
                    monitorClass.getDeclaredMethod("getInstance", long.class);

            HookHandle handle = hook(getInstance).intercept(chain -> {
            final long nativePtr = ((Number) chain.getArg(0)).longValue();
            Object instance = chain.proceed();

            // Release Chrome is optimized/obfuscated, so private field names are not stable.
            // Log the actual field layout we see and resolve the receiver by TYPE instead of
            // relying on Chromium source names such as "mBroadcastReceiver".
            logChromiumTimeZoneMonitorFieldLayout(monitorClass);

            boolean injected = tryInjectChromiumTimezoneViaReceiver(
                    monitorClass, instance, cfg.timezoneId);

            // If R8 has inlined/reshaped the BroadcastReceiver field, bypass that Java detail
            // entirely. The getInstance(long) argument is the native TimeZoneMonitorAndroid*
            // pointer, and Chromium's generated JniZero bridge accepts exactly that pointer
            // plus the IANA zone ID.
            if (!injected) {
                injected = tryInjectChromiumTimezoneViaJni(
                        monitorClass, nativePtr, cfg.timezoneId);
            }

            if (injected) {
                log(Log.INFO, TAG,
                        "Chromium native timezone update sent: " + cfg.timezoneId);
            } else {
                log(Log.WARN, TAG,
                        "Chromium timezone injection exhausted all Java/JNI paths");
            }
                return instance;
            });

            if (handle != null) {
                log(Log.INFO, TAG,
                        "Chromium TimeZoneMonitor target hook installed");
            } else {
                log(Log.WARN, TAG,
                        "Chromium TimeZoneMonitor hook returned no HookHandle");
            }
            return handle;
        } catch (Throwable t) {
            log(Log.WARN, TAG,
                    "Chromium TimeZoneMonitor target hook installation failed: " + t);
            return null;
        }
    }

    private void logChromiumTimeZoneMonitorFieldLayout(Class<?> monitorClass) {
        try {
            Class<?> current = monitorClass;
            while (current != null && current != Object.class) {
                java.lang.reflect.Field[] fields = current.getDeclaredFields();
                if (fields.length == 0) {
                    log(Log.INFO, TAG,
                            "Chromium TimeZoneMonitor fields " + current.getName() + ": <none>");
                } else {
                    for (java.lang.reflect.Field field : fields) {
                        log(Log.INFO, TAG,
                                "Chromium TimeZoneMonitor field "
                                        + current.getName() + "."
                                        + field.getName() + " : "
                                        + field.getType().getName());
                    }
                }
                current = current.getSuperclass();
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG,
                    "Chromium TimeZoneMonitor field-layout logging failed: " + t);
        }
    }

    private boolean tryInjectChromiumTimezoneViaReceiver(
            Class<?> monitorClass, Object instance, String timezoneId) {
        try {
            Class<?> current = monitorClass;
            while (current != null && current != Object.class) {
                for (java.lang.reflect.Field field : current.getDeclaredFields()) {
                    if (!BroadcastReceiver.class.isAssignableFrom(field.getType())) {
                        continue;
                    }

                    field.setAccessible(true);
                    Object receiverObject = field.get(instance);
                    if (!(receiverObject instanceof BroadcastReceiver)) {
                        continue;
                    }

                    Intent fakeChange = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
                    fakeChange.putExtra(Intent.EXTRA_TIMEZONE, timezoneId);
                    ((BroadcastReceiver) receiverObject).onReceive(null, fakeChange);

                    log(Log.INFO, TAG,
                            "Chromium TimeZoneMonitor receiver found by type: "
                                    + field.getType().getName());
                    log(Log.INFO, TAG,
                            "Chromium TimeZoneMonitor synthetic timezone sent: "
                                    + timezoneId);
                    return true;
                }
                current = current.getSuperclass();
            }

            log(Log.INFO, TAG,
                    "Chromium TimeZoneMonitor has no BroadcastReceiver-typed field; "
                            + "trying JNI fallback");
            return false;
        } catch (Throwable t) {
            log(Log.WARN, TAG,
                    "Chromium TimeZoneMonitor receiver injection failed: " + t
                            + "; trying JNI fallback");
            return false;
        }
    }

    private boolean tryInjectChromiumTimezoneViaJni(
            Class<?> monitorClass, long nativePtr, String timezoneId) {
        ClassLoader loader = monitorClass.getClassLoader();
        String jniClassName = monitorClass.getName() + "Jni";

        try {
            Class<?> jniClass = Class.forName(jniClassName, false, loader);
            java.lang.reflect.Method getMethod = null;
            for (java.lang.reflect.Method method : jniClass.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(method.getModifiers())
                        && method.getParameterCount() == 0
                        && "get".equals(method.getName())) {
                    getMethod = method;
                    break;
                }
            }
            if (getMethod == null) {
                log(Log.WARN, TAG,
                        "Chromium JNI fallback: no static get() on " + jniClassName);
                return false;
            }

            getMethod.setAccessible(true);
            Object natives = getMethod.invoke(null);
            if (natives == null) {
                log(Log.WARN, TAG,
                        "Chromium JNI fallback: " + jniClassName + ".get() returned null");
                return false;
            }

            java.lang.reflect.Method timezoneMethod =
                    findChromiumTimezoneChangedMethod(natives.getClass());
            if (timezoneMethod == null) {
                // Some generated implementations expose the method only through the nested
                // Natives interface. Search that interface by signature rather than by its
                // source-level name so this remains resilient to obfuscation.
                for (Class<?> nested : monitorClass.getDeclaredClasses()) {
                    java.lang.reflect.Method candidate =
                            findChromiumTimezoneChangedMethod(nested);
                    if (candidate != null) {
                        timezoneMethod = candidate;
                        break;
                    }
                }
            }

            if (timezoneMethod == null) {
                log(Log.WARN, TAG,
                        "Chromium JNI fallback: no (long,String)->void timezone method found");
                return false;
            }

            timezoneMethod.setAccessible(true);
            timezoneMethod.invoke(natives, nativePtr, timezoneId);
            log(Log.INFO, TAG,
                    "Chromium TimeZoneMonitor JNI fallback invoked via "
                            + jniClassName + " using nativePtr=" + nativePtr);
            return true;
        } catch (ClassNotFoundException e) {
            log(Log.WARN, TAG,
                    "Chromium JNI fallback class not found: " + jniClassName);
            return false;
        } catch (Throwable t) {
            log(Log.WARN, TAG,
                    "Chromium TimeZoneMonitor JNI fallback failed: " + t);
            return false;
        }
    }

    private java.lang.reflect.Method findChromiumTimezoneChangedMethod(Class<?> type) {
        try {
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (method.getReturnType() == void.class
                        && params.length == 2
                        && params[0] == long.class
                        && params[1] == String.class) {
                    return method;
                }
            }

            for (java.lang.reflect.Method method : type.getMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (method.getReturnType() == void.class
                        && params.length == 2
                        && params[0] == long.class
                        && params[1] == String.class) {
                    return method;
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG,
                    "Chromium JNI method-signature scan failed for "
                            + type.getName() + ": " + t);
        }
        return null;
    }

    private void unhookQuietly(HookHandle[] handles) {
        if (handles == null) return;
        for (HookHandle handle : handles) {
            if (handle == null) continue;
            try {
                handle.unhook();
            } catch (Throwable ignored) {
            }
        }
    }

    // -----------------------------------------------------------------
    // Locale (language/country apps read to infer where the user lives)
    // -----------------------------------------------------------------
    private void hookLocale(FakeConfig cfg) {
        final Locale fakeLocale = new Locale(cfg.localeLang, cfg.localeCountry);

        if (cfg.isHookEnabled(ConfigKeys.HOOK_LOCALE_DEFAULT)) {
            hookSafe(() -> hook(Locale.class.getDeclaredMethod("getDefault"))
                    .intercept(chain -> fakeLocale));
            hookSafe(() -> hook(Locale.class.getDeclaredMethod("getDefault", Locale.Category.class))
                    .intercept(chain -> fakeLocale));

            // Chromium and other modern Android apps may bypass Locale.getDefault() and read
            // the process-wide LocaleList directly. Keep that list consistent with the same
            // fake locale so navigator.languages and similar bridges do not expose secondary
            // real device languages.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                final LocaleList fakeLocaleList = new LocaleList(fakeLocale);
                hookSafe(() -> hook(LocaleList.class.getDeclaredMethod("getDefault"))
                        .intercept(chain -> fakeLocaleList));
                hookSafe(() -> hook(LocaleList.class.getDeclaredMethod("getAdjustedDefault"))
                        .intercept(chain -> fakeLocaleList));
            }
        }

        // getResources().getConfiguration().getLocales().get(0) never calls Locale.getDefault()
        // at all — Configuration carries its own LocaleList, filled in by the system when the
        // Configuration object is built, and every read after that (including .get(0)) comes
        // straight from that field. So without this hook, the switch above is invisible to
        // that whole call path and it leaks the device's real locale/country untouched — which
        // is exactly the "US instead of CH" you're seeing. It's a separate switch precisely
        // because it's a separate call path.
        if (cfg.isHookEnabled(ConfigKeys.HOOK_LOCALE_CONFIGURATION) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            final LocaleList fakeLocaleList = new LocaleList(fakeLocale);
            hookSafe(() -> hook(Configuration.class.getDeclaredMethod("getLocales"))
                    .intercept(chain -> fakeLocaleList));
        }
    }

    // -----------------------------------------------------------------
    // SystemProperties: the layer TimeZone/TelephonyManager/Locale sit on top of. A caller
    // can skip all the Java-level getters above entirely and read
    // persist.sys.timezone / gsm.operator.iso-country / etc. straight from here — either via
    // reflection on android.os.SystemProperties or by shelling out to `getprop`. The shell
    // route is outside what a method hook can touch (see the probe app note); this covers the
    // in-process reflection route, which is the one apps actually use in practice.
    //
    // Unlike every other hook above, this can't unconditionally replace the return value —
    // get(String) is a single choke point for *every* system property any code in the process
    // reads, not just the location-relevant ones. So the intercept below only substitutes a
    // fake value for the handful of keys we care about, and calls chain.proceed() — continue
    // down the hook chain with the same args, landing on the real implementation since we're
    // the only hook here — for everything else, exactly like the "call the following chains
    // with the same args" example in libxposed's own sample module.
    //
    // SystemProperties is a @hide platform class. Class.forName / getDeclaredMethod on it can
    // be blocked by hidden-API enforcement depending on the *hooked app's* targetSdk — that's
    // why this whole method is wrapped in a single try/catch: if the reflection setup itself
    // fails, we skip the hook instead of taking down onPackageReady for everything else.
    // -----------------------------------------------------------------
    private void hookSystemProperties(FakeConfig cfg) {
        // The two methods hooked below (get/get-with-default) are a single choke point shared
        // by every property key, so there's no per-key way to only "half install" this hook —
        // instead, whether a given key actually gets faked is decided inside
        // fakeSystemProperty() below, one switch per key group. Skip installing entirely only
        // if every one of those switches is off.
        boolean anyEnabled = cfg.isHookEnabled(ConfigKeys.HOOK_PROP_TIMEZONE)
                || cfg.isHookEnabled(ConfigKeys.HOOK_PROP_LOCALE)
                || cfg.isHookEnabled(ConfigKeys.HOOK_PROP_COUNTRY_ISO)
                || cfg.isHookEnabled(ConfigKeys.HOOK_PROP_OPERATOR_NUMERIC)
                || cfg.isHookEnabled(ConfigKeys.HOOK_PROP_OPERATOR_ALPHA);
        if (!anyEnabled) return;

        try {
            Class<?> spClass = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method getMethod = spClass.getDeclaredMethod("get", String.class);
            java.lang.reflect.Method getWithDefaultMethod =
                    spClass.getDeclaredMethod("get", String.class, String.class);

            hookSafe(() -> hook(getMethod).intercept(chain -> {
                String fake = fakeSystemProperty(cfg, (String) chain.getArg(0));
                return fake != null ? fake : chain.proceed();
            }));
            hookSafe(() -> hook(getWithDefaultMethod).intercept(chain -> {
                String fake = fakeSystemProperty(cfg, (String) chain.getArg(0));
                return fake != null ? fake : chain.proceed();
            }));
        } catch (Throwable t) {
            log(Log.WARN, TAG, "SystemProperties hook unavailable: " + t);
        }
    }

    // Central map from property key to fake value. Returning null means "not one of ours (or
    // its switch is off) — pass through to the real value" via chain.proceed() at the call
    // site above.
    private String fakeSystemProperty(FakeConfig cfg, String key) {
        if (key == null) return null;
        switch (key) {
            case "persist.sys.timezone":
                return cfg.isHookEnabled(ConfigKeys.HOOK_PROP_TIMEZONE) ? cfg.timezoneId : null;
            case "persist.sys.locale":
                return cfg.isHookEnabled(ConfigKeys.HOOK_PROP_LOCALE)
                        ? cfg.localeLang + "-" + cfg.localeCountry : null;
            case "gsm.operator.iso-country":
            case "gsm.sim.operator.iso-country":
                return cfg.isHookEnabled(ConfigKeys.HOOK_PROP_COUNTRY_ISO) ? cfg.isoCountry : null;
            case "gsm.operator.numeric":
            case "gsm.sim.operator.numeric":
                return cfg.isHookEnabled(ConfigKeys.HOOK_PROP_OPERATOR_NUMERIC) ? cfg.operatorNumeric() : null;
            case "gsm.operator.alpha":
                return cfg.isHookEnabled(ConfigKeys.HOOK_PROP_OPERATOR_ALPHA) ? cfg.networkOperatorName : null;
            case "gsm.sim.operator.alpha":
                return cfg.isHookEnabled(ConfigKeys.HOOK_PROP_OPERATOR_ALPHA) ? cfg.simOperatorName : null;
            default:
                return null;
        }
    }

    // -----------------------------------------------------------------
    // Small helper so one missing method (e.g. removed on some OEM ROM) doesn't abort
    // every other hook in the batch.
    // -----------------------------------------------------------------
    private interface ThrowingAction {
        void run() throws Throwable;
    }

    private void hookSafe(ThrowingAction action) {
        try {
            action.run();
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook skipped: " + t);
        }
    }
}
