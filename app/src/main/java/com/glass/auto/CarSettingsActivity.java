package com.glass.auto;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal settings screen: select map app, media app, dock apps, speed unit, lyrics toggle. */
public class CarSettingsActivity extends Activity {

    private Config cfg;
    private List<FreeformLauncher.AppInfo> mapApps;
    private List<FreeformLauncher.AppInfo> mediaApps;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = Config.load(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);
        root.setBackgroundColor(0xFF0A0A0A);
        setContentView(root);

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

        addSection(root, "Speed Unit");
        addSpinner(root,
                new String[]{"km/h", "mph"},
                cfg.speedUnit.equals("mph") ? 1 : 0,
                idx -> cfg.speedUnit = idx == 0 ? "kmh" : "mph");

        addSection(root, "Show Speed");
        addSwitch(root, cfg.showSpeed, on -> cfg.showSpeed = on);

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

    private void saveConfig() {
        try {
            JSONObject j = new JSONObject();
            j.put("panel_count",    cfg.panelCount);
            j.put("map_package",    cfg.mapPackage);
            j.put("panel3_package", cfg.panel3Package);
            j.put("split_ratio",    cfg.splitRatio);
            j.put("split_ratio2",   cfg.splitRatio2);
            j.put("media_package",  cfg.mediaPackage);
            j.put("show_lyrics",    cfg.showLyrics);
            j.put("show_speed",     cfg.showSpeed);
            j.put("speed_unit",     cfg.speedUnit);
            j.put("dock_position",  cfg.dockPosition);
            j.put("dock_auto_hide", cfg.dockAutoHide);
            j.put("reduced_motion", cfg.reducedMotion);
            j.put("ui_scale",       cfg.uiScale);
            JSONArray apps = new JSONArray();
            for (String pkg : cfg.dockApps) apps.put(pkg);
            j.put("dock_apps", apps);

            File dir = getExternalFilesDir(null);
            if (dir == null) return;
            File f = new File(dir, "config.json");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(j.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    interface AppCallback  { void onSelected(String pkg); }
    interface IntCallback  { void onSelected(int index); }
    interface BoolCallback { void onSelected(boolean on); }
}
