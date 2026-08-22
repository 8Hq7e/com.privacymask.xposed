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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import io.github.libxposed.service.XposedService;

public class MainActivity extends AppCompatActivity implements PrivacyMaskApp.ServiceStateListener {

    /** How long to wait after the user stops typing in a text field before saving. */
    private static final long SAVE_DEBOUNCE_MS = 600;

    private Spinner spinnerCountry;
    private EditText editLat, editLng, editPhone;
    private ViewGroup hooksContainer;
    /** One Switch per individual hook, keyed by its ConfigKeys.HOOK_* key. Built once in
     *  onCreate() from HookCatalog and reused for the whole activity lifetime. */
    private final Map<String, Switch> hookSwitches = new LinkedHashMap<>();
    private Button btnRandomize;
    private TextView txtStatus;
    private WorldMapView mapView;

    private List<CountryProfile> profiles;
    private XposedService service;

    // Guards against feedback loops / premature autosaves while we're programmatically
    // populating the controls (initial load) or syncing the map <-> text fields.
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
        // Capture the padding already set from XML (android:padding="20dp") once, before the
        // inset listener starts overwriting it, so every inset update adds to that base
        // instead of replacing it outright.
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
        editLat = findViewById(R.id.editLat);
        editLng = findViewById(R.id.editLng);
        editPhone = findViewById(R.id.editPhone);
        mapView = findViewById(R.id.mapView);
        hooksContainer = findViewById(R.id.hooksContainer);
        btnRandomize = findViewById(R.id.btnRandomize);
        txtStatus = findViewById(R.id.txtStatus);

        profiles = CountryProfile.all();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item);
        for (CountryProfile p : profiles) adapter.add(p.displayName + " (" + p.isoCountry.toUpperCase(Locale.US) + ")");
        spinnerCountry.setAdapter(adapter);

        buildHookSwitches();
        wireUpAutoSave();

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
            // First time this remote-preferences group is touched: pick a random default
            // identity, same as before, just written remotely instead of to a local file.
            ConfigStore.ensureDefaultRandomConfig(service.getRemotePreferences(ConfigKeys.GROUP));
            setControlsEnabled(true);
            loadFromPrefs();
        });
    }

    // -----------------------------------------------------------------
    // Builds the "Hooks" section from HookCatalog: a bold header per group, followed by one
    // Switch per individual hook in that group. Populates hookSwitches so the rest of the
    // activity can read/write every switch generically instead of by field name.
    // -----------------------------------------------------------------
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

    // -----------------------------------------------------------------
    // Autosave wiring: every control below saves and applies on its own, the moment it
    // changes. There's no separate Apply button — text fields are debounced briefly so we
    // don't write on every keystroke, everything else (spinner, switches, map release) saves
    // right away.
    // -----------------------------------------------------------------
    private void wireUpAutoSave() {
        TextWatcher autoSaveWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (loading || updatingFromMap) return;
                // Keep the pin in sync while the user hand-types coordinates.
                Double lat = parseOrNull(editLat.getText().toString());
                Double lng = parseOrNull(editLng.getText().toString());
                if (lat != null && lng != null) mapView.setLocation(lat, lng);
                scheduleDebouncedSave();
            }
        };
        editLat.addTextChangedListener(autoSaveWatcher);
        editLng.addTextChangedListener(autoSaveWatcher);
        editPhone.addTextChangedListener(autoSaveWatcher);

        spinnerCountry.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!loading) saveAndApply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

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

    private void scheduleDebouncedSave() {
        handler.removeCallbacks(debouncedSave);
        handler.postDelayed(debouncedSave, SAVE_DEBOUNCE_MS);
    }

    private void setControlsEnabled(boolean enabled) {
        spinnerCountry.setEnabled(enabled);
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

        double lat = sp.getFloat(ConfigKeys.LAT, 52.5f);
        double lng = sp.getFloat(ConfigKeys.LNG, 13.4f);
        editLat.setText(formatCoord(lat));
        editLng.setText(formatCoord(lng));
        mapView.setLocation(lat, lng);

        editPhone.setText(sp.getString(ConfigKeys.PHONE, ""));

        for (HookCatalog.Hook hook : HookCatalog.allHooks()) {
            Switch sw = hookSwitches.get(hook.key);
            if (sw != null) sw.setChecked(sp.getBoolean(hook.key, true));
        }

        txtStatus.setText("Connected.");
        loading = false;
    }

    /**
     * Saves every control's current value to the module's remote preferences (writable from
     * here — PrivacyMask's own process — even though hooked apps only ever get a read-only
     * view of the same data) and bumps the config version. Called automatically the moment
     * anything changes; there's no separate Apply step.
     *
     * A process that's already running when this fires needs a manual force-stop + reopen (or
     * reboot) to pick up the new values — PrivacyMask never force-stops other apps itself.
     */
    private void saveAndApply() {
        if (service == null || loading) return;

        SharedPreferences.Editor e = service.getRemotePreferences(ConfigKeys.GROUP).edit();
        CountryProfile selected = profiles.get(spinnerCountry.getSelectedItemPosition());
        e.putString(ConfigKeys.COUNTRY, selected.isoCountry);
        e.putString(ConfigKeys.MCC, selected.mcc);
        e.putString(ConfigKeys.MNC, selected.mnc);
        e.putString(ConfigKeys.SIM_OP_NAME, selected.simOperatorName);
        e.putString(ConfigKeys.NET_OP_NAME, selected.networkOperatorName);
        e.putString(ConfigKeys.TIMEZONE, selected.timezoneId);
        e.putString(ConfigKeys.LOCALE_LANG, selected.localeLanguage);
        e.putString(ConfigKeys.LOCALE_COUNTRY, selected.localeCountry);

        Double lat = parseOrNull(editLat.getText().toString());
        Double lng = parseOrNull(editLng.getText().toString());
        e.putFloat(ConfigKeys.LAT, lat != null ? lat.floatValue() : (float) selected.randomLat(new Random()));
        e.putFloat(ConfigKeys.LNG, lng != null ? lng.floatValue() : (float) selected.randomLng(new Random()));

        e.putString(ConfigKeys.PHONE, editPhone.getText().toString().trim());

        for (Map.Entry<String, Switch> entry : hookSwitches.entrySet()) {
            e.putBoolean(entry.getKey(), entry.getValue().isChecked());
        }

        e.apply();
        ConfigStore.bumpVersion(service.getRemotePreferences(ConfigKeys.GROUP));

        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        txtStatus.setText("Saved at " + time + ". Already-running target apps need a manual "
                + "force-stop + reopen (or reboot) to pick this up.");
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
