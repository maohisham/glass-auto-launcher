package com.glass.auto;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared location provider used by WeatherManager, PrayerTimesManager, and SpeedMonitor.
 * Notifies listeners on significant location change (>5 km) to avoid unnecessary API calls.
 */
public class LocationHelper {

    private static final String TAG         = "GlassAuto.Location";
    private static final float  REFRESH_KM  = 5f;     // minimum distance to trigger a refresh
    private static final long   INTERVAL_MS = 30_000L; // GPS poll interval

    public interface Listener {
        void onLocation(double lat, double lon);
    }

    private final LocationManager lm;
    private final List<Listener>  listeners = new ArrayList<>();
    private Location lastLocation;

    private final LocationListener gpsListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location loc) {
            if (lastLocation == null || loc.distanceTo(lastLocation) > REFRESH_KM * 1000f) {
                lastLocation = loc;
                notifyAll(loc.getLatitude(), loc.getLongitude());
            }
        }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
        @Override public void onStatusChanged(String p, int s, Bundle e) { }
    };

    public LocationHelper(Context ctx) {
        lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
    }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    /** Start GPS. Fires existing last-known location immediately if available. */
    public void start() {
        try {
            //noinspection MissingPermission
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    INTERVAL_MS, REFRESH_KM * 1000f, gpsListener);
            // Fire last-known immediately so callers don't wait for first GPS fix
            //noinspection MissingPermission
            Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null)
                //noinspection MissingPermission
                last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last != null) {
                lastLocation = last;
                notifyAll(last.getLatitude(), last.getLongitude());
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Location permission missing", e);
        }
    }

    public void stop() {
        lm.removeUpdates(gpsListener);
    }

    public Location getLastLocation() { return lastLocation; }

    private void notifyAll(double lat, double lon) {
        for (Listener l : listeners) l.onLocation(lat, lon);
    }
}
