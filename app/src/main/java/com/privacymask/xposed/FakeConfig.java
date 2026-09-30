package com.privacymask.xposed;

import android.content.SharedPreferences;

import org.json.JSONException;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

/**
 * Read-only process-local view of the authoritative PrivacyMask configuration.
 *
 * Hooked processes cannot write remote preferences, so this class understands all three startup
 * states:
 *  1) schema-v2 snapshot exists -> use it;
 *  2) upgrade happened but UI has not migrated yet -> read legacy 1.2.x keys consistently;
 *  3) completely fresh install -> use the same deterministic built-in default ConfigStore uses.
 *
 * Once the UI/service migrates or initializes schema v2, target processes read only that single
 * serialized snapshot, eliminating mixed-version field reads.
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

    private final Map<String, Boolean> hookEnabled = new HashMap<>();

    private FakeConfig(ConfigSnapshot snapshot) {
        isoCountry = snapshot.isoCountry;
        mcc = snapshot.mcc;
        mnc = snapshot.mnc;
        simOperatorName = snapshot.simOperatorName;
        networkOperatorName = snapshot.networkOperatorName;
        timezoneId = snapshot.timezoneId;
        localeLang = snapshot.localeLanguage();
        localeCountry = snapshot.localeCountry();
        phoneNumber = snapshot.phoneNumber;
        latitude = snapshot.latitude;
        longitude = snapshot.longitude;
        version = snapshot.configVersion;

        for (HookCatalog.Hook hook : HookCatalog.allHooks()) {
            hookEnabled.put(hook.key, snapshot.isHookEnabled(hook.key));
        }
    }

    private FakeConfig(SharedPreferences legacy) {
        isoCountry = normalizeCountry(
                legacy.getString(ConfigKeys.COUNTRY, "de"));
        mcc = legacy.getString(ConfigKeys.MCC, "262");
        mnc = legacy.getString(ConfigKeys.MNC, "01");
        simOperatorName = legacy.getString(ConfigKeys.SIM_OP_NAME, "T-Mobile DE");
        networkOperatorName =
                legacy.getString(ConfigKeys.NET_OP_NAME, "T-Mobile DE");
        timezoneId = legacy.getString(ConfigKeys.TIMEZONE, "Europe/Berlin");
        localeLang = legacy.getString(ConfigKeys.LOCALE_LANG, "de");
        localeCountry = legacy.getString(ConfigKeys.LOCALE_COUNTRY, "DE");
        phoneNumber = legacy.getString(ConfigKeys.PHONE, "+491234567");
        latitude = legacy.getFloat(ConfigKeys.LAT, 52.5f);
        longitude = legacy.getFloat(ConfigKeys.LNG, 13.4f);
        version = Math.max(1, legacy.getInt(ConfigKeys.VERSION, 0));

        for (HookCatalog.Hook hook : HookCatalog.allHooks()) {
            hookEnabled.put(hook.key, legacy.getBoolean(hook.key, true));
        }
    }

    private FakeConfig() {
        this(ConfigStore.defaultSnapshot());
    }

    public boolean isHookEnabled(String key) {
        Boolean v = hookEnabled.get(key);
        return v == null || v;
    }

    public String operatorNumeric() {
        return mcc + mnc;
    }

    static FakeConfig from(XposedInterface xposed) {
        SharedPreferences sp =
                xposed.getRemotePreferences(ConfigKeys.GROUP);

        String json = sp.getString(ConfigKeys.SNAPSHOT_JSON, null);
        if (json != null && !json.trim().isEmpty()) {
            try {
                ConfigSnapshot snapshot = ConfigSnapshot.fromJson(json);
                ConfigSnapshot.ValidationResult validation = snapshot.validate();
                if (validation.valid) {
                    return new FakeConfig(snapshot);
                }
            } catch (JSONException | RuntimeException ignored) {
                // Fall through to the deterministic safe fake identity below. Once schema-v2
                // exists, legacy keys are no longer authoritative and must never resurrect a
                // stale identity after snapshot corruption.
            }
            return new FakeConfig();
        }

        // Upgrade compatibility only while schema-v2 is genuinely absent and before the UI
        // performs the one-time migration.
        if (sp.contains(ConfigKeys.COUNTRY)) {
            return new FakeConfig(sp);
        }

        // Deterministic fresh-install default; opening PrivacyMask later persists this exact
        // identity instead of replacing it with a random one.
        return new FakeConfig();
    }

    private static String normalizeCountry(String country) {
        if (country == null) return "de";
        return country.trim().toLowerCase(Locale.US);
    }
}
