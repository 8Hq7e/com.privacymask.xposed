package com.privacymask.xposed;

import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

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
    public final Set<String> scope; // empty = every app the framework scoped this module to
    public final int version;

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

        String rawScope = sp.getString(ConfigKeys.SCOPE, "");
        scope = new HashSet<>();
        if (rawScope != null && !rawScope.trim().isEmpty()) {
            scope.addAll(Arrays.asList(rawScope.split(",")));
        }
    }

    public boolean appliesTo(String packageName) {
        if (scope.isEmpty()) return true;
        return scope.contains(packageName.trim());
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
