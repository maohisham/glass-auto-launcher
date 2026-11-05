package com.glass.auto;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
    public float  splitRatio  = 0.70f;     // panel1 fraction of total width
    public float  splitRatio2 = 0.50f;     // panel2 fraction of remaining width (3-panel)
    public boolean swapPanels = false;      // true = map on the right panel, media on the left
    public String panelMode   = "split";    // "split" = Android split screen (default) | "freeform" = positioned windows

    // Media
    public String mediaPackage = "";        // preferred media app package (empty = auto-detect active session)
    public boolean autoStartMedia = true;   // auto-launch media app on launcher start
    public boolean showLyrics = true;

    // Dock
    public List<String> dockApps = new ArrayList<>();
    public boolean dockDefaultsApplied = false; // the default dock list has been filled in once
    public String dockPosition = "bottom";  // "bottom" | "right"
    public boolean dockAutoHide = false;
    public boolean compactDock  = false;    // force the slim dock (otherwise it is used only where no system bar can hold the dock)

    // Speed
    public boolean showSpeed = true;
    public String speedUnit = "kmh";        // "kmh" | "mph"
    public int speedLimitKmh = 0;           // 0 = disabled; alert beeps when exceeded

    // Appearance
    public boolean reducedMotion = false;
    public boolean darkStatusBar = true;
    public float   uiScale       = 0f;   // 0 = auto-scale from screen width; >0 = manual multiplier

    public static void save(Context ctx, Config c) {
        try {
            JSONObject j = new JSONObject();
            j.put("panel_count",    c.panelCount);
            j.put("map_package",    c.mapPackage);
            j.put("panel3_package", c.panel3Package);
            j.put("split_ratio",    c.splitRatio);
            j.put("split_ratio2",   c.splitRatio2);
            j.put("swap_panels",    c.swapPanels);
            j.put("panel_mode",     c.panelMode);
            j.put("media_package",    c.mediaPackage);
            j.put("auto_start_media", c.autoStartMedia);
            j.put("show_lyrics",      c.showLyrics);
            j.put("dock_position",  c.dockPosition);
            j.put("dock_auto_hide", c.dockAutoHide);
            j.put("compact_dock",   c.compactDock);
            j.put("dock_defaults_applied", c.dockDefaultsApplied);
            j.put("show_speed",       c.showSpeed);
            j.put("speed_unit",       c.speedUnit);
            j.put("speed_limit_kmh",  c.speedLimitKmh);
            j.put("reduced_motion", c.reducedMotion);
            j.put("dark_status_bar",c.darkStatusBar);
            j.put("ui_scale",       c.uiScale);
            JSONArray apps = new JSONArray();
            for (String pkg : c.dockApps) apps.put(pkg);
            j.put("dock_apps", apps);
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return;
            try (FileOutputStream out = new FileOutputStream(new File(dir, FILE))) {
                out.write(j.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "config save failed", e);
        }
    }

    public static Config load(Context ctx) {
        Config c = read(ctx);
        applyDockDefaults(ctx, c);
        applyPanelDefaults(ctx, c);
        return c;
    }

    private static Config read(Context ctx) {
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
            c.splitRatio    = (float) j.optDouble("split_ratio", 0.70);
            c.splitRatio2   = (float) j.optDouble("split_ratio2", 0.50);
            c.swapPanels    = j.optBoolean("swap_panels", false);
            c.panelMode     = j.optString("panel_mode", "split");
            c.mediaPackage    = j.optString("media_package", "");
            c.autoStartMedia  = j.optBoolean("auto_start_media", true);
            c.showLyrics      = j.optBoolean("show_lyrics", true);
            c.dockPosition  = j.optString("dock_position", "bottom");
            c.dockAutoHide  = j.optBoolean("dock_auto_hide", false);
            c.compactDock   = j.optBoolean("compact_dock", false);
            c.dockDefaultsApplied = j.optBoolean("dock_defaults_applied", false);
            c.showSpeed      = j.optBoolean("show_speed", true);
            c.speedUnit      = j.optString("speed_unit", "kmh");
            c.speedLimitKmh  = j.optInt("speed_limit_kmh", 0);
            c.reducedMotion = j.optBoolean("reduced_motion", false);
            c.darkStatusBar = j.optBoolean("dark_status_bar", true);
            c.uiScale       = (float) j.optDouble("ui_scale", 0.0);
            JSONArray apps = j.optJSONArray("dock_apps");
            if (apps != null) {
                for (int i = 0; i < apps.length(); i++) c.dockApps.add(apps.getString(i));
            }
        } catch (Exception e) {
            Log.w(TAG, "config read failed", e);
        }
        return c;
    }

    /** First-run dock: these apps, in this order, for whichever are installed. Missing ones are skipped. */
    private static final String[] DEFAULT_DOCK = {
        "com.google.android.apps.maps",
        "app.morphe.android.apps.youtube.music",
        "com.google.android.apps.dynamite",     // Google Chat
        "com.ilv.vradio",
    };

    private static void applyDockDefaults(Context ctx, Config c) {
        if (c.dockDefaultsApplied) return;
        if (!c.dockApps.isEmpty()) { c.dockDefaultsApplied = true; return; }
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        for (String pkg : DEFAULT_DOCK) {
            String found = installed(pm, pkg) ? pkg : null;
            // Morphe builds are patched YT Music apps; match any app.morphe.* YT Music package name.
            if (found == null && pkg.equals("app.morphe.android.apps.youtube.music")) {
                for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    if (ai.packageName.startsWith("app.morphe.") && ai.packageName.contains("youtube.music")) {
                        found = ai.packageName;
                        break;
                    }
                }
            }
            if (found != null) c.dockApps.add(found);
        }
        // Stay unset when nothing was found, so the defaults are retried once the apps are installed.
        if (!c.dockApps.isEmpty()) { c.dockDefaultsApplied = true; save(ctx, c); }
    }

    private static boolean installed(android.content.pm.PackageManager pm, String pkg) {
        try { pm.getApplicationInfo(pkg, 0); return true; }
        catch (android.content.pm.PackageManager.NameNotFoundException e) { return false; }
    }

    /**
     * A fresh install has no map or media app chosen, which leaves split screen nothing to open.
     * Fill in Google Maps and the first installed media app from the dock defaults (kept in memory only).
     */
    private static void applyPanelDefaults(Context ctx, Config c) {
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        if (c.mapPackage.isEmpty() && installed(pm, "com.google.android.apps.maps")) {
            c.mapPackage = "com.google.android.apps.maps";
        }
        if (c.mediaPackage.isEmpty() && "split".equals(c.panelMode)) {
            for (String pkg : c.dockApps) {
                if (!pkg.equals("com.google.android.apps.maps") && !pkg.equals("com.google.android.apps.dynamite")) {
                    c.mediaPackage = pkg;
                    return;
                }
            }
            if (installed(pm, "com.google.android.apps.youtube.music")) {
                c.mediaPackage = "com.google.android.apps.youtube.music";
            }
        }
    }
}
