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
    public static final String SCOPE = "scope_packages"; // comma-separated; empty = every scoped app
    public static final String VERSION = "config_version"; // bumped on every Apply
}
