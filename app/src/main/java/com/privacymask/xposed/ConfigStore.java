package com.privacymask.xposed;

import android.content.SharedPreferences;

import java.util.List;
import java.util.Random;

/**
 * Read/write helper around the config, operating on whatever SharedPreferences instance the
 * caller hands it. In this app that instance always comes from
 * {@code XposedService.getRemotePreferences(ConfigKeys.GROUP)} (see MainActivity) — never
 * from {@code Context.getSharedPreferences()} — so nothing is written into PrivacyMask's own
 * app-data folder. The framework stores it and hands hooked apps a read-only view of the
 * same group; that's the only copy that exists.
 */
public final class ConfigStore {

    private ConfigStore() {}

    /** Picks a random identity and saves it if no config has been written yet (first run of the UI). */
    public static void ensureDefaultRandomConfig(SharedPreferences prefs) {
        if (prefs.contains(ConfigKeys.COUNTRY)) return;
        randomize(prefs);
    }

    /** Picks a brand-new random identity (used by the "Generate a new random identity" button). */
    public static void randomize(SharedPreferences prefs) {
        Random r = new Random();
        List<CountryProfile> all = CountryProfile.all();
        CountryProfile p = all.get(r.nextInt(all.size()));

        prefs.edit()
                .putString(ConfigKeys.COUNTRY, p.isoCountry)
                .putString(ConfigKeys.MCC, p.mcc)
                .putString(ConfigKeys.MNC, p.mnc)
                .putString(ConfigKeys.SIM_OP_NAME, p.simOperatorName)
                .putString(ConfigKeys.NET_OP_NAME, p.networkOperatorName)
                .putString(ConfigKeys.TIMEZONE, p.timezoneId)
                .putString(ConfigKeys.LOCALE_LANG, p.localeLanguage)
                .putString(ConfigKeys.LOCALE_COUNTRY, p.localeCountry)
                .putString(ConfigKeys.PHONE, p.randomPhoneNumber(r))
                .putFloat(ConfigKeys.LAT, (float) p.randomLat(r))
                .putFloat(ConfigKeys.LNG, (float) p.randomLng(r))
                .apply();
        bumpVersion(prefs);
    }

    public static void bumpVersion(SharedPreferences prefs) {
        int v = prefs.getInt(ConfigKeys.VERSION, 0);
        prefs.edit().putInt(ConfigKeys.VERSION, v + 1).apply();
    }
}
