package com.glass.auto;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

/**
 * Clean UI Sound Effects (PS5 inspired navigation tick and selection chime).
 */
final class SoundEffects {
    private static SoundPool soundPool;
    private static int soundNav;
    private static int soundSelect;
    private static boolean ready;
    private static boolean navEnabled = true;
    private static boolean selectEnabled = true;
    private static float volumeScale = 1.0f;

    private SoundEffects() {
    }

    static void init(Context context) {
        if (soundPool != null) return;
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            soundPool = new SoundPool.Builder()
                    .setMaxStreams(4)
                    .setAudioAttributes(attrs)
                    .build();
            soundNav = soundPool.load(context, R.raw.ps5_nav, 1);
            soundSelect = soundPool.load(context, R.raw.ps5_select, 1);
            ready = true;
        } catch (Exception ignored) {
            ready = false;
        }
    }

    static void configure(boolean navOn, boolean selectOn, int volume) {
        navEnabled = navOn;
        selectEnabled = selectOn;
        volumeScale = Math.max(0f, Math.min(1f, volume / 100f));
    }

    static void playNav() {
        if (navEnabled && ready && soundPool != null && soundNav != 0) {
            soundPool.play(soundNav, 0.65f * volumeScale, 0.65f * volumeScale, 0, 0, 1.0f);
        }
    }

    static void playSelect() {
        if (selectEnabled && ready && soundPool != null && soundSelect != 0) {
            soundPool.play(soundSelect, 0.85f * volumeScale, 0.85f * volumeScale, 0, 0, 1.0f);
        }
    }

    static void release() {
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
            ready = false;
        }
    }
}
