package com.glass.auto;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches current weather from Open-Meteo (free, no API key).
 * Refreshes when location changes >5 km or after 30 minutes.
 */
public class WeatherManager implements LocationHelper.Listener {

    private static final String TAG        = "GlassAuto.Weather";
    private static final long   MAX_AGE_MS = 30 * 60_000L;
    private static final String API        =
            "https://api.open-meteo.com/v1/forecast" +
            "?current=temperature_2m,weather_code,relative_humidity_2m" +
            "&latitude=%s&longitude=%s";

    public static class WeatherData {
        public float   tempC;
        public int     weatherCode;
        public int     humidity;
        public String  emoji;
        public String  description;
        public String  cityName = ""; // reverse-geocoded city / locality
    }

    public interface Listener {
        void onWeather(WeatherData data);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler         main     = new Handler(Looper.getMainLooper());
    private Context  ctx;
    private Listener listener;
    private long   lastFetchMs  = 0;
    private String lastCityName = "";

    public void setContext(Context c) { this.ctx = c.getApplicationContext(); }
    public void setListener(Listener l) { this.listener = l; }

    @Override
    public void onLocation(double lat, double lon) {
        long now = System.currentTimeMillis();
        if (now - lastFetchMs < MAX_AGE_MS) return; // still fresh
        fetch(lat, lon);
    }

    /** Force a refresh regardless of cache age. */
    public void refresh(double lat, double lon) {
        lastFetchMs = 0;
        fetch(lat, lon);
    }

    private void fetch(double lat, double lon) {
        executor.execute(() -> {
            WeatherData data = fetchSync(lat, lon);
            if (data != null) {
                data.cityName = reverseGeocode(lat, lon);
                lastCityName  = data.cityName;
                lastFetchMs   = System.currentTimeMillis();
                main.post(() -> { if (listener != null) listener.onWeather(data); });
            }
        });
    }

    private WeatherData fetchSync(double lat, double lon) {
        try {
            String url = String.format(API,
                    String.format("%.4f", lat), String.format("%.4f", lon));
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestProperty("User-Agent", "GlassAuto/1.0");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            if (conn.getResponseCode() != 200) return null;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            conn.disconnect();

            JSONObject j       = new JSONObject(sb.toString());
            JSONObject current = j.getJSONObject("current");
            WeatherData d = new WeatherData();
            d.tempC      = (float) current.getDouble("temperature_2m");
            d.weatherCode = current.getInt("weather_code");
            d.humidity   = current.optInt("relative_humidity_2m", 0);
            d.emoji       = emojiFor(d.weatherCode);
            d.description = descFor(d.weatherCode);
            return d;
        } catch (Exception e) {
            Log.w(TAG, "Weather fetch failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Reverse geocode lat/lon to a short city name.
     * 1. Android Geocoder (fast, offline on most devices).
     * 2. Nominatim OSM fallback if Geocoder unavailable.
     */
    private String reverseGeocode(double lat, double lon) {
        // 1. Try Android Geocoder
        if (ctx != null && Geocoder.isPresent()) {
            try {
                Geocoder gc = new Geocoder(ctx, Locale.getDefault());
                List<Address> results = gc.getFromLocation(lat, lon, 1);
                if (results != null && !results.isEmpty()) {
                    Address a = results.get(0);
                    // Prefer subLocality (neighbourhood) → locality (city) → subAdminArea (district)
                    String name = a.getSubLocality();
                    if (name == null || name.isEmpty()) name = a.getLocality();
                    if (name == null || name.isEmpty()) name = a.getSubAdminArea();
                    if (name != null && !name.isEmpty()) return name;
                }
            } catch (Exception e) {
                Log.w(TAG, "Geocoder failed: " + e.getMessage());
            }
        }

        // 2. Nominatim fallback
        try {
            String url = String.format(Locale.US,
                    "https://nominatim.openstreetmap.org/reverse?lat=%.5f&lon=%.5f&format=json&zoom=10",
                    lat, lon);
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestProperty("User-Agent", "GlassAuto/1.0");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (conn.getResponseCode() == 200) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                conn.disconnect();
                JSONObject addr = new JSONObject(sb.toString()).optJSONObject("address");
                if (addr != null) {
                    // OSM address keys in priority order
                    for (String key : new String[]{"city_district","suburb","city","town","village","county"}) {
                        String v = addr.optString(key, "");
                        if (!v.isEmpty()) return v;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Nominatim failed: " + e.getMessage());
        }

        return "";
    }

    // WMO weather interpretation codes → emoji + label
    private static String emojiFor(int code) {
        if (code == 0)                        return "☀";
        if (code <= 2)                        return "🌤";
        if (code == 3)                        return "☁";
        if (code <= 48)                       return "🌫";
        if (code <= 55)                       return "🌦";
        if (code <= 65)                       return "🌧";
        if (code <= 67)                       return "🌨";
        if (code <= 77)                       return "❄";
        if (code <= 82)                       return "🌦";
        if (code <= 86)                       return "🌨";
        if (code <= 99)                       return "⛈";
        return "🌡";
    }

    private static String descFor(int code) {
        if (code == 0)  return "Clear";
        if (code <= 2)  return "Partly cloudy";
        if (code == 3)  return "Overcast";
        if (code <= 48) return "Foggy";
        if (code <= 55) return "Drizzle";
        if (code <= 65) return "Rain";
        if (code <= 67) return "Freezing rain";
        if (code <= 77) return "Snow";
        if (code <= 82) return "Showers";
        if (code <= 86) return "Snow showers";
        if (code <= 99) return "Thunderstorm";
        return "";
    }
}
