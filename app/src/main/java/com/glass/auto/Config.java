package com.glass.auto;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Reads config.json from the app's external files directory. All fields have safe defaults. */
public class Config {

    private static final String TAG = "GlassAuto.Config";
    private static final String FILE = "config.json";

    // Panel layout
    public int    panelCount  = 2;          // 1, 2, or 3
    public String mapPackage  = "";         // package name of preferred map app (panel 1)
    public String panel3Package = "";       // package name for panel 3 (3-panel mode)
    public float  splitRatio  = 0.55f;     // panel1 fraction of total width
    public float  splitRatio2 = 0.50f;     // panel2 fraction of remaining width (3-panel)

    // Media
    public String mediaPackage = "";        // preferred media app package (empty = auto-detect active session)
    public boolean showLyrics = true;

    // Dock
    public List<String> dockApps = new ArrayList<>();
    public String dockPosition = "bottom";  // "bottom" | "right"
    public boolean dockAutoHide = false;

    // Speed
    public boolean showSpeed = true;
    public String speedUnit = "kmh";        // "kmh" | "mph"

    // Appearance
    public boolean reducedMotion = false;
    public boolean darkStatusBar = true;

    public static Config load(Context ctx) {
        Config c = new Config();
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return c;
            File f = new File(dir, FILE);
            if (!f.exists()) return c;
            byte[] bytes = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                //noinspection ResultOfMethodCallIgnored
                in.read(bytes);
            }
            JSONObject j = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            c.panelCount    = j.optInt("panel_count", 2);
            c.mapPackage    = j.optString("map_package", "");
            c.panel3Package = j.optString("panel3_package", "");
            c.splitRatio    = (float) j.optDouble("split_ratio", 0.55);
            c.splitRatio2   = (float) j.optDouble("split_ratio2", 0.50);
            c.mediaPackage  = j.optString("media_package", "");
            c.showLyrics    = j.optBoolean("show_lyrics", true);
            c.dockPosition  = j.optString("dock_position", "bottom");
            c.dockAutoHide  = j.optBoolean("dock_auto_hide", false);
            c.showSpeed     = j.optBoolean("show_speed", true);
            c.speedUnit     = j.optString("speed_unit", "kmh");
            c.reducedMotion = j.optBoolean("reduced_motion", false);
            c.darkStatusBar = j.optBoolean("dark_status_bar", true);
            JSONArray apps = j.optJSONArray("dock_apps");
            if (apps != null) {
                for (int i = 0; i < apps.length(); i++) c.dockApps.add(apps.getString(i));
            }
        } catch (Exception e) {
            Log.w(TAG, "config read failed", e);
        }
        return c;
    }
}
