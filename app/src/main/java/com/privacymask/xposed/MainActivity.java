package com.privacymask.xposed;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.libxposed.service.XposedService;

public class MainActivity extends AppCompatActivity
        implements PrivacyMaskApp.ServiceStateListener {

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
    private boolean pendingSave = false;

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
            Insets systemBars =
                    insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(
                    basePadLeft + systemBars.left,
                    basePadTop + systemBars.top,
                    basePadRight + systemBars.right,
                    basePadBottom + systemBars.bottom);
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
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item);
        for (CountryProfile p : profiles) {
            adapter.add(
                    p.displayName + " ("
                            + p.isoCountry.toUpperCase(Locale.US) + ")");
        }
        spinnerCountry.setAdapter(adapter);

        buildHookSwitches();
        wireUpAutoSave();

        btnApplyPreset.setOnClickListener(v -> {
            int position = spinnerCountry.getSelectedItemPosition();
            if (position < 0 || position >= profiles.size()) return;
            applyProfileDefaults(profiles.get(position));
            pendingSave = true;
            saveAndApply();
        });

        btnRandomize.setOnClickListener(v -> {
            if (service == null) return;
            handler.removeCallbacks(debouncedSave);
            pendingSave = false;

            ConfigStore.SaveResult result = ConfigStore.randomize(
                    service.getRemotePreferences(ConfigKeys.GROUP));
            if (!result.success) {
                showPersistenceError(result.error);
                return;
            }

            loadSnapshot(result.snapshot, "Random identity saved.");
            Toast.makeText(
                    this,
                    "Generated a new random identity.",
                    Toast.LENGTH_SHORT).show();
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

        // A debounced valid edit must not disappear merely because the Activity stopped before
        // 600 ms elapsed. Persist it synchronously before dropping the service listener.
        if (pendingSave && service != null && !loading) {
            saveAndApply();
        }

        PrivacyMaskApp.removeServiceStateListener(this);
        super.onStop();
    }

    @Override
    public void onServiceStateChanged(XposedService boundService) {
        runOnUiThread(() -> {
            service = boundService;
            if (service == null) {
                setControlsEnabled(false);
                txtStatus.setText(
                        "Not connected — make sure PrivacyMask is enabled in your "
                                + "framework's Manager app, then reopen this screen.");
                return;
            }

            ConfigStore.LoadResult result = ConfigStore.ensureConfig(
                    service.getRemotePreferences(ConfigKeys.GROUP));
            if (!result.success()) {
                setControlsEnabled(false);
                showPersistenceError(result.error);
                return;
            }

            setControlsEnabled(true);
            String status;
            if (result.migratedLegacy) {
                status = "Migrated PrivacyMask 1.2.x settings to atomic config snapshot.";
            } else if (result.createdDefault) {
                status = "Initialized deterministic default identity.";
            } else {
                status = "Connected.";
            }
            loadSnapshot(result.snapshot, status);
        });
    }

    private void buildHookSwitches() {
        int dp8 = dpToPx(8);
        int dp16 = dpToPx(16);

        for (HookCatalog.Group group : HookCatalog.GROUPS) {
            TextView header = new TextView(this);
            header.setText(group.title);
            header.setTextSize(14);
            header.setTypeface(
                    header.getTypeface(),
                    android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams headerParams =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            headerParams.topMargin = dp16;
            header.setLayoutParams(headerParams);
            hooksContainer.addView(header);

            for (HookCatalog.Hook hook : group.hooks) {
                Switch sw = new Switch(this);
                sw.setText(hook.label);
                sw.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams swParams =
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT);
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
            @Override
            public void beforeTextChanged(
                    CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(
                    CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (loading || updatingFromMap) return;

                Double lat = parseOrNull(editLat.getText().toString());
                Double lng = parseOrNull(editLng.getText().toString());
                if (isFiniteCoordinatePair(lat, lng)) {
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
                (buttonView, isChecked) -> {
                    if (!loading) {
                        pendingSave = true;
                        saveAndApply();
                    }
                };
        for (Switch sw : hookSwitches.values()) {
            sw.setOnCheckedChangeListener(toggleListener);
        }

        mapView.setOnLocationChangeListener(
                new WorldMapView.OnLocationChangeListener() {
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
                            pendingSave = true;
                            saveAndApply();
                        }
                    }
                });
    }

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
        pendingSave = true;
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
        if (service == null) return;
        ConfigStore.LoadResult result = ConfigStore.ensureConfig(
                service.getRemotePreferences(ConfigKeys.GROUP));
        if (!result.success()) {
            showPersistenceError(result.error);
            return;
        }
        loadSnapshot(result.snapshot, "Connected.");
    }

    private void loadSnapshot(ConfigSnapshot snapshot, String status) {
        loading = true;
        pendingSave = false;

        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).isoCountry.equalsIgnoreCase(snapshot.isoCountry)) {
                spinnerCountry.setSelection(i);
                break;
            }
        }

        editCountryIso.setText(snapshot.isoCountry);
        editMcc.setText(snapshot.mcc);
        editMnc.setText(snapshot.mnc);
        editSimOperatorName.setText(snapshot.simOperatorName);
        editNetworkOperatorName.setText(snapshot.networkOperatorName);
        editTimezone.setText(snapshot.timezoneId);
        editLocaleLanguage.setText(snapshot.localeLanguage());
        editLocaleCountry.setText(snapshot.localeCountry());
        editPhone.setText(snapshot.phoneNumber);
        editLat.setText(formatCoord(snapshot.latitude));
        editLng.setText(formatCoord(snapshot.longitude));
        mapView.setLocation(snapshot.latitude, snapshot.longitude);

        for (HookCatalog.Hook hook : HookCatalog.allHooks()) {
            Switch sw = hookSwitches.get(hook.key);
            if (sw != null) {
                sw.setChecked(snapshot.isHookEnabled(hook.key));
            }
        }

        ConfigSnapshot.ValidationResult validation = snapshot.validate();
        StringBuilder loadedStatus = new StringBuilder();
        loadedStatus.append(status)
                .append(" Config v")
                .append(snapshot.configVersion)
                .append(".");
        if (validation.warning != null && !validation.warning.isEmpty()) {
            loadedStatus.append(" Warning: ").append(validation.warning);
        }
        txtStatus.setText(loadedStatus.toString());
        loading = false;
    }

    private boolean saveAndApply() {
        if (service == null || loading) return false;

        ConfigSnapshot candidate;
        try {
            candidate = buildCandidateFromUi();
        } catch (IllegalArgumentException ex) {
            showValidationError(ex.getMessage());
            return false;
        }

        ConfigSnapshot.ValidationResult validation = candidate.validate();
        if (!validation.valid) {
            showValidationError(validation.error);
            return false;
        }

        ConfigStore.SaveResult result = ConfigStore.saveNext(
                service.getRemotePreferences(ConfigKeys.GROUP),
                candidate);
        if (!result.success) {
            showPersistenceError(result.error);
            return false;
        }

        pendingSave = false;
        String time =
                new SimpleDateFormat("HH:mm:ss", Locale.US)
                        .format(new Date());

        StringBuilder status = new StringBuilder();
        status.append("Saved config v")
                .append(result.snapshot.configVersion)
                .append(" at ")
                .append(time)
                .append(". Already-running target apps need a force-stop + reopen ")
                .append("(or reboot) to pick this up.");
        if (result.warning != null && !result.warning.isEmpty()) {
            status.append(" Warning: ").append(result.warning);
        }
        txtStatus.setText(status.toString());
        return true;
    }

    private ConfigSnapshot buildCandidateFromUi() {
        String isoCountry =
                editCountryIso.getText().toString().trim()
                        .toLowerCase(Locale.US);
        String mcc = editMcc.getText().toString().trim();
        String mnc = editMnc.getText().toString().trim();
        String simOperatorName =
                editSimOperatorName.getText().toString().trim();
        String networkOperatorName =
                editNetworkOperatorName.getText().toString().trim();
        String timezoneId =
                editTimezone.getText().toString().trim();
        String localeLanguage =
                editLocaleLanguage.getText().toString().trim();
        String localeCountry =
                editLocaleCountry.getText().toString().trim();
        String phone = editPhone.getText().toString().trim();

        Double lat = parseOrNull(editLat.getText().toString());
        Double lng = parseOrNull(editLng.getText().toString());
        if (lat == null || lng == null) {
            throw new IllegalArgumentException(
                    "Latitude and longitude must be valid numbers.");
        }

        String localeTag;
        try {
            localeTag = ConfigSnapshot.normalizeLocaleTag(
                    localeLanguage, localeCountry);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(
                    "Locale language/region is invalid.");
        }

        Map<String, Boolean> hooks = new LinkedHashMap<>();
        for (Map.Entry<String, Switch> entry : hookSwitches.entrySet()) {
            hooks.put(entry.getKey(), entry.getValue().isChecked());
        }

        return new ConfigSnapshot(
                ConfigSnapshot.SCHEMA_VERSION,
                0,
                isoCountry,
                mcc,
                mnc,
                simOperatorName,
                networkOperatorName,
                timezoneId,
                localeTag,
                phone,
                lat,
                lng,
                hooks);
    }

    private void showValidationError(String message) {
        txtStatus.setText("Not saved — validation failed: " + message);
    }

    private void showPersistenceError(String message) {
        txtStatus.setText("Not saved — persistence failed: " + message);
    }

    private static Double parseOrNull(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException | NullPointerException ex) {
            return null;
        }
    }

    private static boolean isFiniteCoordinatePair(Double lat, Double lng) {
        return lat != null
                && lng != null
                && Double.isFinite(lat)
                && Double.isFinite(lng)
                && lat >= -90.0
                && lat <= 90.0
                && lng >= -180.0
                && lng <= 180.0;
    }

    private static String formatCoord(double v) {
        return String.format(Locale.US, "%.5f", v);
    }
}
