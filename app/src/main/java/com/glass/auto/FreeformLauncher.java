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
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

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
        if (packageName == null || packageName.isEmpty()) return false;
        try {
            Intent intent = host.getPackageManager()
                    .getLaunchIntentForPackage(packageName);
            if (intent == null) {
                Log.w(TAG, "No launch intent for " + packageName);
                return false;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);

            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchBounds(bounds);

            host.startActivity(intent, opts.toBundle());
            Log.d(TAG, "Launched " + packageName + " at " + bounds);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Freeform launch failed for " + packageName, e);
            return false;
        }
    }

    /** Launches a map app with a geo intent so it opens in navigation mode. */
    public static boolean launchMap(Activity host, String packageName, Rect bounds) {
        if (packageName == null || packageName.isEmpty()) return false;
        try {
            // Try geo intent first (opens navigation/map view directly)
            Intent geoIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="));
            geoIntent.setPackage(packageName);
            geoIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);

            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchBounds(bounds);

            if (geoIntent.resolveActivity(host.getPackageManager()) != null) {
                host.startActivity(geoIntent, opts.toBundle());
                return true;
            }
            // Fall back to normal launch intent
            return launch(host, packageName, bounds);
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

    /** Returns all installed apps that handle media playback intents. */
    public static List<AppInfo> findMediaApps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        // Apps that can play music typically handle this intent
        Intent probe = new Intent(Intent.ACTION_VIEW);
        probe.setType("audio/*");
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

    public static class AppInfo {
        public String packageName;
        public String label;
        public android.graphics.drawable.Drawable icon;
    }
}
