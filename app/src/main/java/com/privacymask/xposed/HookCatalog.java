package com.privacymask.xposed;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Registry of every individual hook PrivacyMaskModule can install, grouped exactly the way
 * the module groups them internally (hookTelephony, hookSubscriptionInfo, hookLocation,
 * hookTimeZone, hookLocale, hookSystemProperties). Single source of truth for:
 *  - the per-hook remote-preferences key (ConfigKeys.HOOK_*)
 *  - the label shown next to its Switch in MainActivity, under its group's header
 *
 * MainActivity builds its "Hooks" section entirely from {@link #GROUPS}, and FakeConfig seeds
 * every key's default (true) from {@link #allHooks()}. Adding a new individual hook is then
 * just: add one entry here, and check {@code cfg.isHookEnabled(KEY)} at the call site in
 * PrivacyMaskModule — nothing else needs to change.
 *
 * Overloads/variants that fake the exact same thing for the exact same logical getter (e.g.
 * the per-slot TelephonyManager overloads added in API 30, or getMcc()/getMccString()) share a
 * single entry here instead of getting their own switch — they aren't separate features, just
 * different ways to ask for the same value.
 */
public final class HookCatalog {

    private HookCatalog() {}

    public static final class Hook {
        public final String key;
        public final String label;

        Hook(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    public static final class Group {
        public final String title;
        public final List<Hook> hooks;

        Group(String title, Hook... hooks) {
            this.title = title;
            this.hooks = Arrays.asList(hooks);
        }
    }

    public static final List<Group> GROUPS = Arrays.asList(
            new Group("Telephony (TelephonyManager)",
                    new Hook(ConfigKeys.HOOK_TEL_SIM_COUNTRY_ISO, "SIM country ISO (getSimCountryIso)"),
                    new Hook(ConfigKeys.HOOK_TEL_NETWORK_COUNTRY_ISO, "Network country ISO (getNetworkCountryIso)"),
                    new Hook(ConfigKeys.HOOK_TEL_SIM_OPERATOR, "SIM operator MCC/MNC (getSimOperator)"),
                    new Hook(ConfigKeys.HOOK_TEL_NETWORK_OPERATOR, "Network operator MCC/MNC (getNetworkOperator)"),
                    new Hook(ConfigKeys.HOOK_TEL_SIM_OPERATOR_NAME, "SIM operator name (getSimOperatorName)"),
                    new Hook(ConfigKeys.HOOK_TEL_NETWORK_OPERATOR_NAME, "Network operator name (getNetworkOperatorName)"),
                    new Hook(ConfigKeys.HOOK_TEL_PHONE_NUMBER, "Phone number (getLine1Number)")
            ),
            new Group("Subscription info (SubscriptionInfo)",
                    new Hook(ConfigKeys.HOOK_SUB_COUNTRY_ISO, "Country ISO (getCountryIso)"),
                    new Hook(ConfigKeys.HOOK_SUB_CARRIER_NAME, "Carrier name (getCarrierName)"),
                    new Hook(ConfigKeys.HOOK_SUB_DISPLAY_NAME, "Display name (getDisplayName)"),
                    new Hook(ConfigKeys.HOOK_SUB_NUMBER, "Phone number (getNumber)"),
                    new Hook(ConfigKeys.HOOK_SUB_MCC, "MCC (getMcc / getMccString)"),
                    new Hook(ConfigKeys.HOOK_SUB_MNC, "MNC (getMnc / getMncString)")
            ),
            new Group("Location",
                    new Hook(ConfigKeys.HOOK_LOC_LATITUDE, "Latitude (Location.getLatitude)"),
                    new Hook(ConfigKeys.HOOK_LOC_LONGITUDE, "Longitude (Location.getLongitude)"),
                    new Hook(ConfigKeys.HOOK_LOC_ACCURACY, "Accuracy (Location.getAccuracy)"),
                    new Hook(ConfigKeys.HOOK_LOC_LAST_KNOWN, "Last known location (LocationManager.getLastKnownLocation)")
            ),
            new Group("Time zone",
                    new Hook(ConfigKeys.HOOK_TZ_DEFAULT, "Default zone (java.util / Android ICU / Chromium)"),
                    new Hook(ConfigKeys.HOOK_TZ_ZONEID, "System default zone (ZoneId.systemDefault)")
            ),
            new Group("Locale",
                    new Hook(ConfigKeys.HOOK_LOCALE_DEFAULT, "Default locale (Locale.getDefault)"),
                    new Hook(ConfigKeys.HOOK_LOCALE_CONFIGURATION, "Configuration locales (Configuration.getLocales)")
            ),
            new Group("System properties",
                    new Hook(ConfigKeys.HOOK_PROP_TIMEZONE, "persist.sys.timezone"),
                    new Hook(ConfigKeys.HOOK_PROP_LOCALE, "persist.sys.locale"),
                    new Hook(ConfigKeys.HOOK_PROP_COUNTRY_ISO, "gsm.operator.iso-country / gsm.sim.operator.iso-country"),
                    new Hook(ConfigKeys.HOOK_PROP_OPERATOR_NUMERIC, "gsm.operator.numeric / gsm.sim.operator.numeric"),
                    new Hook(ConfigKeys.HOOK_PROP_OPERATOR_ALPHA, "gsm.operator.alpha / gsm.sim.operator.alpha")
            )
    );

    /** Every hook across every group, flattened — used to seed defaults and to save/load. */
    public static List<Hook> allHooks() {
        List<Hook> all = new ArrayList<>();
        for (Group g : GROUPS) all.addAll(g.hooks);
        return all;
    }
}
