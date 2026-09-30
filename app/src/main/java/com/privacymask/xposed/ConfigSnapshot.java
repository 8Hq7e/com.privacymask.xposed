package com.privacymask.xposed;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable, versioned PrivacyMask configuration snapshot.
 *
 * One serialized instance is the only authoritative configuration from schema v2 onward.
 * Identity values, hook flags and configVersion therefore become visible to a target process
 * atomically rather than as a collection of independently updated SharedPreferences keys.
 */
public final class ConfigSnapshot {

    public static final int SCHEMA_VERSION = 2;

    public final int schemaVersion;
    public final int configVersion;

    public final String isoCountry;
    public final String mcc;
    public final String mnc;
    public final String simOperatorName;
    public final String networkOperatorName;
    public final String timezoneId;
    public final String localeTag;
    public final String phoneNumber;
    public final double latitude;
    public final double longitude;

    private final Map<String, Boolean> hookEnabled;

    public static final class ValidationResult {
        public final boolean valid;
        public final String error;
        public final String warning;

        private ValidationResult(boolean valid, String error, String warning) {
            this.valid = valid;
            this.error = error;
            this.warning = warning;
        }

        public static ValidationResult ok(String warning) {
            return new ValidationResult(true, null, warning);
        }

        public static ValidationResult error(String error) {
            return new ValidationResult(false, error, null);
        }
    }

    public ConfigSnapshot(
            int schemaVersion,
            int configVersion,
            String isoCountry,
            String mcc,
            String mnc,
            String simOperatorName,
            String networkOperatorName,
            String timezoneId,
            String localeTag,
            String phoneNumber,
            double latitude,
            double longitude,
            Map<String, Boolean> hookEnabled) {
        this.schemaVersion = schemaVersion;
        this.configVersion = configVersion;
        this.isoCountry = isoCountry;
        this.mcc = mcc;
        this.mnc = mnc;
        this.simOperatorName = simOperatorName;
        this.networkOperatorName = networkOperatorName;
        this.timezoneId = timezoneId;
        this.localeTag = localeTag;
        this.phoneNumber = phoneNumber;
        this.latitude = latitude;
        this.longitude = longitude;
        this.hookEnabled = Collections.unmodifiableMap(new LinkedHashMap<>(hookEnabled));
    }

    public boolean isHookEnabled(String key) {
        Boolean enabled = hookEnabled.get(key);
        return enabled == null || enabled;
    }

    public Map<String, Boolean> hookMap() {
        return hookEnabled;
    }

    public String localeLanguage() {
        Locale locale = Locale.forLanguageTag(localeTag);
        return locale.getLanguage();
    }

    public String localeCountry() {
        Locale locale = Locale.forLanguageTag(localeTag);
        return locale.getCountry();
    }

    public String operatorNumeric() {
        return mcc + mnc;
    }

    public ConfigSnapshot withConfigVersion(int newVersion) {
        return new ConfigSnapshot(
                schemaVersion,
                newVersion,
                isoCountry,
                mcc,
                mnc,
                simOperatorName,
                networkOperatorName,
                timezoneId,
                localeTag,
                phoneNumber,
                latitude,
                longitude,
                hookEnabled);
    }

    public String toJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", schemaVersion);
        root.put("configVersion", configVersion);

        JSONObject identity = new JSONObject();
        identity.put("countryIso", isoCountry);
        identity.put("mcc", mcc);
        identity.put("mnc", mnc);
        identity.put("simOperatorName", simOperatorName);
        identity.put("networkOperatorName", networkOperatorName);
        identity.put("timezone", timezoneId);
        identity.put("localeTag", localeTag);
        identity.put("phoneNumber", phoneNumber);
        identity.put("latitude", latitude);
        identity.put("longitude", longitude);
        root.put("identity", identity);

        JSONObject hooks = new JSONObject();
        for (HookCatalog.Hook hook : HookCatalog.allHooks()) {
            hooks.put(hook.key, isHookEnabled(hook.key));
        }
        root.put("hooks", hooks);
        return root.toString();
    }

    public static ConfigSnapshot fromJson(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        int schemaVersion = root.getInt("schemaVersion");
        if (schemaVersion != SCHEMA_VERSION) {
            throw new JSONException("Unsupported schemaVersion " + schemaVersion);
        }

        int configVersion = root.getInt("configVersion");
        JSONObject identity = root.getJSONObject("identity");
        JSONObject hooks = root.optJSONObject("hooks");

        Map<String, Boolean> hookMap = new LinkedHashMap<>();
        for (HookCatalog.Hook hook : HookCatalog.allHooks()) {
            hookMap.put(
                    hook.key,
                    hooks == null || !hooks.has(hook.key)
                            ? true
                            : hooks.optBoolean(hook.key, true));
        }

        return new ConfigSnapshot(
                schemaVersion,
                configVersion,
                identity.getString("countryIso"),
                identity.getString("mcc"),
                identity.getString("mnc"),
                identity.optString("simOperatorName", ""),
                identity.optString("networkOperatorName", ""),
                identity.getString("timezone"),
                identity.getString("localeTag"),
                identity.optString("phoneNumber", ""),
                identity.getDouble("latitude"),
                identity.getDouble("longitude"),
                hookMap);
    }

    public static String normalizeLocaleTag(String language, String country) {
        String normalizedLanguage =
                language == null ? "" : language.trim().toLowerCase(Locale.US);
        String normalizedCountry =
                country == null ? "" : country.trim().toUpperCase(Locale.US);

        Locale.Builder builder = new Locale.Builder();
        builder.setLanguage(normalizedLanguage);
        if (!normalizedCountry.isEmpty()) {
            builder.setRegion(normalizedCountry);
        }
        return builder.build().toLanguageTag();
    }

    public ValidationResult validate() {
        if (schemaVersion != SCHEMA_VERSION) {
            return ValidationResult.error("Unsupported config schema " + schemaVersion + ".");
        }
        if (configVersion < 0) {
            return ValidationResult.error("Config version cannot be negative.");
        }
        if (isoCountry == null || !isoCountry.matches("[a-zA-Z]{2}")) {
            return ValidationResult.error(
                    "Country ISO must be exactly two letters, e.g. us.");
        }
        if (mcc == null || !mcc.matches("\\d{3}")) {
            return ValidationResult.error("MCC must be exactly three digits.");
        }
        if (mnc == null || !mnc.matches("\\d{2,3}")) {
            return ValidationResult.error("MNC must be two or three digits.");
        }
        if (timezoneId == null || timezoneId.trim().isEmpty()) {
            return ValidationResult.error("Time zone cannot be empty.");
        }
        try {
            ZoneId.of(timezoneId);
        } catch (DateTimeException ex) {
            return ValidationResult.error("Invalid IANA time zone: " + timezoneId);
        }

        Locale locale;
        try {
            locale = Locale.forLanguageTag(localeTag == null ? "" : localeTag);
        } catch (Throwable t) {
            return ValidationResult.error("Invalid locale tag.");
        }
        if (locale.getLanguage().isEmpty()
                || "und".equalsIgnoreCase(locale.toLanguageTag())) {
            return ValidationResult.error("Locale language cannot be empty.");
        }

        if (!Double.isFinite(latitude) || latitude < -90.0 || latitude > 90.0) {
            return ValidationResult.error(
                    "Latitude must be finite and between -90 and 90.");
        }
        if (!Double.isFinite(longitude) || longitude < -180.0 || longitude > 180.0) {
            return ValidationResult.error(
                    "Longitude must be finite and between -180 and 180.");
        }

        // Phone number policy for schema v2: empty is allowed because Android may legitimately
        // expose no line number. Non-empty values must use a basic E.164 representation so the
        // same snapshot is not interpreted differently by different telephony call paths.
        if (phoneNumber == null) {
            return ValidationResult.error("Phone number cannot be null.");
        }
        if (!phoneNumber.isEmpty()
                && !phoneNumber.matches("\\+[1-9]\\d{6,14}")) {
            return ValidationResult.error(
                    "Phone number must be empty or use E.164 form, e.g. +12135550147.");
        }

        List<String> warnings = new ArrayList<>();
        if (!locale.getCountry().isEmpty()
                && !locale.getCountry().equalsIgnoreCase(isoCountry)) {
            warnings.add(
                    "Locale region " + locale.getCountry()
                            + " differs from identity country "
                            + isoCountry.toUpperCase(Locale.US) + ".");
        }

        // Custom values are deliberately permitted. Built-in profiles are used only to surface
        // contradictions that are easy to create accidentally; they do not block saving.
        CountryProfile reference = findReferenceProfile(isoCountry);
        if (reference != null) {
            if (!reference.mcc.equals(mcc)) {
                warnings.add(
                        "MCC " + mcc + " differs from the built-in "
                                + reference.displayName + " profile (" + reference.mcc + ").");
            }
            if (!reference.timezoneId.equals(timezoneId)) {
                warnings.add(
                        "Time zone " + timezoneId + " differs from the built-in "
                                + reference.displayName + " profile ("
                                + reference.timezoneId + ").");
            }
            if (!phoneNumber.isEmpty()
                    && !phoneNumber.startsWith(reference.phonePrefix)) {
                warnings.add(
                        "Phone prefix does not match the built-in "
                                + reference.displayName + " profile ("
                                + reference.phonePrefix + ").");
            }
        }

        String warning = warnings.isEmpty() ? null : String.join(" ", warnings);
        return ValidationResult.ok(warning);
    }

    private static CountryProfile findReferenceProfile(String isoCountry) {
        if (isoCountry == null) return null;
        for (CountryProfile profile : CountryProfile.all()) {
            if (profile.isoCountry.equalsIgnoreCase(isoCountry)) {
                return profile;
            }
        }
        return null;
    }
}
