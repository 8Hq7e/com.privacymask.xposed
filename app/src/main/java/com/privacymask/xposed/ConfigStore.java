package com.privacymask.xposed;

import android.content.SharedPreferences;

import org.json.JSONException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Authoritative persistence layer for PrivacyMask configuration.
 *
 * Schema v2 stores one complete JSON snapshot under {@link ConfigKeys#SNAPSHOT_JSON}. That single
 * value contains identity fields, hook flags and configVersion, so a target process cannot observe
 * a half-written identity where, for example, the new time zone is paired with the previous MCC.
 *
 * Legacy 1.2.x keys are read only for one-time migration and are intentionally left in place after
 * migration so rollback to an older APK remains possible during development.
 */
public final class ConfigStore {

    private ConfigStore() {}

    public static final class LoadResult {
        public final ConfigSnapshot snapshot;
        public final boolean migratedLegacy;
        public final boolean createdDefault;
        public final String error;

        private LoadResult(
                ConfigSnapshot snapshot,
                boolean migratedLegacy,
                boolean createdDefault,
                String error) {
            this.snapshot = snapshot;
            this.migratedLegacy = migratedLegacy;
            this.createdDefault = createdDefault;
            this.error = error;
        }

        public boolean success() {
            return snapshot != null && error == null;
        }
    }

    public static final class SaveResult {
        public final boolean success;
        public final ConfigSnapshot snapshot;
        public final String error;
        public final String warning;

        private SaveResult(
                boolean success,
                ConfigSnapshot snapshot,
                String error,
                String warning) {
            this.success = success;
            this.snapshot = snapshot;
            this.error = error;
            this.warning = warning;
        }

        static SaveResult success(ConfigSnapshot snapshot, String warning) {
            return new SaveResult(true, snapshot, null, warning);
        }

        static SaveResult failure(String error) {
            return new SaveResult(false, null, error, null);
        }
    }

    /**
     * Loads schema-v2 config, migrates a legacy 1.2.x config, or creates one deterministic
     * default. Fresh installs are never randomized implicitly.
     */
    public static LoadResult ensureConfig(SharedPreferences prefs) {
        LoadResult existing = readSnapshot(prefs);
        if (existing.success()) {
            return existing;
        }

        if (prefs.contains(ConfigKeys.SNAPSHOT_JSON)) {
            return existing;
        }

        if (prefs.contains(ConfigKeys.COUNTRY)) {
            ConfigSnapshot migrated = legacySnapshot(prefs);
            SaveResult saved = writeExactSnapshot(prefs, migrated);
            if (!saved.success) {
                return new LoadResult(
                        null, false, false,
                        "Legacy config migration failed: " + saved.error);
            }
            return new LoadResult(saved.snapshot, true, false, null);
        }

        ConfigSnapshot defaultSnapshot = defaultSnapshot();
        SaveResult saved = writeExactSnapshot(prefs, defaultSnapshot);
        if (!saved.success) {
            return new LoadResult(
                    null, false, false,
                    "Default config initialization failed: " + saved.error);
        }
        return new LoadResult(saved.snapshot, false, true, null);
    }

    /**
     * Reads only the authoritative schema-v2 snapshot. No legacy fallback is performed here.
     */
    public static LoadResult readSnapshot(SharedPreferences prefs) {
        String json = prefs.getString(ConfigKeys.SNAPSHOT_JSON, null);
        if (json == null || json.trim().isEmpty()) {
            return new LoadResult(null, false, false, "No config snapshot exists.");
        }

        try {
            ConfigSnapshot snapshot = ConfigSnapshot.fromJson(json);
            ConfigSnapshot.ValidationResult validation = snapshot.validate();
            if (!validation.valid) {
                return new LoadResult(
                        null, false, false,
                        "Saved config is invalid: " + validation.error);
            }
            return new LoadResult(snapshot, false, false, null);
        } catch (JSONException | RuntimeException ex) {
            return new LoadResult(
                    null, false, false,
                    "Cannot parse config snapshot: " + ex.getMessage());
        }
    }

    /**
     * Saves a user-edited candidate as one atomic snapshot. configVersion is incremented inside
     * the same serialized value, so version and data can never disagree.
     */
    public static SaveResult saveNext(
            SharedPreferences prefs, ConfigSnapshot candidate) {
        LoadResult current = ensureConfig(prefs);
        if (!current.success()) {
            return SaveResult.failure(current.error);
        }

        int nextVersion = current.snapshot.configVersion + 1;
        ConfigSnapshot next = candidate.withConfigVersion(nextVersion);
        return writeExactSnapshot(prefs, next);
    }

    /**
     * Explicit randomization only. Hook flags are preserved from the current snapshot.
     */
    public static SaveResult randomize(SharedPreferences prefs) {
        LoadResult current = ensureConfig(prefs);
        if (!current.success()) {
            return SaveResult.failure(current.error);
        }

        Random r = new Random();
        List<CountryProfile> all = CountryProfile.all();
        CountryProfile p = all.get(r.nextInt(all.size()));

        Map<String, Boolean> hooks = new LinkedHashMap<>();
        for (HookCatalog.Hook hook : HookCatalog.allHooks()) {
            hooks.put(hook.key, current.snapshot.isHookEnabled(hook.key));
        }

        String localeTag = ConfigSnapshot.normalizeLocaleTag(
                p.localeLanguage, p.localeCountry);

        ConfigSnapshot candidate = new ConfigSnapshot(
                ConfigSnapshot.SCHEMA_VERSION,
                current.snapshot.configVersion,
                p.isoCountry.toLowerCase(Locale.US),
                p.mcc,
                p.mnc,
                p.simOperatorName,
                p.networkOperatorName,
                p.timezoneId,
                localeTag,
                p.randomPhoneNumber(r),
                p.randomLat(r),
                p.randomLng(r),
                hooks);

        return saveNext(prefs, candidate);
    }

    private static SaveResult writeExactSnapshot(
            SharedPreferences prefs, ConfigSnapshot snapshot) {
        ConfigSnapshot.ValidationResult validation = snapshot.validate();
        if (!validation.valid) {
            return SaveResult.failure(validation.error);
        }

        try {
            String json = snapshot.toJson();
            boolean committed = prefs.edit()
                    .putString(ConfigKeys.SNAPSHOT_JSON, json)
                    .commit();
            if (!committed) {
                return SaveResult.failure(
                        "Remote preferences rejected the atomic config commit.");
            }
            return SaveResult.success(snapshot, validation.warning);
        } catch (JSONException | RuntimeException ex) {
            return SaveResult.failure(
                    "Could not serialize/save config snapshot: " + ex.getMessage());
        }
    }

    /**
     * Deterministic fresh-install identity. This intentionally mirrors the old FakeConfig
     * fallback so upgrading behavior is predictable and a UI launch no longer changes identity.
     */
    static ConfigSnapshot defaultSnapshot() {
        Map<String, Boolean> hooks = allHooksEnabled();

        return new ConfigSnapshot(
                ConfigSnapshot.SCHEMA_VERSION,
                1,
                "de",
                "262",
                "01",
                "T-Mobile DE",
                "T-Mobile DE",
                "Europe/Berlin",
                "de-DE",
                "+491234567",
                52.5,
                13.4,
                hooks);
    }

    private static ConfigSnapshot legacySnapshot(SharedPreferences prefs) {
        String language = prefs.getString(ConfigKeys.LOCALE_LANG, "de");
        String country = prefs.getString(ConfigKeys.LOCALE_COUNTRY, "DE");
        String localeTag;
        try {
            localeTag = ConfigSnapshot.normalizeLocaleTag(language, country);
        } catch (RuntimeException ex) {
            localeTag = "de-DE";
        }

        Map<String, Boolean> hooks = new LinkedHashMap<>();
        for (HookCatalog.Hook hook : HookCatalog.allHooks()) {
            hooks.put(hook.key, prefs.getBoolean(hook.key, true));
        }

        int legacyVersion = Math.max(1, prefs.getInt(ConfigKeys.VERSION, 0));

        return new ConfigSnapshot(
                ConfigSnapshot.SCHEMA_VERSION,
                legacyVersion,
                normalizeCountry(prefs.getString(ConfigKeys.COUNTRY, "de")),
                prefs.getString(ConfigKeys.MCC, "262"),
                prefs.getString(ConfigKeys.MNC, "01"),
                prefs.getString(ConfigKeys.SIM_OP_NAME, ""),
                prefs.getString(ConfigKeys.NET_OP_NAME, ""),
                prefs.getString(ConfigKeys.TIMEZONE, "Europe/Berlin"),
                localeTag,
                prefs.getString(ConfigKeys.PHONE, "+491234567"),
                prefs.getFloat(ConfigKeys.LAT, 52.5f),
                prefs.getFloat(ConfigKeys.LNG, 13.4f),
                hooks);
    }

    private static Map<String, Boolean> allHooksEnabled() {
        Map<String, Boolean> hooks = new LinkedHashMap<>();
        for (HookCatalog.Hook hook : HookCatalog.allHooks()) {
            hooks.put(hook.key, true);
        }
        return hooks;
    }

    private static String normalizeCountry(String country) {
        if (country == null) return "de";
        return country.trim().toLowerCase(Locale.US);
    }
}
