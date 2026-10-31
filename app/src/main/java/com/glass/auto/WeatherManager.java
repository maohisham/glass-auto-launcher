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
    private static final String FORECAST_API =
            "https://api.open-meteo.com/v1/forecast" +
            "?latitude=%s&longitude=%s" +
            "&hourly=temperature_2m,weather_code,precipitation_probability" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&forecast_days=7&timezone=auto&timeformat=unixtime";
    private static final String AQI_API =
            "https://air-quality-api.open-meteo.com/v1/air-quality" +
            "?latitude=%s&longitude=%s" +
            "&current=european_aqi,pm2_5,pm10&timezone=auto";

    public static class WeatherData {
        public float   tempC;
        public int     weatherCode;
        public int     humidity;
        public String  emoji;
        public String  description;
        public String  cityName = "";
    }

    public static class HourlyPoint {
        public long  timeEpoch;
        public float tempC;
        public int   weatherCode;
        public int   precipPct;
    }

    public static class DailyPoint {
        public long  timeEpoch;
        public float tempMinC, tempMaxC;
        public int   weatherCode;
        public int   precipPct;
    }

    public static class AqiData {
        public int   europeanAqi;
        public float pm25, pm10;
        public String label() {
            if (europeanAqi <= 20)  return "Good";
            if (europeanAqi <= 40)  return "Fair";
            if (europeanAqi <= 60)  return "Moderate";
            if (europeanAqi <= 80)  return "Poor";
            if (europeanAqi <= 100) return "Very Poor";
            return "Extremely Poor";
        }
    }

    public static class ForecastData {
        public java.util.List<HourlyPoint> hourly = new java.util.ArrayList<>();
        public java.util.List<DailyPoint>  daily  = new java.util.ArrayList<>();
        public AqiData aqi;
    }

    public interface Listener         { void onWeather(WeatherData data); }
    public interface ForecastListener { void onForecast(ForecastData data); }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler         main     = new Handler(Looper.getMainLooper());
    private Context     ctx;
    private Listener    listener;
    private long        lastFetchMs  = 0;
    private String      lastCityName = "";
    private WeatherData cachedData;
    private double      cachedLat = Double.NaN, cachedLon = Double.NaN;

    public void setContext(Context c) { this.ctx = c.getApplicationContext(); }
    public void setListener(Listener l) { this.listener = l; }

    public WeatherData getCachedData() { return cachedData; }

    @Override
    public void onLocation(double lat, double lon) {
        cachedLat = lat;
        cachedLon = lon;
        long now = System.currentTimeMillis();
        if (now - lastFetchMs < MAX_AGE_MS) return;
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
                cachedData    = data;
                main.post(() -> { if (listener != null) listener.onWeather(data); });
            }
        });
    }

    /** Asynchronously fetches 24h hourly + 7-day daily forecast and current AQI. */
    public void fetchForecast(ForecastListener cb) {
        if (Double.isNaN(cachedLat)) { cb.onForecast(new ForecastData()); return; }
        final double lat = cachedLat, lon = cachedLon;
        executor.execute(() -> {
            ForecastData result = new ForecastData();
            String latS = String.format(Locale.US, "%.4f", lat);
            String lonS = String.format(Locale.US, "%.4f", lon);
            try {
                String body = get(String.format(FORECAST_API, latS, lonS));
                if (body != null) parseForecast(body, result);
            } catch (Exception e) { Log.w(TAG, "Forecast fetch error", e); }
            try {
                String body = get(String.format(AQI_API, latS, lonS));
                if (body != null) parseAqi(body, result);
            } catch (Exception e) { Log.w(TAG, "AQI fetch error", e); }
            main.post(() -> cb.onForecast(result));
        });
    }

    private void parseForecast(String body, ForecastData out) throws Exception {
        org.json.JSONObject root = new org.json.JSONObject(body);
        org.json.JSONObject h = root.getJSONObject("hourly");
        org.json.JSONArray hTime  = h.getJSONArray("time");
        org.json.JSONArray hTemp  = h.getJSONArray("temperature_2m");
        org.json.JSONArray hCode  = h.getJSONArray("weather_code");
        org.json.JSONArray hPrec  = h.getJSONArray("precipitation_probability");
        long nowEpoch = System.currentTimeMillis() / 1000;
        int count = 0;
        for (int i = 0; i < hTime.length() && count < 24; i++) {
            long t = hTime.getLong(i);
            if (t < nowEpoch - 1800) continue; // skip past hours
            HourlyPoint p = new HourlyPoint();
            p.timeEpoch  = t;
            p.tempC      = (float) hTemp.getDouble(i);
            p.weatherCode = hCode.getInt(i);
            p.precipPct  = hPrec.isNull(i) ? 0 : hPrec.getInt(i);
            out.hourly.add(p);
            count++;
        }
        org.json.JSONObject d = root.getJSONObject("daily");
        org.json.JSONArray dTime = d.getJSONArray("time");
        org.json.JSONArray dMax  = d.getJSONArray("temperature_2m_max");
        org.json.JSONArray dMin  = d.getJSONArray("temperature_2m_min");
        org.json.JSONArray dCode = d.getJSONArray("weather_code");
        org.json.JSONArray dPrec = d.getJSONArray("precipitation_probability_max");
        for (int i = 0; i < dTime.length(); i++) {
            DailyPoint p = new DailyPoint();
            p.timeEpoch  = dTime.getLong(i);
            p.tempMaxC   = (float) dMax.getDouble(i);
            p.tempMinC   = (float) dMin.getDouble(i);
            p.weatherCode = dCode.getInt(i);
            p.precipPct  = dPrec.isNull(i) ? 0 : dPrec.getInt(i);
            out.daily.add(p);
        }
    }

    private void parseAqi(String body, ForecastData out) throws Exception {
        org.json.JSONObject root    = new org.json.JSONObject(body);
        org.json.JSONObject current = root.getJSONObject("current");
        AqiData aqi = new AqiData();
        aqi.europeanAqi = current.optInt("european_aqi", 0);
        aqi.pm25        = (float) current.optDouble("pm2_5", 0);
        aqi.pm10        = (float) current.optDouble("pm10",  0);
        out.aqi = aqi;
    }

    private String get(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestProperty("User-Agent", "GlassAuto/1.0");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        if (conn.getResponseCode() != 200) { conn.disconnect(); return null; }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        conn.disconnect();
        return sb.toString();
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
