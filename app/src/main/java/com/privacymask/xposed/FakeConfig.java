package com.privacymask.xposed;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

/**
 * Read-only snapshot of the current fake identity, taken from the remote preferences the
 * Xposed framework shares between PrivacyMask (writer, via XposedService) and every hooked
 * app's process (reader, via XposedInterface#getRemotePreferences — read-only there by
 * design). No ContentProvider, no IPC of our own to maintain: the framework already handles
 * getting this Bundle-free SharedPreferences view into the hooked process.
 *
 * All fields fall back to a sensible built-in default if a key hasn't been written yet, so a
 * freshly installed module behaves consistently even before PrivacyMask has ever been opened.
 */
public class FakeConfig {

    public final String isoCountry;
    public final String mcc, mnc;
    public final String simOperatorName, networkOperatorName;
    public final String timezoneId;
    public final String localeLang, localeCountry;
    public final String phoneNumber;
    public final double latitude, longitude;
    public final int version;

    // One flag per individual hook — see HookCatalog for the full grouped list of keys. All
    // default to true, so a fresh install (or one upgrading from the old six group-level
    // switches) behaves exactly like before this option existed.
    private final Map<String, Boolean> hookEnabled = new HashMap<>();

    private FakeConfig(SharedPreferences sp) {
        isoCountry = sp.getString(ConfigKeys.COUNTRY, "de");
        mcc = sp.getString(ConfigKeys.MCC, "262");
        mnc = sp.getString(ConfigKeys.MNC, "01");
        simOperatorName = sp.getString(ConfigKeys.SIM_OP_NAME, "");
        networkOperatorName = sp.getString(ConfigKeys.NET_OP_NAME, "");
        timezoneId = sp.getString(ConfigKeys.TIMEZONE, "Europe/Berlin");
        localeLang = sp.getString(ConfigKeys.LOCALE_LANG, "de");
        localeCountry = sp.getString(ConfigKeys.LOCALE_COUNTRY, "DE");
        phoneNumber = sp.getString(ConfigKeys.PHONE, "+491234567");
        latitude = sp.getFloat(ConfigKeys.LAT, 52.5f);
        longitude = sp.getFloat(ConfigKeys.LNG, 13.4f);
        version = sp.getInt(ConfigKeys.VERSION, 0);

        for (HookCatalog.Hook hook : HookCatalog.allHooks()) {
            hookEnabled.put(hook.key, sp.getBoolean(hook.key, true));
        }
    }

    /** Whether the individual hook identified by one of the ConfigKeys.HOOK_* keys is on. */
    public boolean isHookEnabled(String key) {
        Boolean v = hookEnabled.get(key);
        return v == null || v;
    }

    public String operatorNumeric() {
        return mcc + mnc;
    }

    /**
     * Reads the current identity directly from the module's remote preferences.
     * Safe to call from inside a hooked process with no Context and no round-trip through a
     * ContentProvider — {@code xposed} is the module instance itself (XposedModule implements
     * XposedInterface, which declares getRemotePreferences()).
     *
     * @throws UnsupportedOperationException if the running framework doesn't advertise
     *                                        PROP_CAP_REMOTE (remote preferences support)
     */
    static FakeConfig from(XposedInterface xposed) {
        return new FakeConfig(xposed.getRemotePreferences(ConfigKeys.GROUP));
    }
}
