package com.glass.auto;

import android.os.Handler;
import android.os.Looper;

/**
 * Tracks driving behaviour and computes a fuel-efficiency eco score (0–100).
 *
 * Score components:
 *   35% — Optimal speed zone adherence (60–90 km/h sweet spot)
 *   25% — Speed smoothness (low std deviation = no harsh accel/brake)
 *   20% — Average speed efficiency (distance/duration vs optimal 70 km/h)
 *   15% — Idle penalty (time sitting still with engine on)
 *    5% — Consistency bonus (never exceeded 130 km/h)
 *
 * Feed speed updates via onSpeed(); call calculate() for the latest stats.
 * The listener fires every UPDATE_INTERVAL_MS while the trip is active.
 */
public class EcoScoreManager {

    // --- Config ---
    private static final float IDLE_THRESHOLD_KMH  = 5f;       // below this = idling
    private static final long  IDLE_MIN_MS          = 15_000L;  // must idle this long to count
    private static final float TRIP_START_MIN_KMH   = 5f;       // speed to begin a trip
    private static final long  UPDATE_INTERVAL_MS   = 10_000L;  // score recalculation interval
    private static final int   MAX_SAMPLES          = 36_000;   // cap at 10 hours @ 1 Hz

    // --- Public data class ---
    public static class TripStats {
        public int   score;           // 0–100 final score
        public String grade;          // "Excellent" / "Good" / "Fair" / "Poor" / "Very Poor"
        public String gradeEmoji;     // 🌿 ✅ 🟡 🔴 ⛽
        public float distanceKm;
        public long  durationSec;     // total trip duration (including idle)
        public long  idleSec;         // cumulative idle seconds
        public float idlePct;         // idle as % of trip
        public float avgSpeedKmh;
        public float maxSpeedKmh;
        // Component scores (each 0–100, before weighting)
        public float zoneScore;
        public float smoothScore;
        public float effScore;
        public float idleScore;
        public float bonusScore;
    }

    public interface Listener { void onScoreUpdated(TripStats stats); }

    // --- State ---
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;

    private final java.util.List<Float> samples = new java.util.ArrayList<>(3600);
    private float maxSpeed      = 0;
    private float distanceKm    = 0;
    private float lastSpeedKmh  = 0;
    private long  lastSampleMs  = 0;

    // Idle tracking
    private boolean isIdle      = false;
    private long    idleStartMs = 0;
    private long    totalIdleMs = 0;

    // Trip lifecycle
    private boolean tripActive  = false;
    private long    tripStartMs = 0;

    public void setListener(Listener l) { this.listener = l; }

    /** Call once per GPS speed update (typically every 1 second). */
    public void onSpeed(float speedKmh) {
        long now = System.currentTimeMillis();

        // Start trip on first movement
        if (!tripActive && speedKmh >= TRIP_START_MIN_KMH) {
            tripActive  = true;
            tripStartMs = now;
            lastSampleMs = now;
        }
        if (!tripActive) return;

        // Distance (trapezoidal integration)
        if (lastSampleMs > 0) {
            float dtHours = (now - lastSampleMs) / 3_600_000f;
            distanceKm += ((lastSpeedKmh + speedKmh) / 2f) * dtHours;
        }
        lastSampleMs  = now;
        lastSpeedKmh  = speedKmh;
        if (speedKmh > maxSpeed) maxSpeed = speedKmh;

        // Idle detection (hysteresis via IDLE_MIN_MS)
        if (speedKmh < IDLE_THRESHOLD_KMH) {
            if (!isIdle) { isIdle = true; idleStartMs = now; }
        } else {
            if (isIdle) {
                long elapsed = now - idleStartMs;
                if (elapsed >= IDLE_MIN_MS) totalIdleMs += elapsed;
                isIdle = false;
            }
        }

        // Collect sample (capped)
        if (samples.size() < MAX_SAMPLES) samples.add(speedKmh);

        // Schedule recalculation
        main.removeCallbacks(recalcRunnable);
        main.postDelayed(recalcRunnable, UPDATE_INTERVAL_MS);
    }

    private final Runnable recalcRunnable = () -> {
        if (listener != null) listener.onScoreUpdated(calculate());
    };

    /** Compute and return the latest TripStats synchronously. */
    public TripStats calculate() {
        TripStats s = new TripStats();
        if (!tripActive || samples.isEmpty()) return s;

        long now = System.currentTimeMillis();
        long tripMs  = now - tripStartMs;
        long idleMs  = totalIdleMs + (isIdle && (now - idleStartMs) >= IDLE_MIN_MS
                        ? (now - idleStartMs) : 0);

        s.distanceKm  = distanceKm;
        s.durationSec = tripMs / 1000;
        s.idleSec     = idleMs / 1000;
        s.idlePct     = tripMs > 0 ? (idleMs * 100f / tripMs) : 0;
        s.maxSpeedKmh = maxSpeed;
        s.avgSpeedKmh = tripMs > 0 ? distanceKm / (tripMs / 3_600_000f) : 0;

        // 1. Zone score (35%)
        float zoneSum = 0;
        for (float v : samples) zoneSum += zonePoints(v);
        s.zoneScore = zoneSum / samples.size();

        // 2. Smoothness — inverse of speed std deviation (25%)
        float mean = s.avgSpeedKmh;
        float varSum = 0;
        for (float v : samples) varSum += (v - mean) * (v - mean);
        float stdDev = (float) Math.sqrt(varSum / samples.size());
        s.smoothScore = Math.max(0, 100 - stdDev * 3f);

        // 3. Average speed efficiency — peaks at 70 km/h (20%)
        s.effScore = Math.max(0, 100 - Math.abs(s.avgSpeedKmh - 70f) * 1.5f);

        // 4. Idle penalty — 0% idle = 100, 40%+ idle = 0 (15%)
        s.idleScore = Math.max(0, 100 - s.idlePct * 2.5f);

        // 5. Speed cap bonus — penalise exceeding 130 km/h (5%)
        s.bonusScore = maxSpeed < 130 ? 100 :
                Math.max(0, 100 - (maxSpeed - 130) * 2f);

        float total = s.zoneScore   * 0.35f
                    + s.smoothScore * 0.25f
                    + s.effScore    * 0.20f
                    + s.idleScore   * 0.15f
                    + s.bonusScore  * 0.05f;

        s.score      = Math.round(total);
        s.grade      = grade(s.score);
        s.gradeEmoji = gradeEmoji(s.score);
        return s;
    }

    /** Reset all trip data (call when starting a new trip). */
    public void reset() {
        samples.clear();
        maxSpeed = 0; distanceKm = 0; lastSpeedKmh = 0; lastSampleMs = 0;
        isIdle = false; idleStartMs = 0; totalIdleMs = 0;
        tripActive = false; tripStartMs = 0;
        main.removeCallbacks(recalcRunnable);
    }

    public boolean isTripActive() { return tripActive; }

    // --- Helpers ---

    private static float zonePoints(float speed) {
        if (speed >= 60 && speed <= 90)  return 100f;
        if (speed >= 40 && speed <= 110) return 70f;
        if (speed >= 20 && speed <= 130) return 40f;
        return 10f;  // idling or very high speed
    }

    private static String grade(int score) {
        if (score >= 90) return "Excellent";
        if (score >= 75) return "Good";
        if (score >= 60) return "Fair";
        if (score >= 40) return "Poor";
        return "Very Poor";
    }

    private static String gradeEmoji(int score) {
        if (score >= 90) return "🌿";
        if (score >= 75) return "✅";
        if (score >= 60) return "🟡";
        if (score >= 40) return "🔴";
        return "⛽";
    }
}
