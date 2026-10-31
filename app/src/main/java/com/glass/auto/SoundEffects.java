package com.glass.auto;

import android.content.Context;

/** UI sound effects — disabled (no audio assets bundled). */
final class SoundEffects {

    private SoundEffects() { }

    static void init(Context context) { }

    static void configure(boolean navOn, boolean selectOn, int volume) { }

    static void playNav() { }

    static void playSelect() { }

    static void release() { }
}
