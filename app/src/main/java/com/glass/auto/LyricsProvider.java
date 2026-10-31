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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches time-synced lyrics from LRCLib (https://lrclib.net) — free, no API key required.
 *
 * Returns a list of LyricLine with timestamp + text. The caller polls getPositionMs()
 * from MediaSessionController every 500ms and highlights the current line.
 */
public class LyricsProvider {

    private static final String TAG     = "GlassAuto.Lyrics";
    private static final String API_URL = "https://lrclib.net/api/get";

    public static class LyricLine {
        public final long   timeMs;
        public final String text;
        LyricLine(long timeMs, String text) { this.timeMs = timeMs; this.text = text; }
    }

    public interface Callback {
        /** Called on the main thread. lines is empty if no lyrics found. */
        void onLyrics(List<LyricLine> lines);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private String lastTrack  = "";
    private String lastArtist = "";

    /** Fetch lyrics for the given track/artist. Only fetches if track changed. */
    public void fetch(String trackTitle, String artist, Callback callback) {
        if (trackTitle == null || trackTitle.isEmpty()) {
            callback.onLyrics(new ArrayList<>());
            return;
        }
        String key = trackTitle + "|" + artist;
        if (key.equals(lastTrack + "|" + lastArtist)) return; // already loaded
        lastTrack  = trackTitle;
        lastArtist = artist != null ? artist : "";

        executor.execute(() -> {
            List<LyricLine> lines = fetchSync(trackTitle, lastArtist);
            main.post(() -> callback.onLyrics(lines));
        });
    }

    private List<LyricLine> fetchSync(String title, String artist) {
        try {
            String query = API_URL
                    + "?track_name=" + URLEncoder.encode(title,  "UTF-8")
                    + "&artist_name=" + URLEncoder.encode(artist, "UTF-8");
            URL url = new URL(query);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "GlassAuto/1.0");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() != 200) return new ArrayList<>();

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            conn.disconnect();

            JSONObject json = new JSONObject(sb.toString());
            String syncedLyrics = json.optString("syncedLyrics", "");
            if (!syncedLyrics.isEmpty()) return parseLrc(syncedLyrics);

            // Fall back to plain lyrics (no timestamps)
            String plain = json.optString("plainLyrics", "");
            if (!plain.isEmpty()) return parsePlain(plain);

        } catch (Exception e) {
            Log.w(TAG, "Lyrics fetch failed: " + e.getMessage());
        }
        return new ArrayList<>();
    }

    /** Parses LRC format: [mm:ss.xx] lyric text */
    private List<LyricLine> parseLrc(String lrc) {
        List<LyricLine> lines = new ArrayList<>();
        for (String raw : lrc.split("\n")) {
            raw = raw.trim();
            if (!raw.startsWith("[")) continue;
            int close = raw.indexOf(']');
            if (close < 0) continue;
            String ts = raw.substring(1, close);
            String text = raw.substring(close + 1).trim();
            try {
                String[] parts = ts.split("[:.]");
                long ms = Long.parseLong(parts[0]) * 60_000L
                        + Long.parseLong(parts[1]) * 1_000L
                        + (parts.length > 2 ? Long.parseLong(parts[2]) * 10L : 0);
                lines.add(new LyricLine(ms, text));
            } catch (NumberFormatException ignored) { }
        }
        return lines;
    }

    /** Wraps plain text into LyricLine list with no timestamps (all at 0ms). */
    private List<LyricLine> parsePlain(String plain) {
        List<LyricLine> lines = new ArrayList<>();
        for (String line : plain.split("\n")) {
            if (!line.trim().isEmpty()) lines.add(new LyricLine(0, line.trim()));
        }
        return lines;
    }

    /** Returns the index of the current lyric line given playback position. */
    public static int currentLine(List<LyricLine> lines, long posMs) {
        if (lines.isEmpty()) return -1;
        int idx = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).timeMs <= posMs) idx = i; else break;
        }
        return idx;
    }

    public void cancel() {
        lastTrack = "";
        lastArtist = "";
    }
}
