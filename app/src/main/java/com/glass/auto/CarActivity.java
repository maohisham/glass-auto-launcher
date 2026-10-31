package com.glass.auto;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Calendar;
import java.util.List;

public class CarActivity extends Activity {

    // --- Views ---
    private SplitPanelLayout splitPanel;
    private View             mapPanel;
    private View             divider;
    private View             mapHint;
    private TextView         clock;
    private TextView         speedLabel;
    private ImageView        albumArt;
    private TextView         trackTitle;
    private TextView         trackArtist;
    private ImageView        btnPlayPause;
    private ImageView        btnNext;
    private SeekBar          progress;
    private LinearLayout     lyricsContainer;
    private DockView         dock;

    // --- Core ---
    private Config                 cfg;
    private MediaSessionController mediaCtrl;
    private LyricsProvider         lyricsProvider;
    private SpeedMonitor           speedMonitor;

    private final Handler  handler      = new Handler(Looper.getMainLooper());
    private List<LyricsProvider.LyricLine> currentLyrics;
    private int    currentLyricIndex    = -1;
    private long   trackDurationMs      = 0;
    private boolean isPlaying           = false;
    private String  currentTrack        = "";
    private String  currentArtist       = "";

    // Runnable that ticks the clock and progress bar every second
    private final Runnable tickRunnable = new Runnable() {
        @Override public void run() {
            tickClock();
            tickProgress();
            tickLyrics();
            handler.postDelayed(this, 500);
        }
    };

    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Redirect to setup wizard if any permission is missing
        if (!PermissionHelper.allGranted(this)) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }

        // Fullscreen, keep screen on while driving
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);

        setContentView(R.layout.activity_car);

        cfg = Config.load(this);

        bindViews();
        setupDock();
        setupSplitPanel();
        setupMediaControls();

        mediaCtrl    = new MediaSessionController(this);
        lyricsProvider = new LyricsProvider();
        speedMonitor = new SpeedMonitor(this);

        mediaCtrl.setListener(mediaListener);
        speedMonitor.setListener(speedListener);
    }

    private void bindViews() {
        splitPanel    = findViewById(R.id.split_panel);
        mapPanel      = findViewById(R.id.map_panel);
        divider       = findViewById(R.id.divider);
        mapHint       = findViewById(R.id.map_hint);
        clock         = findViewById(R.id.clock);
        speedLabel    = findViewById(R.id.speed_label);
        albumArt      = findViewById(R.id.album_art);
        trackTitle    = findViewById(R.id.track_title);
        trackArtist   = findViewById(R.id.track_artist);
        btnPlayPause  = findViewById(R.id.btn_play_pause);
        btnNext       = findViewById(R.id.btn_next);
        progress      = findViewById(R.id.progress);
        lyricsContainer = findViewById(R.id.lyrics_container);
        dock          = findViewById(R.id.dock);

        findViewById(R.id.btn_settings).setOnClickListener(
                v -> startActivity(new Intent(this, CarSettingsActivity.class)));
    }

    private void setupDock() {
        dock.setApps(cfg.dockApps, getPackageManager());
        dock.setLaunchListener(pkg -> {
            Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) startActivity(intent);
        });
    }

    private void setupSplitPanel() {
        splitPanel.setRatio(cfg.splitRatio);
        splitPanel.setOnRatioChangedListener(ratio -> {
            cfg.splitRatio = ratio;
            // Relaunch map app with new bounds after ratio settles
            handler.removeCallbacks(relaunchMapRunnable);
            handler.postDelayed(relaunchMapRunnable, 300);
        });

        // Launch map after the layout has measured so we know the bounds
        splitPanel.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override public void onGlobalLayout() {
                        splitPanel.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        launchMapApp();
                    }
                });
    }

    private final Runnable relaunchMapRunnable = this::launchMapApp;

    private void launchMapApp() {
        if (cfg.mapPackage.isEmpty()) {
            mapHint.setVisibility(View.VISIBLE);
            return;
        }
        mapHint.setVisibility(View.GONE);

        // Compute screen-absolute bounds of the map panel
        int[] loc = new int[2];
        mapPanel.getLocationOnScreen(loc);
        Rect bounds = new Rect(loc[0], loc[1],
                loc[0] + mapPanel.getWidth(),
                loc[1] + mapPanel.getHeight());

        FreeformLauncher.launchMap(this, cfg.mapPackage, bounds);
    }

    private void setupMediaControls() {
        btnPlayPause.setOnClickListener(v -> { if (mediaCtrl != null) mediaCtrl.togglePlayPause(); });
        btnNext.setOnClickListener(v -> { if (mediaCtrl != null) mediaCtrl.skipNext(); });
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser && mediaCtrl != null)
                    mediaCtrl.seekTo((long) p * trackDurationMs / 1000);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
    }

    // -------------------------------------------------------------------------
    // Lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        ComponentName cn = new ComponentName(this, MediaListenerService.class);
        mediaCtrl.start(cn);
        speedMonitor.start();
        handler.post(tickRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tickRunnable);
        speedMonitor.stop();
        mediaCtrl.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(relaunchMapRunnable);
        lyricsProvider.cancel();
    }

    // -------------------------------------------------------------------------
    // Tick

    private void tickClock() {
        java.util.Date now = Calendar.getInstance().getTime();
        clock.setText(DateFormat.getTimeFormat(this).format(now));
    }

    private void tickProgress() {
        if (mediaCtrl == null || trackDurationMs <= 0) return;
        long pos = mediaCtrl.getPositionMs();
        progress.setProgress((int) (pos * 1000 / trackDurationMs));
    }

    private void tickLyrics() {
        if (currentLyrics == null || currentLyrics.isEmpty() || mediaCtrl == null) return;
        long pos = mediaCtrl.getPositionMs();
        int idx = LyricsProvider.currentLine(currentLyrics, pos);
        if (idx == currentLyricIndex) return;
        currentLyricIndex = idx;
        highlightLyricLine(idx);
    }

    private void highlightLyricLine(int activeIdx) {
        for (int i = 0; i < lyricsContainer.getChildCount(); i++) {
            TextView tv = (TextView) lyricsContainer.getChildAt(i);
            boolean active = i == activeIdx;
            tv.setTextColor(active ? Color.WHITE : 0x77FFFFFF);
            tv.setTextSize(active ? 15f : 13f);
        }
        // Scroll so active line is visible
        if (activeIdx >= 0 && activeIdx < lyricsContainer.getChildCount()) {
            View v = lyricsContainer.getChildAt(activeIdx);
            findViewById(R.id.lyrics_scroll).scrollTo(0, v.getTop() - 40);
        }
    }

    // -------------------------------------------------------------------------
    // Listeners

    private final MediaSessionController.Listener mediaListener =
            new MediaSessionController.Listener() {
        @Override
        public void onTrackChanged(String title, String artist, Bitmap art) {
            currentTrack  = title  != null ? title  : "";
            currentArtist = artist != null ? artist : "";
            trackTitle.setText(currentTrack.isEmpty() ? getString(R.string.no_media) : currentTrack);
            trackArtist.setText(currentArtist);
            albumArt.setImageBitmap(art);
            currentLyricIndex = -1;
            currentLyrics = null;
            lyricsContainer.removeAllViews();

            if (cfg.showLyrics && !currentTrack.isEmpty()) {
                lyricsProvider.fetch(currentTrack, currentArtist, lyrics -> {
                    currentLyrics = lyrics;
                    currentLyricIndex = -1;
                    lyricsContainer.removeAllViews();
                    for (LyricsProvider.LyricLine line : lyrics) {
                        TextView tv = new TextView(CarActivity.this);
                        tv.setText(line.text);
                        tv.setTextColor(0x77FFFFFF);
                        tv.setTextSize(13f);
                        tv.setPadding(0, 4, 0, 4);
                        lyricsContainer.addView(tv);
                    }
                    if (lyrics.isEmpty()) {
                        TextView tv = new TextView(CarActivity.this);
                        tv.setText(R.string.no_lyrics);
                        tv.setTextColor(0x44FFFFFF);
                        tv.setTextSize(12f);
                        lyricsContainer.addView(tv);
                    }
                });
            }
        }

        @Override
        public void onPlaybackStateChanged(boolean playing, long posMs, long durationMs) {
            isPlaying = playing;
            trackDurationMs = durationMs;
            btnPlayPause.setImageResource(playing
                    ? android.R.drawable.ic_media_pause
                    : android.R.drawable.ic_media_play);
            progress.setMax(1000);
            if (durationMs > 0) progress.setProgress((int) (posMs * 1000 / durationMs));
        }

        @Override
        public void onSessionLost() {
            trackTitle.setText(R.string.no_media);
            trackArtist.setText("");
            albumArt.setImageDrawable(null);
            lyricsContainer.removeAllViews();
        }
    };

    private final SpeedMonitor.Listener speedListener = speedKmh -> {
        if (cfg.speedUnit.equals("mph")) {
            speedLabel.setText(String.valueOf((int) (speedKmh * 0.621371f)));
        } else {
            speedLabel.setText(String.valueOf((int) speedKmh));
        }
    };
}
