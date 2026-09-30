package com.privacymask.xposed;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.text.SimpleDateFormat;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.libxposed.service.XposedService;

public class MainActivity extends AppCompatActivity implements PrivacyMaskApp.ServiceStateListener {

    private static final long SAVE_DEBOUNCE_MS = 600;

    private Spinner spinnerCountry;
    private EditText editCountryIso, editMcc, editMnc;
    private EditText editSimOperatorName, editNetworkOperatorName;
    private EditText editTimezone, editLocaleLanguage, editLocaleCountry;
    private EditText editLat, editLng, editPhone;
    private ViewGroup hooksContainer;
    private final Map<String, Switch> hookSwitches = new LinkedHashMap<>();
    private Button btnApplyPreset, btnRandomize;
    private TextView txtStatus;
    private WorldMapView mapView;

    private List<CountryProfile> profiles;
    private XposedService service;

    private boolean loading = false;
    private boolean updatingFromMap = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable debouncedSave = this::saveAndApply;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        View root = findViewById(R.id.main);
        final int basePadLeft = root.getPaddingLeft();
        final int basePadTop = root.getPaddingTop();
        final int basePadRight = root.getPaddingRight();
        final int basePadBottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(basePadLeft + systemBars.left, basePadTop + systemBars.top,
                    basePadRight + systemBars.right, basePadBottom + systemBars.bottom);
            return insets;
        });

        spinnerCountry = findViewById(R.id.spinnerCountry);
        editCountryIso = findViewById(R.id.editCountryIso);
        editMcc = findViewById(R.id.editMcc);
        editMnc = findViewById(R.id.editMnc);
        editSimOperatorName = findViewById(R.id.editSimOperatorName);
        editNetworkOperatorName = findViewById(R.id.editNetworkOperatorName);
        editTimezone = findViewById(R.id.editTimezone);
        editLocaleLanguage = findViewById(R.id.editLocaleLanguage);
        editLocaleCountry = findViewById(R.id.editLocaleCountry);
        editLat = findViewById(R.id.editLat);
        editLng = findViewById(R.id.editLng);
        editPhone = findViewById(R.id.editPhone);
        mapView = findViewById(R.id.mapView);
        hooksContainer = findViewById(R.id.hooksContainer);
        btnApplyPreset = findViewById(R.id.btnApplyPreset);
        btnRandomize = findViewById(R.id.btnRandomize);
        txtStatus = findViewById(R.id.txtStatus);

        profiles = CountryProfile.all();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item);
        for (CountryProfile p : profiles) {
            adapter.add(p.displayName + " (" + p.isoCountry.toUpperCase(Locale.US) + ")");
        }
        spinnerCountry.setAdapter(adapter);

        buildHookSwitches();
        wireUpAutoSave();

        btnApplyPreset.setOnClickListener(v -> {
            int position = spinnerCountry.getSelectedItemPosition();
            if (position < 0 || position >= profiles.size()) return;
            applyProfileDefaults(profiles.get(position));
            saveAndApply();
        });

        btnRandomize.setOnClickListener(v -> {
            if (service == null) return;
            ConfigStore.randomize(service.getRemotePreferences(ConfigKeys.GROUP));
            loadFromPrefs();
            Toast.makeText(this, "Generated a new random identity.", Toast.LENGTH_SHORT).show();
        });

        setControlsEnabled(false);
        txtStatus.setText("Connecting to the Xposed framework…");
    }

    @Override
    protected void onStart() {
        super.onStart();
        PrivacyMaskApp.addServiceStateListener(this, true);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(debouncedSave);
        PrivacyMaskApp.removeServiceStateListener(this);
        super.onStop();
    }

    @Override
    public void onServiceStateChanged(XposedService boundService) {
        runOnUiThread(() -> {
            service = boundService;
            if (service == null) {
                setControlsEnabled(false);
                txtStatus.setText("Not connected — make sure PrivacyMask is enabled in your "
                        + "framework's Manager app, then reopen this screen.");
                return;
            }
            ConfigStore.ensureDefaultRandomConfig(service.getRemotePreferences(ConfigKeys.GROUP));
            setControlsEnabled(true);
            loadFromPrefs();
        });
    }

    private void buildHookSwitches() {
        int dp8 = dpToPx(8);
        int dp16 = dpToPx(16);

        for (HookCatalog.Group group : HookCatalog.GROUPS) {
            TextView header = new TextView(this);
            header.setText(group.title);
            header.setTextSize(14);
            header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            headerParams.topMargin = dp16;
            header.setLayoutParams(headerParams);
            hooksContainer.addView(header);

            for (HookCatalog.Hook hook : group.hooks) {
                Switch sw = new Switch(this);
                sw.setText(hook.label);
                sw.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams swParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                swParams.topMargin = dp8;
                sw.setLayoutParams(swParams);
                hooksContainer.addView(sw);
                hookSwitches.put(hook.key, sw);
            }
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void wireUpAutoSave() {
        TextWatcher autoSaveWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (loading || updatingFromMap) return;
                Double lat = parseOrNull(editLat.getText().toString());
                Double lng = parseOrNull(editLng.getText().toString());
                if (lat != null && lng != null
                        && lat >= -90.0 && lat <= 90.0
                        && lng >= -180.0 && lng <= 180.0) {
                    mapView.setLocation(lat, lng);
                }
                scheduleDebouncedSave();
            }
        };

        editCountryIso.addTextChangedListener(autoSaveWatcher);
        editMcc.addTextChangedListener(autoSaveWatcher);
        editMnc.addTextChangedListener(autoSaveWatcher);
        editSimOperatorName.addTextChangedListener(autoSaveWatcher);
        editNetworkOperatorName.addTextChangedListener(autoSaveWatcher);
        editTimezone.addTextChangedListener(autoSaveWatcher);
        editLocaleLanguage.addTextChangedListener(autoSaveWatcher);
        editLocaleCountry.addTextChangedListener(autoSaveWatcher);
        editLat.addTextChangedListener(autoSaveWatcher);
        editLng.addTextChangedListener(autoSaveWatcher);
        editPhone.addTextChangedListener(autoSaveWatcher);

        CompoundButton.OnCheckedChangeListener toggleListener =
                (buttonView, isChecked) -> { if (!loading) saveAndApply(); };
        for (Switch sw : hookSwitches.values()) {
            sw.setOnCheckedChangeListener(toggleListener);
        }

        mapView.setOnLocationChangeListener(new WorldMapView.OnLocationChangeListener() {
            @Override
            public void onLocationPreview(double lat, double lng) {
                updatingFromMap = true;
                editLat.setText(formatCoord(lat));
                editLng.setText(formatCoord(lng));
                updatingFromMap = false;
            }

            @Override
            public void onLocationCommitted(double lat, double lng) {
                updatingFromMap = true;
                editLat.setText(formatCoord(lat));
                editLng.setText(formatCoord(lng));
                updatingFromMap = false;
                if (!loading) {
                    handler.removeCallbacks(debouncedSave);
                    saveAndApply();
                }
            }
        });
    }

    /**
     * The country spinner is now a preset loader, not the source of truth. It refreshes the
     * country/operator/timezone/locale fields but deliberately leaves phone number and GPS
     * untouched so a stable manually chosen identity is not changed just by browsing presets.
     */
    private void applyProfileDefaults(CountryProfile p) {
        boolean previousLoading = loading;
        loading = true;
        editCountryIso.setText(p.isoCountry);
        editMcc.setText(p.mcc);
        editMnc.setText(p.mnc);
        editSimOperatorName.setText(p.simOperatorName);
        editNetworkOperatorName.setText(p.networkOperatorName);
        editTimezone.setText(p.timezoneId);
        editLocaleLanguage.setText(p.localeLanguage);
        editLocaleCountry.setText(p.localeCountry);
        loading = previousLoading;
    }

    private void scheduleDebouncedSave() {
        handler.removeCallbacks(debouncedSave);
        handler.postDelayed(debouncedSave, SAVE_DEBOUNCE_MS);
    }

    private void setControlsEnabled(boolean enabled) {
        spinnerCountry.setEnabled(enabled);
        btnApplyPreset.setEnabled(enabled);
        editCountryIso.setEnabled(enabled);
        editMcc.setEnabled(enabled);
        editMnc.setEnabled(enabled);
        editSimOperatorName.setEnabled(enabled);
        editNetworkOperatorName.setEnabled(enabled);
        editTimezone.setEnabled(enabled);
        editLocaleLanguage.setEnabled(enabled);
        editLocaleCountry.setEnabled(enabled);
        editLat.setEnabled(enabled);
        editLng.setEnabled(enabled);
        editPhone.setEnabled(enabled);
        mapView.setEnabled(enabled);
        for (Switch sw : hookSwitches.values()) {
            sw.setEnabled(enabled);
        }
        btnRandomize.setEnabled(enabled);
    }

    private void loadFromPrefs() {
        loading = true;
        SharedPreferences sp = service.getRemotePreferences(ConfigKeys.GROUP);

        String iso = sp.getString(ConfigKeys.COUNTRY, "de");
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).isoCountry.equalsIgnoreCase(iso)) {
                spinnerCountry.setSelection(i);
                break;
            }
        }

        editCountryIso.setText(iso);
        editMcc.setText(sp.getString(ConfigKeys.MCC, "262"));
        editMnc.setText(sp.getString(ConfigKeys.MNC, "01"));
        editSimOperatorName.setText(sp.getString(ConfigKeys.SIM_OP_NAME, ""));
        editNetworkOperatorName.setText(sp.getString(ConfigKeys.NET_OP_NAME, ""));
        editTimezone.setText(sp.getString(ConfigKeys.TIMEZONE, "Europe/Berlin"));
        editLocaleLanguage.setText(sp.getString(ConfigKeys.LOCALE_LANG, "de"));
        editLocaleCountry.setText(sp.getString(ConfigKeys.LOCALE_COUNTRY, "DE"));
        editPhone.setText(sp.getString(ConfigKeys.PHONE, ""));

        double lat = sp.getFloat(ConfigKeys.LAT, 52.5f);
        double lng = sp.getFloat(ConfigKeys.LNG, 13.4f);
        editLat.setText(formatCoord(lat));
        editLng.setText(formatCoord(lng));
        mapView.setLocation(lat, lng);

        for (HookCatalog.Hook hook : HookCatalog.allHooks()) {
            Switch sw = hookSwitches.get(hook.key);
            if (sw != null) sw.setChecked(sp.getBoolean(hook.key, true));
        }

        txtStatus.setText("Connected.");
        loading = false;
    }

    private void saveAndApply() {
        if (service == null || loading) return;

        String isoCountry = editCountryIso.getText().toString().trim().toLowerCase(Locale.US);
        String mcc = editMcc.getText().toString().trim();
        String mnc = editMnc.getText().toString().trim();
        String simOperatorName = editSimOperatorName.getText().toString().trim();
        String networkOperatorName = editNetworkOperatorName.getText().toString().trim();
        String timezoneId = editTimezone.getText().toString().trim();
        String localeLang = editLocaleLanguage.getText().toString().trim().toLowerCase(Locale.US);
        String localeCountry = editLocaleCountry.getText().toString().trim().toUpperCase(Locale.US);
        String phone = editPhone.getText().toString().trim();

        if (!isoCountry.matches("[a-zA-Z]{2}")) {
            showValidationError("Country ISO must be exactly two letters, e.g. us.");
            return;
        }
        if (!mcc.matches("\\d{3}")) {
            showValidationError("MCC must be exactly three digits.");
            return;
        }
        if (!mnc.matches("\\d{2,3}")) {
            showValidationError("MNC must be two or three digits.");
            return;
        }
        if (localeLang.isEmpty()) {
            showValidationError("Locale language cannot be empty.");
            return;
        }
        if (!localeCountry.isEmpty() && !localeCountry.matches("[A-Za-z]{2}")) {
            showValidationError("Locale country/region must be two letters, e.g. US.");
            return;
        }
        try {
            ZoneId.of(timezoneId);
        } catch (DateTimeException ex) {
            showValidationError("Invalid IANA time zone: " + timezoneId);
            return;
        }

        Double lat = parseOrNull(editLat.getText().toString());
        Double lng = parseOrNull(editLng.getText().toString());
        if (lat == null || lat < -90.0 || lat > 90.0) {
            showValidationError("Latitude must be between -90 and 90.");
            return;
        }
        if (lng == null || lng < -180.0 || lng > 180.0) {
            showValidationError("Longitude must be between -180 and 180.");
            return;
        }

        SharedPreferences.Editor e = service.getRemotePreferences(ConfigKeys.GROUP).edit();
        e.putString(ConfigKeys.COUNTRY, isoCountry);
        e.putString(ConfigKeys.MCC, mcc);
        e.putString(ConfigKeys.MNC, mnc);
        e.putString(ConfigKeys.SIM_OP_NAME, simOperatorName);
        e.putString(ConfigKeys.NET_OP_NAME, networkOperatorName);
        e.putString(ConfigKeys.TIMEZONE, timezoneId);
        e.putString(ConfigKeys.LOCALE_LANG, localeLang);
        e.putString(ConfigKeys.LOCALE_COUNTRY, localeCountry);
        e.putString(ConfigKeys.PHONE, phone);
        e.putFloat(ConfigKeys.LAT, lat.floatValue());
        e.putFloat(ConfigKeys.LNG, lng.floatValue());

        for (Map.Entry<String, Switch> entry : hookSwitches.entrySet()) {
            e.putBoolean(entry.getKey(), entry.getValue().isChecked());
        }

        e.apply();
        ConfigStore.bumpVersion(service.getRemotePreferences(ConfigKeys.GROUP));

        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        txtStatus.setText("Saved at " + time + ". Already-running target apps need a manual "
                + "force-stop + reopen (or reboot) to pick this up.");
    }

    private void showValidationError(String message) {
        txtStatus.setText("Not saved: " + message);
    }

    private static Double parseOrNull(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException | NullPointerException ex) {
            return null;
        }
    }

    private static String formatCoord(double v) {
        return String.format(Locale.US, "%.5f", v);
    }
}
