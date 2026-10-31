package com.glass.auto;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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
 * Fetches prayer times from Aladhan API (free, no API key, worldwide).
 * Refreshes daily and on significant location change (>5 km via LocationHelper).
 *
 * Method defaults to 3 (Muslim World League). Override via config if needed.
 */
public class PrayerTimesManager implements LocationHelper.Listener {

    private static final String TAG = "GlassAuto.Prayer";
    // Aladhan v1 timings endpoint
    private static final String API =
            "https://api.aladhan.com/v1/timings/%d?latitude=%s&longitude=%s&method=%d";

    // Prayer names in order (display name → API key)
    private static final String[] PRAYER_KEYS  = {"Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"};
    private static final String[] PRAYER_NAMES = {"Subuh", "Zohor", "Asar", "Maghrib", "Isyak"};

    public static class PrayerData {
        /** All five prayer times for today. Key = display name, value = "HH:mm". */
        public Map<String, String> times = new LinkedHashMap<>();
        public String currentPrayer  = "";   // e.g. "Zohor"
        public String currentTime    = "";   // e.g. "12:30"
        public String nextPrayer     = "";   // e.g. "Asar"
        public String nextTime       = "";   // e.g. "15:45"
        public long   nextPrayerMs   = 0;    // epoch ms of next prayer (for countdown)
    }

    public interface Listener {
        void onPrayerTimes(PrayerData data);
    }

    private final ExecutorService executor  = Executors.newSingleThreadExecutor();
    private final Handler         main      = new Handler(Looper.getMainLooper());
    private Listener listener;
    private PrayerData cached;
    private long lastFetchDate = 0; // yyyyMMdd as long
    private int method = 3; // Muslim World League default

    public void setListener(Listener l) {
        this.listener = l;
        if (cached != null) {
            cached = computeCurrentNext(cached);
            main.post(() -> { if (listener != null) listener.onPrayerTimes(cached); });
        }
    }

    /** Override calculation method (Aladhan method id). Default: 3 (Muslim World League). */
    public void setMethod(int m) { method = m; }

    @Override
    public void onLocation(double lat, double lon) {
        long today = todayKey();
        if (cached != null && lastFetchDate == today) {
            // Same day — just recompute current/next from cached times and notify
            cached = computeCurrentNext(cached);
            main.post(() -> { if (listener != null) listener.onPrayerTimes(cached); });
            return;
        }
        fetch(lat, lon, today);
    }

    /** Force a refresh (e.g. on date change at midnight). */
    public void refresh(double lat, double lon) {
        lastFetchDate = 0;
        fetch(lat, lon, todayKey());
    }

    /** Recompute current/next prayer without a network call. Call every minute from CarActivity. */
    public void tick() {
        if (cached == null) return;
        cached = computeCurrentNext(cached);
        main.post(() -> { if (listener != null) listener.onPrayerTimes(cached); });
    }

    private void fetch(double lat, double lon, long todayKey) {
        executor.execute(() -> {
            PrayerData data = fetchSync(lat, lon);
            if (data != null) {
                lastFetchDate = todayKey;
                cached = computeCurrentNext(data);
                main.post(() -> { if (listener != null) listener.onPrayerTimes(cached); });
            }
        });
    }

    private PrayerData fetchSync(double lat, double lon) {
        try {
            long epochSec = System.currentTimeMillis() / 1000;
            String url = String.format(Locale.US, API, epochSec,
                    String.format(Locale.US, "%.4f", lat),
                    String.format(Locale.US, "%.4f", lon),
                    method);
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

            JSONObject root    = new JSONObject(sb.toString());
            JSONObject timings = root.getJSONObject("data").getJSONObject("timings");

            PrayerData d = new PrayerData();
            for (int i = 0; i < PRAYER_KEYS.length; i++) {
                String raw = timings.optString(PRAYER_KEYS[i], "");
                // Aladhan returns "HH:mm (TZ)" — keep only "HH:mm"
                if (raw.contains(" ")) raw = raw.split(" ")[0];
                d.times.put(PRAYER_NAMES[i], raw);
            }
            return d;
        } catch (Exception e) {
            Log.w(TAG, "Prayer fetch failed: " + e.getMessage());
            return null;
        }
    }

    /** Determine which prayer is current and which is next based on the current clock time. */
    private PrayerData computeCurrentNext(PrayerData d) {
        Calendar now = Calendar.getInstance();
        int nowMins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);

        String[] names = d.times.keySet().toArray(new String[0]);
        String[] times = d.times.values().toArray(new String[0]);
        int[] mins = new int[times.length];

        for (int i = 0; i < times.length; i++) {
            mins[i] = parseHHmm(times[i]);
        }

        int currentIdx = -1;
        int nextIdx    = -1;

        for (int i = 0; i < mins.length; i++) {
            if (mins[i] <= nowMins) currentIdx = i;
        }
        if (currentIdx == -1) {
            // Before Fajr — current is Isyak (yesterday), next is Fajr
            currentIdx = mins.length - 1;
            nextIdx    = 0;
        } else if (currentIdx == mins.length - 1) {
            // After Isyak — next is Fajr tomorrow
            nextIdx = 0;
        } else {
            nextIdx = currentIdx + 1;
        }

        d.currentPrayer = names[currentIdx];
        d.currentTime   = times[currentIdx];
        d.nextPrayer    = names[nextIdx];
        d.nextTime      = times[nextIdx];

        // Compute epoch ms for next prayer
        Calendar next = Calendar.getInstance();
        int nextMins = mins[nextIdx];
        next.set(Calendar.HOUR_OF_DAY, nextMins / 60);
        next.set(Calendar.MINUTE, nextMins % 60);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        // If next prayer is Fajr and we're after Isyak, it's tomorrow
        if (nextIdx == 0 && currentIdx == mins.length - 1) {
            next.add(Calendar.DAY_OF_YEAR, 1);
        }
        d.nextPrayerMs = next.getTimeInMillis();
        return d;
    }

    private int parseHHmm(String hhmm) {
        try {
            String[] p = hhmm.split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) { return 0; }
    }

    private long todayKey() {
        return Long.parseLong(new SimpleDateFormat("yyyyMMdd", Locale.US)
                .format(new Date()));
    }
}
