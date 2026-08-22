package com.privacymask.xposed;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A complete, self-consistent "identity": country, timezone, carrier, locale, and a
 * bounding box used to generate a plausible random coordinate inside that country.
 * All fields are chosen together so apps never see a contradiction (e.g. a German
 * carrier paired with a US timezone).
 */
public class CountryProfile {
    public final String displayName;
    public final String isoCountry;     // lowercase ISO-3166 alpha-2, e.g. "de"
    public final String mcc;
    public final String mnc;
    public final String simOperatorName;
    public final String networkOperatorName;
    public final String timezoneId;     // e.g. "Europe/Berlin"
    public final String localeLanguage; // "de"
    public final String localeCountry;  // "DE"
    public final String phonePrefix;    // "+49"
    public final double latMin, latMax, lngMin, lngMax;

    public CountryProfile(String displayName, String isoCountry, String mcc, String mnc,
                           String simOperatorName, String networkOperatorName,
                           String timezoneId, String localeLanguage, String localeCountry,
                           String phonePrefix,
                           double latMin, double latMax, double lngMin, double lngMax) {
        this.displayName = displayName;
        this.isoCountry = isoCountry;
        this.mcc = mcc;
        this.mnc = mnc;
        this.simOperatorName = simOperatorName;
        this.networkOperatorName = networkOperatorName;
        this.timezoneId = timezoneId;
        this.localeLanguage = localeLanguage;
        this.localeCountry = localeCountry;
        this.phonePrefix = phonePrefix;
        this.latMin = latMin;
        this.latMax = latMax;
        this.lngMin = lngMin;
        this.lngMax = lngMax;
    }

    public String operatorNumeric() {
        return mcc + mnc;
    }

    public String randomPhoneNumber(Random r) {
        StringBuilder sb = new StringBuilder(phonePrefix);
        for (int i = 0; i < 9; i++) sb.append(r.nextInt(10));
        return sb.toString();
    }

    public double randomLat(Random r) {
        return latMin + r.nextDouble() * (latMax - latMin);
    }

    public double randomLng(Random r) {
        return lngMin + r.nextDouble() * (lngMax - lngMin);
    }

    public static List<CountryProfile> all() {
        List<CountryProfile> list = new ArrayList<>();
        list.add(new CountryProfile("Germany", "de", "262", "01", "T-Mobile DE", "T-Mobile DE",
                "Europe/Berlin", "de", "DE", "+49", 47.5, 55.0, 6.0, 15.0));
        list.add(new CountryProfile("Netherlands", "nl", "204", "04", "Vodafone NL", "Vodafone NL",
                "Europe/Amsterdam", "nl", "NL", "+31", 50.8, 53.5, 3.5, 7.2));
        list.add(new CountryProfile("France", "fr", "208", "01", "Orange F", "Orange F",
                "Europe/Paris", "fr", "FR", "+33", 42.5, 51.0, -4.5, 7.5));
        list.add(new CountryProfile("United Kingdom", "gb", "234", "15", "Vodafone UK", "Vodafone UK",
                "Europe/London", "en", "GB", "+44", 50.0, 58.5, -6.0, 1.7));
        list.add(new CountryProfile("Italy", "it", "222", "01", "TIM", "TIM",
                "Europe/Rome", "it", "IT", "+39", 37.0, 46.5, 7.0, 18.0));
        list.add(new CountryProfile("Spain", "es", "214", "01", "Vodafone ES", "Vodafone ES",
                "Europe/Madrid", "es", "ES", "+34", 36.0, 43.5, -9.0, 3.0));
        list.add(new CountryProfile("Turkey", "tr", "286", "01", "Turkcell", "Turkcell",
                "Europe/Istanbul", "tr", "TR", "+90", 36.0, 42.0, 26.0, 44.5));
        list.add(new CountryProfile("United Arab Emirates", "ae", "424", "02", "du", "du",
                "Asia/Dubai", "ar", "AE", "+971", 22.6, 26.1, 51.5, 56.4));
        list.add(new CountryProfile("Japan", "jp", "440", "10", "NTT DoCoMo", "NTT DoCoMo",
                "Asia/Tokyo", "ja", "JP", "+81", 31.0, 43.5, 130.0, 145.0));
        list.add(new CountryProfile("Canada", "ca", "302", "72", "Rogers", "Rogers",
                "America/Toronto", "en", "CA", "+1", 43.0, 55.0, -95.0, -75.0));
        list.add(new CountryProfile("United States", "us", "310", "260", "T-Mobile US", "T-Mobile US",
                "America/New_York", "en", "US", "+1", 30.0, 44.0, -100.0, -75.0));
        list.add(new CountryProfile("Switzerland", "ch", "228", "01", "Swisscom", "Swisscom",
                "Europe/Zurich", "de", "CH", "+41", 45.8, 47.8, 6.0, 10.5));
        return list;
    }
}
