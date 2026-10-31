package com.glass.auto;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * Checks and requests every permission Glass Auto needs.
 *
 * Freeform window mode (enable_freeform_support) requires WRITE_SECURE_SETTINGS
 * which cannot be granted at runtime — if it is missing the setup screen shows
 * the one-liner adb command. On most aftermarket car boxes it is already on.
 */
public class PermissionHelper {

    /** True if the app can draw overlay windows (needed for corner mask over map). */
    public static boolean hasOverlay(Activity ctx) {
        return Settings.canDrawOverlays(ctx);
    }

    /** Opens the system page where the user grants overlay permission. */
    public static void requestOverlay(Activity ctx, int requestCode) {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + ctx.getPackageName()));
        ctx.startActivityForResult(i, requestCode);
    }

    /** True if Glass Auto's MediaListenerService is enabled as a notification listener. */
    public static boolean hasNotificationListener(Activity ctx) {
        String flat = Settings.Secure.getString(
                ctx.getContentResolver(), "enabled_notification_listeners");
        if (TextUtils.isEmpty(flat)) return false;
        ComponentName target = new ComponentName(ctx, MediaListenerService.class);
        for (String entry : flat.split(":")) {
            if (ComponentName.unflattenFromString(entry) != null &&
                    ComponentName.unflattenFromString(entry).equals(target)) return true;
        }
        return false;
    }

    /** Opens the Notification Listener settings page. */
    public static void requestNotificationListener(Activity ctx) {
        ctx.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    /** True if ACCESS_FINE_LOCATION is granted. */
    public static boolean hasLocation(Activity ctx) {
        return ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Requests location permission via the standard runtime dialog. */
    public static void requestLocation(Activity ctx, int requestCode) {
        ctx.requestPermissions(
                new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},
                requestCode);
    }

    /**
     * Checks whether freeform window mode is enabled.
     * Returns false if it cannot be determined (treated as "may be on").
     */
    public static boolean isFreeformEnabled(Activity ctx) {
        try {
            int val = Settings.Global.getInt(ctx.getContentResolver(),
                    "enable_freeform_support", 0);
            return val == 1;
        } catch (Exception e) {
            return true; // Assume on if we can't read it
        }
    }

    /** All runtime-grantable permissions are satisfied. */
    public static boolean allGranted(Activity ctx) {
        return hasOverlay(ctx) && hasNotificationListener(ctx) && hasLocation(ctx);
    }
}
