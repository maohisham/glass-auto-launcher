package com.glass.auto;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches prayer times from the official JAKIM e-Solat API (www.e-solat.gov.my).
 *
 * Strategy:
 *   1. Detect nearest JAKIM zone from GPS coordinates (JakimZoneLookup).
 *   2. Fetch from JAKIM e-Solat primary endpoint.
 *   3. Fall back to waktusolat.app (mirrors JAKIM data, more reliable uptime).
 *   4. Fall back to Aladhan API with method=17 (JAKIM calculation method).
 *
 * Prayer names follow JAKIM's official Malay terminology.
 * Refreshes on location change >5 km or daily at midnight.
 * Tick every minute to update current/next prayer label.
 */
public class PrayerTimesManager implements LocationHelper.Listener {

    private static final String TAG = "GlassAuto.Prayer";

    // JAKIM e-Solat official API — period=today returns today's single entry
    private static final String ESOLAT_API =
            "https://www.e-solat.gov.my/index.php?r=esolatApi/takwimsolat&period=today&zone=%s";

    // waktusolat.app mirrors JAKIM data; returns full month, we pick today's day
    private static final String WAKTU_API =
            "https://api.waktusolat.app/v2/solat/%s";

    // Aladhan fallback — method 17 = JAKIM
    private static final String ALADHAN_API =
            "https://api.aladhan.com/v1/timings/%d?latitude=%s&longitude=%s&method=17";

    // Display names matching JAKIM's official Malay terminology
    private static final String[] PRAYER_DISPLAY = {"Subuh", "Zohor", "Asar", "Maghrib", "Isyak"};

    private static final String[] HIJRI_MONTHS = {
        "Muharram", "Safar", "Rabi'ul Awwal", "Rabi'ul Akhir",
        "Jamadil Awwal", "Jamadil Akhir", "Rajab", "Sha'ban",
        "Ramadan", "Syawal", "Zulkaedah", "Zulhijjah"
    };

    public static class PrayerData {
        public Map<String, String> times  = new LinkedHashMap<>();
        public String syurukTime          = ""; // sunrise — not tracked for current/next
        public String currentPrayer       = "";
        public String currentTime         = "";
        public String nextPrayer          = "";
        public String nextTime            = "";
        public long   nextPrayerMs        = 0;
        public String zoneName            = "";
        public String zoneCode            = "";
        public String hijriDate           = ""; // e.g. "10 Rabi'ul Akhir 1446"
    }

    public interface Listener {
        void onPrayerTimes(PrayerData data);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler         main     = new Handler(Looper.getMainLooper());

    private Listener   listener;
    private PrayerData cached;
    private long lastFetchDateKey = 0; // yyyyMMdd
    private double lastLat = Double.NaN, lastLon = Double.NaN;

    public void setListener(Listener l) {
        this.listener = l;
        if (cached != null) {
            cached = computeCurrentNext(cached);
            main.post(() -> { if (listener != null) listener.onPrayerTimes(cached); });
        }
    }

    /** Returns the last successfully fetched data, or null if not yet available. */
    public PrayerData getCached() { return cached; }

    @Override
    public void onLocation(double lat, double lon) {
        long today = todayKey();
        JakimZoneLookup.Zone zone = JakimZoneLookup.nearest(lat, lon);
        boolean sameDay  = lastFetchDateKey == today;
        boolean sameZone = zone.code.equals(cached != null ? cached.zoneCode : "");

        if (cached != null && sameDay && sameZone) {
            // Same day, same zone — just recompute current/next
            cached = computeCurrentNext(cached);
            main.post(() -> { if (listener != null) listener.onPrayerTimes(cached); });
            return;
        }

        lastLat = lat;
        lastLon = lon;
        fetch(lat, lon, zone, today);
    }

    /** Call once per minute from CarActivity to update the current/next label. */
    public void tick() {
        if (cached == null) return;
        cached = computeCurrentNext(cached);
        main.post(() -> { if (listener != null) listener.onPrayerTimes(cached); });
    }

    private void fetch(double lat, double lon, JakimZoneLookup.Zone zone, long dateKey) {
        executor.execute(() -> {
            PrayerData data = null;

            // 1. Try official JAKIM e-Solat
            data = fetchEsolat(zone);
            if (data == null) {
                Log.w(TAG, "e-Solat failed, trying waktusolat.app");
                // 2. Try waktusolat.app mirror
                data = fetchWaktuSolat(zone);
            }
            if (data == null) {
                Log.w(TAG, "waktusolat.app failed, falling back to Aladhan method=17");
                // 3. Aladhan with JAKIM method
                data = fetchAladhan(lat, lon);
            }

            if (data == null) {
                Log.e(TAG, "All prayer time sources failed");
                return;
            }

            data.zoneCode = zone.code;
            data.zoneName = zone.name;
            lastFetchDateKey = dateKey;
            cached = computeCurrentNext(data);

            final PrayerData finalData = cached;
            main.post(() -> { if (listener != null) listener.onPrayerTimes(finalData); });
        });
    }

    // -----------------------------------------------------------------------
    // Source 1: official JAKIM e-Solat API

    private PrayerData fetchEsolat(JakimZoneLookup.Zone zone) {
        try {
            String url = String.format(ESOLAT_API, zone.code);
            String body = get(url);
            if (body == null) return null;

            JSONObject root = new JSONObject(body);
            // Response: { "status": "OK!", "prayerTime": [ { "hijri":..., "date":"01-Nov-2026",
            //   "day":"Saturday", "imsak":"05:37", "fajr":"05:47", "syuruk":"07:02",
            //   "dhuhr":"13:08", "asr":"16:28", "sunset":"19:12", "maghrib":"19:12",
            //   "isha":"20:24", "midnight":"01:07" } ] }
            String status = root.optString("status", "");
            if (!status.contains("OK")) return null;

            JSONArray arr = root.getJSONArray("prayerTime");
            if (arr.length() == 0) return null;
            JSONObject t = arr.getJSONObject(0);

            PrayerData d = new PrayerData();
            d.times.put("Subuh",   stripTz(t.optString("fajr",    "")));
            d.times.put("Zohor",   stripTz(t.optString("dhuhr",   "")));
            d.times.put("Asar",    stripTz(t.optString("asr",     "")));
            d.times.put("Maghrib", stripTz(t.optString("maghrib", "")));
            d.times.put("Isyak",   stripTz(t.optString("isha",    "")));
            d.syurukTime = stripTz(t.optString("syuruk", ""));
            String rawHijri = t.optString("hijri", "");
            if (!rawHijri.isEmpty()) d.hijriDate = parseHijri(rawHijri);
            return validate(d) ? d : null;
        } catch (Exception e) {
            Log.w(TAG, "e-Solat parse failed: " + e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Source 2: waktusolat.app (JAKIM mirror, higher uptime)

    private PrayerData fetchWaktuSolat(JakimZoneLookup.Zone zone) {
        try {
            String url = String.format(WAKTU_API, zone.code);
            String body = get(url);
            if (body == null) return null;

            JSONObject root = new JSONObject(body);
            // Response: { "zone":"WLY01", "prayer_time": [ { "day":1, "subuh":"05:45",
            //   "syuruk":"07:01", "zohor":"13:07", "asar":"16:28",
            //   "maghrib":"19:11", "isyak":"20:24" }, ... ] }
            JSONArray arr = root.optJSONArray("prayer_time");
            if (arr == null || arr.length() == 0) return null;

            // Find today's entry by day-of-month
            int dayOfMonth = Calendar.getInstance().get(Calendar.DAY_OF_MONTH);
            JSONObject t = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject entry = arr.getJSONObject(i);
                if (entry.optInt("day", -1) == dayOfMonth) { t = entry; break; }
            }
            if (t == null) t = arr.getJSONObject(0); // fallback to first entry

            PrayerData d = new PrayerData();
            d.times.put("Subuh",   stripTz(t.optString("subuh",   "")));
            d.times.put("Zohor",   stripTz(t.optString("zohor",   "")));
            d.times.put("Asar",    stripTz(t.optString("asar",    "")));
            d.times.put("Maghrib", stripTz(t.optString("maghrib", "")));
            d.times.put("Isyak",   stripTz(t.optString("isyak",   "")));
            d.syurukTime = stripTz(t.optString("syuruk", ""));
            return validate(d) ? d : null;
        } catch (Exception e) {
            Log.w(TAG, "waktusolat parse failed: " + e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Source 3: Aladhan with method=17 (JAKIM algorithm)

    private PrayerData fetchAladhan(double lat, double lon) {
        try {
            long epochSec = System.currentTimeMillis() / 1000;
            String url = String.format(Locale.US, ALADHAN_API, epochSec,
                    String.format(Locale.US, "%.4f", lat),
                    String.format(Locale.US, "%.4f", lon));
            String body = get(url);
            if (body == null) return null;

            JSONObject timings = new JSONObject(body)
                    .getJSONObject("data").getJSONObject("timings");

            PrayerData d = new PrayerData();
            d.times.put("Subuh",   stripTz(timings.optString("Fajr",    "")));
            d.times.put("Zohor",   stripTz(timings.optString("Dhuhr",   "")));
            d.times.put("Asar",    stripTz(timings.optString("Asr",     "")));
            d.times.put("Maghrib", stripTz(timings.optString("Maghrib", "")));
            d.times.put("Isyak",   stripTz(timings.optString("Isha",    "")));
            return validate(d) ? d : null;
        } catch (Exception e) {
            Log.w(TAG, "Aladhan fallback failed: " + e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Helpers

    private String get(String urlStr) {
        try {
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
        } catch (Exception e) {
            Log.w(TAG, "HTTP GET failed: " + urlStr + " — " + e.getMessage());
            return null;
        }
    }

    /** Strip timezone suffix e.g. "13:07 +08" → "13:07" */
    private String stripTz(String s) {
        if (s == null) return "";
        int space = s.indexOf(' ');
        return space > 0 ? s.substring(0, space) : s;
    }

    /** Return false if any prayer time is empty/invalid. */
    private boolean validate(PrayerData d) {
        for (String t : d.times.values()) {
            if (t == null || t.isEmpty() || !t.contains(":")) return false;
        }
        return true;
    }

    private PrayerData computeCurrentNext(PrayerData d) {
        Calendar now = Calendar.getInstance();
        int nowMins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);

        String[] names = d.times.keySet().toArray(new String[0]);
        String[] times = d.times.values().toArray(new String[0]);
        int[]    mins  = new int[times.length];
        for (int i = 0; i < times.length; i++) mins[i] = parseHHmm(times[i]);

        int currentIdx = -1, nextIdx;
        for (int i = 0; i < mins.length; i++) if (mins[i] <= nowMins) currentIdx = i;

        if (currentIdx == -1) {
            currentIdx = mins.length - 1; // before Subuh — current = Isyak (prev day)
            nextIdx = 0;
        } else if (currentIdx == mins.length - 1) {
            nextIdx = 0; // after Isyak — next = Subuh tomorrow
        } else {
            nextIdx = currentIdx + 1;
        }

        d.currentPrayer = names[currentIdx];
        d.currentTime   = times[currentIdx];
        d.nextPrayer    = names[nextIdx];
        d.nextTime      = times[nextIdx];

        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, mins[nextIdx] / 60);
        next.set(Calendar.MINUTE,      mins[nextIdx] % 60);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (nextIdx == 0 && currentIdx == mins.length - 1)
            next.add(Calendar.DAY_OF_YEAR, 1);
        d.nextPrayerMs = next.getTimeInMillis();

        // Compute Hijri date from ICU if not supplied by the API
        if (d.hijriDate.isEmpty()) {
            d.hijriDate = computeHijriDate();
        }
        return d;
    }

    private static String computeHijriDate() {
        try {
            android.icu.util.IslamicCalendar ic = new android.icu.util.IslamicCalendar();
            int day   = ic.get(android.icu.util.Calendar.DAY_OF_MONTH);
            int month = ic.get(android.icu.util.Calendar.MONTH); // 0-indexed
            int year  = ic.get(android.icu.util.Calendar.YEAR);
            return day + " " + HIJRI_MONTHS[month] + " " + year;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Handles two formats from the JAKIM API:
     *   DD-MonthName-YYYY  e.g. "20-Jamadil Awwal-1448"
     *   YYYY-MM-DD         e.g. "1448-05-20" (month is 1-indexed)
     */
    private String parseHijri(String raw) {
        raw = raw.trim();
        int f = raw.indexOf('-');
        int l = raw.lastIndexOf('-');
        if (f <= 0 || l <= f) return raw.replace("-", " ").trim();
        String p1 = raw.substring(0, f).trim();
        String p2 = raw.substring(f + 1, l).trim();
        String p3 = raw.substring(l + 1).trim();
        try {
            if (p1.length() == 4) {
                // YYYY-MM-DD: p1=year, p2=month(1-indexed), p3=day
                int yr = Integer.parseInt(p1);
                int mo = Integer.parseInt(p2);
                int dy = Integer.parseInt(p3);
                if (mo >= 1 && mo <= 12) return dy + " " + HIJRI_MONTHS[mo - 1] + " " + yr;
            } else {
                // DD-MonthName-YYYY or DD-MM-YYYY
                int dy = Integer.parseInt(p1);
                try {
                    int mo = Integer.parseInt(p2);
                    int yr = Integer.parseInt(p3);
                    if (mo >= 1 && mo <= 12) return dy + " " + HIJRI_MONTHS[mo - 1] + " " + yr;
                } catch (NumberFormatException ignore) {
                    return dy + " " + p2 + " " + p3;
                }
            }
        } catch (NumberFormatException ignore) { }
        return raw.replace("-", " ").trim();
    }

    private int parseHHmm(String hhmm) {
        try { String[] p = hhmm.split(":"); return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]); }
        catch (Exception e) { return 0; }
    }

    private long todayKey() {
        return Long.parseLong(new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()));
    }
}
