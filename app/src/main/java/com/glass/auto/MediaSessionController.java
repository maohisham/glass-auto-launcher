package com.glass.auto;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;

/**
 * Watches the active MediaSession and calls back with track info and playback state.
 *
 * Requires MEDIA_CONTENT_CONTROL permission or a bound NotificationListenerService.
 * Grant via: adb shell pm grant com.glass.auto android.permission.MEDIA_CONTENT_CONTROL
 */
public class MediaSessionController {

    private static final String TAG = "GlassAuto.Media";

    public interface Listener {
        void onTrackChanged(String title, String artist, Bitmap art);
        void onPlaybackStateChanged(boolean playing, long positionMs, long durationMs);
        void onSessionLost();
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private MediaController active;
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata meta) {
            if (listener == null || meta == null) return;
            String title  = meta.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null) artist = meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            Bitmap art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
            long duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION);
            listener.onTrackChanged(title, artist, art);
            notifyPlayback(duration);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            notifyPlayback(-1);
        }
    };

    public MediaSessionController(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void setListener(Listener l) { this.listener = l; }

    /** Call from onResume; binds to the currently active session. */
    public void start(ComponentName notificationListenerComponent) {
        try {
            MediaSessionManager mgr = (MediaSessionManager)
                    ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (mgr == null) return;
            List<MediaController> controllers = mgr.getActiveSessions(notificationListenerComponent);
            if (controllers.isEmpty()) {
                if (listener != null) listener.onSessionLost();
                return;
            }
            attach(controllers.get(0));
        } catch (SecurityException e) {
            Log.w(TAG, "No permission for active sessions — bind NotificationListenerService first", e);
        }
    }

    private void attach(MediaController mc) {
        if (active != null) active.unregisterCallback(callback);
        active = mc;
        active.registerCallback(callback, main);
        // Fire initial state
        MediaMetadata meta = mc.getMetadata();
        if (meta != null) callback.onMetadataChanged(meta);
    }

    /** Returns the current playback position in ms, or 0. */
    public long getPositionMs() {
        if (active == null) return 0;
        PlaybackState ps = active.getPlaybackState();
        if (ps == null) return 0;
        return ps.getPosition();
    }

    public boolean isPlaying() {
        if (active == null) return false;
        PlaybackState ps = active.getPlaybackState();
        return ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
    }

    public void togglePlayPause() {
        if (active == null) return;
        MediaController.TransportControls tc = active.getTransportControls();
        if (isPlaying()) tc.pause(); else tc.play();
    }

    public void skipNext() {
        if (active != null) active.getTransportControls().skipToNext();
    }

    public void skipPrev() {
        if (active != null) active.getTransportControls().skipToPrevious();
    }

    public void seekTo(long posMs) {
        if (active != null) active.getTransportControls().seekTo(posMs);
    }

    public void stop() {
        if (active != null) {
            active.unregisterCallback(callback);
            active = null;
        }
    }

    private void notifyPlayback(long duration) {
        if (listener == null || active == null) return;
        PlaybackState ps = active.getPlaybackState();
        boolean playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
        long pos = ps != null ? ps.getPosition() : 0;
        if (duration < 0) {
            MediaMetadata m = active.getMetadata();
            duration = m != null ? m.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
        }
        listener.onPlaybackStateChanged(playing, pos, duration);
    }
}
