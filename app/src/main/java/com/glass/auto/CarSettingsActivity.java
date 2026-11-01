package com.glass.auto;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Minimal settings screen: select map app, media app, dock apps, speed unit, lyrics toggle. */
public class CarSettingsActivity extends Activity {

    @Override
    protected void onDestroy() {
        super.onDestroy();
        CarActivity car = CarActivity.instance;
        if (car != null) car.onSettingsClosed();
    }

    private Config cfg;
    private List<FreeformLauncher.AppInfo> mapApps;
    private List<FreeformLauncher.AppInfo> mediaApps;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = Config.load(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0A0A0A);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 96);
        scroll.addView(root);
        setContentView(scroll);

        mapApps   = FreeformLauncher.findMapApps(this);
        mediaApps = FreeformLauncher.findMediaApps(this);

        addSection(root, "Panel Layout");
        addSpinner(root,
                new String[]{"1 panel (map fullscreen)", "2 panels (map + media)", "3 panels (map + media + app)"},
                cfg.panelCount - 1,
                idx -> cfg.panelCount = idx + 1);

        addSection(root, "Panel 1 — Map App");
        addAppSpinner(root, mapApps, cfg.mapPackage, pkg -> cfg.mapPackage = pkg);

        addSection(root, "Panel 3 App (3-panel mode only)");
        List<FreeformLauncher.AppInfo> allApps = FreeformLauncher.findAllApps(this);
        addAppSpinner(root, allApps, cfg.panel3Package, pkg -> cfg.panel3Package = pkg);

        addSection(root, "Media App (empty = auto-detect)");
        addAppSpinner(root, mediaApps, cfg.mediaPackage, pkg -> cfg.mediaPackage = pkg);

        addSection(root, "Auto-Start Media on Launch");
        addSwitch(root, cfg.autoStartMedia, on -> cfg.autoStartMedia = on);

        addSection(root, "Speed Unit");
        addSpinner(root,
                new String[]{"km/h", "mph"},
                cfg.speedUnit.equals("mph") ? 1 : 0,
                idx -> cfg.speedUnit = idx == 0 ? "kmh" : "mph");

        addSection(root, "Show Speed");
        addSwitch(root, cfg.showSpeed, on -> cfg.showSpeed = on);

        addSection(root, "Speed Alert Limit");
        int[] limitValues = {0, 60, 70, 80, 90, 100, 110, 120, 130, 140};
        String[] limitLabels = {"Disabled", "60 km/h", "70 km/h", "80 km/h",
                "90 km/h", "100 km/h", "110 km/h", "120 km/h", "130 km/h", "140 km/h"};
        int limitSel = 0;
        for (int i = 1; i < limitValues.length; i++) {
            if (limitValues[i] == cfg.speedLimitKmh) { limitSel = i; break; }
        }
        addSpinner(root, limitLabels, limitSel, idx -> cfg.speedLimitKmh = limitValues[idx]);

        addSection(root, "Show Lyrics");
        addSwitch(root, cfg.showLyrics, on -> cfg.showLyrics = on);

        addSection(root, "Reduced Motion");
        addSwitch(root, cfg.reducedMotion, on -> cfg.reducedMotion = on);

        addSection(root, "UI Scale (dock text & sections)");
        float[] scaleValues = {0f, 0.75f, 1.0f, 1.25f, 1.5f};
        String[] scaleLabels = {"Auto (recommended)", "75%", "100%", "125%", "150%"};
        int scaleSel = 0;
        for (int i = 1; i < scaleValues.length; i++) {
            if (Math.abs(cfg.uiScale - scaleValues[i]) < 0.01f) { scaleSel = i; break; }
        }
        addSpinner(root, scaleLabels, scaleSel, idx -> cfg.uiScale = scaleValues[idx]);

        addSection(root, "Dock Apps (tap to toggle)");
        addDockAppsPicker(root, allApps);

        addSection(root, "Freeform Window Support");
        addFreeformButton(root);

        Button save = new Button(this);
        save.setText("Save & Close");
        save.setOnClickListener(v -> { saveConfig(); finish(); });
        root.addView(save);
    }

    private void addSection(ViewGroup parent, String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xAAFFFFFF);
        tv.setTextSize(12f);
        tv.setPadding(0, 24, 0, 4);
        parent.addView(tv);
    }

    private void addAppSpinner(ViewGroup parent, List<FreeformLauncher.AppInfo> apps,
                               String currentPkg, AppCallback cb) {
        List<String> labels = new ArrayList<>();
        List<String> pkgs   = new ArrayList<>();
        labels.add("None");
        pkgs.add("");
        int sel = 0;
        for (int i = 0; i < apps.size(); i++) {
            labels.add(apps.get(i).label + " (" + apps.get(i).packageName + ")");
            pkgs.add(apps.get(i).packageName);
            if (apps.get(i).packageName.equals(currentPkg)) sel = i + 1;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(adapter);
        spinner.setSelection(sel);
        final List<String> pkgsFinal = pkgs;
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                cb.onSelected(pkgsFinal.get(pos));
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        parent.addView(spinner);
    }

    private void addSpinner(ViewGroup parent, String[] options, int sel, IntCallback cb) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(adapter);
        spinner.setSelection(sel);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { cb.onSelected(pos); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        parent.addView(spinner);
    }

    private void addSwitch(ViewGroup parent, boolean initial, BoolCallback cb) {
        Switch sw = new Switch(this);
        sw.setChecked(initial);
        sw.setOnCheckedChangeListener((v, on) -> cb.onSelected(on));
        parent.addView(sw);
    }

    private void addFreeformButton(ViewGroup parent) {
        int freeform = 0;
        try {
            freeform = android.provider.Settings.Global.getInt(
                    getContentResolver(), "enable_freeform_support", 0);
        } catch (Exception ignored) { }

        TextView status = new TextView(this);
        status.setTextSize(13f);
        status.setPadding(0, 4, 0, 8);
        boolean enabled = freeform == 1;
        status.setText(enabled ? "✅ Freeform windows are ENABLED" : "⚠️ Freeform windows are DISABLED — panels will appear blank");
        status.setTextColor(enabled ? 0xFF80E080 : 0xFFFFAA44);
        parent.addView(status);

        if (!enabled) {
            Button btn = new Button(this);
            btn.setText("Enable Freeform Windows");
            btn.setOnClickListener(v -> {
                try {
                    android.provider.Settings.Global.putInt(
                            getContentResolver(), "enable_freeform_support", 1);
                    android.provider.Settings.Global.putInt(
                            getContentResolver(), "force_resizable_activities", 1);
                    status.setText("✅ Freeform windows ENABLED — please restart the app");
                    status.setTextColor(0xFF80E080);
                    btn.setEnabled(false);
                    android.widget.Toast.makeText(this,
                            "Done! Close and reopen Glass Auto for changes to take effect.",
                            android.widget.Toast.LENGTH_LONG).show();
                } catch (SecurityException e) {
                    android.widget.Toast.makeText(this,
                            "Permission denied. Run via ADB:\nadb shell pm grant com.glass.auto android.permission.WRITE_SECURE_SETTINGS",
                            android.widget.Toast.LENGTH_LONG).show();
                }
            });
            parent.addView(btn);
        }
    }

    private void saveConfig() {
        Config.save(this, cfg);
    }

    private void addDockAppsPicker(ViewGroup parent, List<FreeformLauncher.AppInfo> apps) {
        for (FreeformLauncher.AppInfo app : apps) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 8, 0, 8);

            CheckBox cb = new CheckBox(this);
            cb.setChecked(cfg.dockApps.contains(app.packageName));
            cb.setOnCheckedChangeListener((v, checked) -> {
                if (checked) { if (!cfg.dockApps.contains(app.packageName)) cfg.dockApps.add(app.packageName); }
                else cfg.dockApps.remove(app.packageName);
            });

            ImageView icon = new ImageView(this);
            Drawable d = app.icon;
            if (d != null) icon.setImageDrawable(d);
            int iconSz = (int)(40 * getResources().getDisplayMetrics().density);
            icon.setLayoutParams(new LinearLayout.LayoutParams(iconSz, iconSz));
            icon.setPadding(8, 0, 16, 0);

            TextView label = new TextView(this);
            label.setText(app.label);
            label.setTextColor(0xFFFFFFFF);
            label.setTextSize(14f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            label.setLayoutParams(lp);

            row.addView(cb);
            row.addView(icon);
            row.addView(label);
            row.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
            parent.addView(row);
        }
    }

    interface AppCallback  { void onSelected(String pkg); }
    interface IntCallback  { void onSelected(int index); }
    interface BoolCallback { void onSelected(boolean on); }
}
