package com.privacymask.xposed;

/**
 * Key names for the single remote-preferences group PrivacyMask shares with hooked apps
 * through the Xposed framework (XposedService.getRemotePreferences / XposedInterface's
 * getRemotePreferences). Kept in one place so the app side (writer) and the module side
 * (reader) can't drift apart.
 */
public final class ConfigKeys {

    private ConfigKeys() {}

    /** Remote-preferences group name. */
    public static final String GROUP = "privacymask_config";

    /** Authoritative schema-v2+ configuration snapshot. */
    public static final String SNAPSHOT_JSON = "config_snapshot_json";

    // Legacy schema-v1 keys retained only for one-time migration from PrivacyMask 1.2.x.
    public static final String COUNTRY = "iso_country";
    public static final String MCC = "mcc";
    public static final String MNC = "mnc";
    public static final String SIM_OP_NAME = "sim_operator_name";
    public static final String NET_OP_NAME = "network_operator_name";
    public static final String TIMEZONE = "timezone_id";
    public static final String LOCALE_LANG = "locale_lang";
    public static final String LOCALE_COUNTRY = "locale_country";
    public static final String PHONE = "phone_number";
    public static final String LAT = "latitude";
    public static final String LNG = "longitude";
    public static final String VERSION = "config_version"; // legacy migration source only

    // Per-hook on/off switches — one per individual hook() call PrivacyMaskModule installs,
    // NOT one per group. Which apps get hooked at all is entirely up to the framework's own
    // scope screen (Vector/LSPosed Manager); these only control which of the hooks below fire
    // once a process is in scope. All default to true (see FakeConfig / HookCatalog) so a
    // fresh install — or one upgrading from the old six group-level switches — behaves exactly
    // like before this option existed.
    //
    // See HookCatalog for how these are grouped and labeled in the UI.

    // -- Telephony (TelephonyManager) --
    public static final String HOOK_TEL_SIM_COUNTRY_ISO = "hook_tel_sim_country_iso";
    public static final String HOOK_TEL_NETWORK_COUNTRY_ISO = "hook_tel_network_country_iso";
    public static final String HOOK_TEL_SIM_OPERATOR = "hook_tel_sim_operator";
    public static final String HOOK_TEL_NETWORK_OPERATOR = "hook_tel_network_operator";
    public static final String HOOK_TEL_SIM_OPERATOR_NAME = "hook_tel_sim_operator_name";
    public static final String HOOK_TEL_NETWORK_OPERATOR_NAME = "hook_tel_network_operator_name";
    public static final String HOOK_TEL_PHONE_NUMBER = "hook_tel_phone_number";

    // -- Subscription info (SubscriptionInfo) --
    public static final String HOOK_SUB_COUNTRY_ISO = "hook_sub_country_iso";
    public static final String HOOK_SUB_CARRIER_NAME = "hook_sub_carrier_name";
    public static final String HOOK_SUB_DISPLAY_NAME = "hook_sub_display_name";
    public static final String HOOK_SUB_NUMBER = "hook_sub_number";
    public static final String HOOK_SUB_MCC = "hook_sub_mcc";
    public static final String HOOK_SUB_MNC = "hook_sub_mnc";

    // -- Location --
    public static final String HOOK_LOC_LATITUDE = "hook_loc_latitude";
    public static final String HOOK_LOC_LONGITUDE = "hook_loc_longitude";
    public static final String HOOK_LOC_ACCURACY = "hook_loc_accuracy";
    public static final String HOOK_LOC_LAST_KNOWN = "hook_loc_last_known";

    // -- Time zone --
    public static final String HOOK_TZ_DEFAULT = "hook_tz_default";
    public static final String HOOK_TZ_ZONEID = "hook_tz_zoneid";

    // -- Locale --
    public static final String HOOK_LOCALE_DEFAULT = "hook_locale_default";
    public static final String HOOK_LOCALE_CONFIGURATION = "hook_locale_configuration";

    // -- System properties --
    public static final String HOOK_PROP_TIMEZONE = "hook_prop_timezone";
    public static final String HOOK_PROP_LOCALE = "hook_prop_locale";
    public static final String HOOK_PROP_COUNTRY_ISO = "hook_prop_country_iso";
    public static final String HOOK_PROP_OPERATOR_NUMERIC = "hook_prop_operator_numeric";
    public static final String HOOK_PROP_OPERATOR_ALPHA = "hook_prop_operator_alpha";
}
