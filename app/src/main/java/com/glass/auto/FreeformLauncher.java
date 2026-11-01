package com.glass.auto;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Launches an app into a freeform window bounded to a specific screen rectangle.
 *
 * Requires freeform window mode to be enabled on the device. Most aftermarket car
 * Android boxes have it on by default. Enable via adb if needed:
 *   adb shell settings put global enable_freeform_support 1
 *   adb shell settings put global force_resizable_activities 1
 *
 * The launcher overlay (rounded-corner mask + dock) is drawn on top by CarActivity.
 */
public class FreeformLauncher {

    private static final String TAG = "GlassAuto.Freeform";

    /** Launches the given package into freeform bounds. Returns true if the launch was attempted. */
    public static boolean launch(Activity host, String packageName, Rect bounds) {
        return launch(host, packageName, bounds, false);
    }

    /** With reset=true the app's existing task is cleared, because Android ignores new bounds for a task that already exists. */
    public static boolean launch(Activity host, String packageName, Rect bounds, boolean reset) {
        if (packageName == null || packageName.isEmpty()) return false;
        try {
            Intent intent = host.getPackageManager()
                    .getLaunchIntentForPackage(packageName);
            if (intent == null) {
                Log.w(TAG, "No launch intent for " + packageName);
                return false;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | (reset ? Intent.FLAG_ACTIVITY_CLEAR_TASK : Intent.FLAG_ACTIVITY_MULTIPLE_TASK));

            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchBounds(bounds);

            host.startActivity(intent, freeformOptions(opts)); // freeform mode is required on Android 12+ to honour the bounds
            Log.d(TAG, "Launched " + packageName + " at " + bounds);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Freeform launch failed for " + packageName, e);
            return false;
        }
    }

    /**
     * Android split screen. {@code adjacent=false} opens the app fullscreen in a fresh task (it is then
     * docked by the accessibility "toggle split screen" action); {@code adjacent=true} opens the app
     * in the other half of an active split.
     */
    public static boolean launchSplit(Activity host, String packageName, boolean adjacent) {
        if (packageName == null || packageName.isEmpty()) return false;
        try {
            Intent intent = host.getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent == null) return false;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | (adjacent ? Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                                : Intent.FLAG_ACTIVITY_CLEAR_TASK));
            host.startActivity(intent);
            Log.d(TAG, "Split launch " + packageName + " adjacent=" + adjacent);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Split launch failed for " + packageName, e);
            return false;
        }
    }

    /** Launches a map app with a geo intent so it opens in navigation mode. */
    public static boolean launchMap(Activity host, String packageName, Rect bounds) {
        return launchMap(host, packageName, bounds, false);
    }

    public static boolean launchMap(Activity host, String packageName, Rect bounds, boolean reset) {
        if (packageName == null || packageName.isEmpty()) return false;
        try {
            // Try geo intent first (opens navigation/map view directly)
            Intent geoIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="));
            geoIntent.setPackage(packageName);
            geoIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | (reset ? Intent.FLAG_ACTIVITY_CLEAR_TASK : Intent.FLAG_ACTIVITY_MULTIPLE_TASK));

            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchBounds(bounds);

            if (geoIntent.resolveActivity(host.getPackageManager()) != null) {
                host.startActivity(geoIntent, freeformOptions(opts));
                return true;
            }
            // Fall back to normal launch intent
            return launch(host, packageName, bounds, reset);
        } catch (Exception e) {
            Log.e(TAG, "Map launch failed for " + packageName, e);
            return false;
        }
    }

    /** Returns all installed apps that handle map/navigation intents. */
    public static List<AppInfo> findMapApps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"));
        List<ResolveInfo> resolved = pm.queryIntentActivities(probe, 0);
        List<AppInfo> result = new ArrayList<>();
        for (ResolveInfo ri : resolved) {
            AppInfo info = new AppInfo();
            info.packageName = ri.activityInfo.packageName;
            info.label = ri.loadLabel(pm).toString();
            info.icon = ri.loadIcon(pm);
            result.add(info);
        }
        return result;
    }

    /** Returns every launchable app installed on the device (for panel 3 picker). */
    public static List<AppInfo> findAllApps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN);
        probe.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(probe, 0);
        List<AppInfo> result = new ArrayList<>();
        for (ResolveInfo ri : resolved) {
            AppInfo info = new AppInfo();
            info.packageName = ri.activityInfo.packageName;
            info.label = ri.loadLabel(pm).toString();
            info.icon  = ri.loadIcon(pm);
            result.add(info);
        }
        result.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return result;
    }

    /** Returns installed media player apps (excludes file tools, terminals, etc.). */
    public static List<AppInfo> findMediaApps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<AppInfo> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // Primary: apps that expose MediaBrowserService — these are real media players
        // (Spotify, YouTube Music, Poweramp, VLC, AIMP, etc.)
        Intent browserProbe = new Intent("android.media.browse.MediaBrowserService");
        for (ResolveInfo ri : pm.queryIntentServices(browserProbe, 0)) {
            String pkg = ri.serviceInfo.packageName;
            if (seen.add(pkg)) {
                AppInfo info = new AppInfo();
                info.packageName = pkg;
                info.label       = ri.loadLabel(pm).toString();
                info.icon        = ri.loadIcon(pm);
                result.add(info);
            }
        }

        // Fallback: audio file viewers that also appear in the app launcher
        // (filters out Termux, file managers, system tools, etc.)
        Set<String> launcherPkgs = new HashSet<>();
        Intent launcherProbe = new Intent(Intent.ACTION_MAIN);
        launcherProbe.addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(launcherProbe, 0)) {
            launcherPkgs.add(ri.activityInfo.packageName);
        }

        Intent audioProbe = new Intent(Intent.ACTION_VIEW);
        audioProbe.setType("audio/*");
        for (ResolveInfo ri : pm.queryIntentActivities(audioProbe, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (seen.add(pkg) && launcherPkgs.contains(pkg)) {
                AppInfo info = new AppInfo();
                info.packageName = pkg;
                info.label       = ri.loadLabel(pm).toString();
                info.icon        = ri.loadIcon(pm);
                result.add(info);
            }
        }

        result.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return result;
    }

    /**
     * Calls ActivityOptions.setLaunchWindowingMode(WINDOWING_MODE_FREEFORM=5) via reflection.
     * This is a @hide API but is present on all Android 8+ devices. Without it, Android 12+
     * ignores setLaunchBounds() and launches the activity as fullscreen instead.
     */
    static Bundle freeformOptions(ActivityOptions opts) {
        try {
            Method m = ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
            m.invoke(opts, 5); // 5 = WindowConfiguration.WINDOWING_MODE_FREEFORM
            return opts.toBundle();
        } catch (Exception e) {
            // Android 16 blocks the method above (HyperOS then opens the app fullscreen). The method
            // only stores this value in the options bundle, so put it there directly.
            Bundle b = opts.toBundle();
            b.putInt("android.activity.windowingMode", 5);
            return b;
        }
    }

    public static class AppInfo {
        public String packageName;
        public String label;
        public android.graphics.drawable.Drawable icon;
    }
}
