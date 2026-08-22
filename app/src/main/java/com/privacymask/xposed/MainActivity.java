package com.privacymask.xposed;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import io.github.libxposed.service.XposedService;

public class MainActivity extends Activity implements PrivacyMaskApp.ServiceStateListener {

    private Spinner spinnerCountry;
    private EditText editLat, editLng, editPhone, editScope;
    private Button btnRandomize, btnApply;
    private TextView txtStatus;

    private List<CountryProfile> profiles;
    private XposedService service;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        spinnerCountry = findViewById(R.id.spinnerCountry);
        editLat = findViewById(R.id.editLat);
        editLng = findViewById(R.id.editLng);
        editPhone = findViewById(R.id.editPhone);
        editScope = findViewById(R.id.editScope);
        btnRandomize = findViewById(R.id.btnRandomize);
        btnApply = findViewById(R.id.btnApply);
        txtStatus = findViewById(R.id.txtStatus);

        profiles = CountryProfile.all();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item);
        for (CountryProfile p : profiles) adapter.add(p.displayName + " (" + p.isoCountry.toUpperCase(Locale.US) + ")");
        spinnerCountry.setAdapter(adapter);

        btnRandomize.setOnClickListener(v -> {
            if (service == null) return;
            ConfigStore.randomize(service.getRemotePreferences(ConfigKeys.GROUP));
            loadFromPrefs();
            Toast.makeText(this, "Generated a new random identity.", Toast.LENGTH_SHORT).show();
        });

        btnApply.setOnClickListener(v -> applyChanges());

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

    private void setControlsEnabled(boolean enabled) {
        spinnerCountry.setEnabled(enabled);
        editLat.setEnabled(enabled);
        editLng.setEnabled(enabled);
        editPhone.setEnabled(enabled);
        editScope.setEnabled(enabled);
        btnRandomize.setEnabled(enabled);
        btnApply.setEnabled(enabled);
    }

    private void loadFromPrefs() {
        SharedPreferences sp = service.getRemotePreferences(ConfigKeys.GROUP);
        String iso = sp.getString(ConfigKeys.COUNTRY, "de");
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).isoCountry.equalsIgnoreCase(iso)) {
                spinnerCountry.setSelection(i);
                break;
            }
        }
        editLat.setText(String.valueOf(sp.getFloat(ConfigKeys.LAT, 52.5f)));
        editLng.setText(String.valueOf(sp.getFloat(ConfigKeys.LNG, 13.4f)));
        editPhone.setText(sp.getString(ConfigKeys.PHONE, ""));
        editScope.setText(sp.getString(ConfigKeys.SCOPE, ""));
        txtStatus.setText("Connected.");
    }

    /**
     * Saves the changes to the module's remote preferences (writable from here — PrivacyMask's
     * own process — even though hooked apps only ever get a read-only view of the same data),
     * bumps the config version, and — if root access is available — force-stops every package
     * listed in the "extra filter" field so they restart with the new identity right away.
     * Without root, the config is still saved; the user just needs to manually close and
     * reopen the target apps (or reboot).
     */
    private void applyChanges() {
        if (service == null) return;
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

        try {
            e.putFloat(ConfigKeys.LAT, Float.parseFloat(editLat.getText().toString().trim()));
        } catch (NumberFormatException ignored) {
            e.putFloat(ConfigKeys.LAT, (float) selected.randomLat(new Random()));
        }
        try {
            e.putFloat(ConfigKeys.LNG, Float.parseFloat(editLng.getText().toString().trim()));
        } catch (NumberFormatException ignored) {
            e.putFloat(ConfigKeys.LNG, (float) selected.randomLng(new Random()));
        }

        e.putString(ConfigKeys.PHONE, editPhone.getText().toString().trim());
        e.putString(ConfigKeys.SCOPE, editScope.getText().toString().trim());
        e.apply();
        ConfigStore.bumpVersion(service.getRemotePreferences(ConfigKeys.GROUP));

        String scope = editScope.getText().toString().trim();
        boolean restarted = false;
        if (!scope.isEmpty()) {
            restarted = tryForceStopViaRoot(scope.split(","));
        }

        if (restarted) {
            txtStatus.setText("Saved. Target apps were force-stopped — the new identity applies the next time you open them.");
        } else if (!scope.isEmpty()) {
            txtStatus.setText("Saved, but no root access was found for auto-restart. Please force-stop the target apps manually (Android Settings, or your framework Manager app).");
        } else {
            txtStatus.setText("Saved (filter = every app enabled in the Manager's scope screen). For it to fully apply, close and reopen those apps, or reboot.");
        }
    }

    /** Attempts to run `su -c "am force-stop <pkg>"` for each package in the filter list. */
    private boolean tryForceStopViaRoot(String[] packages) {
        boolean anySuccess = false;
        for (String pkg : packages) {
            String p = pkg.trim();
            if (p.isEmpty()) continue;
            try {
                Process proc = Runtime.getRuntime().exec("su");
                OutputStream os = proc.getOutputStream();
                os.write(("am force-stop " + p + "\n").getBytes());
                os.write("exit\n".getBytes());
                os.flush();
                os.close();
                int code = proc.waitFor();
                if (code == 0) anySuccess = true;
            } catch (Exception ex) {
                // No root / su unavailable — ignored, the status text covers this case.
            }
        }
        return anySuccess;
    }
}
