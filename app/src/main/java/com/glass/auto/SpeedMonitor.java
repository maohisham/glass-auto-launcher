package com.glass.auto;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;

/**
 * Provides GPS-based vehicle speed. Updates every second at ~1m accuracy threshold.
 *
 * For OBD-II speed (more accurate at low speeds), an ELM327 Bluetooth adapter is needed.
 * That integration is a future milestone — GPS speed is the default.
 */
public class SpeedMonitor {

    private static final String TAG = "GlassAuto.Speed";
    private static final long UPDATE_INTERVAL_MS = 1000;
    private static final float MIN_DISTANCE_M     = 0f;

    public interface Listener {
        /** speedKmh is 0 when stationary or GPS unavailable. */
        void onSpeed(float speedKmh);
    }

    private final LocationManager lm;
    private Listener listener;
    private float lastSpeedKmh = 0f;

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location loc) {
            float speedMs = loc.hasSpeed() ? loc.getSpeed() : 0f;
            lastSpeedKmh = speedMs * 3.6f;
            if (listener != null) listener.onSpeed(lastSpeedKmh);
        }

        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) {
            if (listener != null) listener.onSpeed(0f);
        }
        @Override public void onStatusChanged(String p, int s, Bundle e) { }
    };

    public SpeedMonitor(Context ctx) {
        lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
    }

    public void setListener(Listener l) { this.listener = l; }

    /** Start GPS updates. Requires ACCESS_FINE_LOCATION permission. */
    public void start() {
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                //noinspection MissingPermission
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        UPDATE_INTERVAL_MS, MIN_DISTANCE_M, locationListener);
            } else {
                Log.w(TAG, "GPS provider not available");
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Location permission not granted", e);
        }
    }

    public void stop() {
        lm.removeUpdates(locationListener);
    }

    public float getLastSpeedKmh() { return lastSpeedKmh; }
}
