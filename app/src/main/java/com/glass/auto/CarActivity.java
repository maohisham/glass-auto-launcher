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

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CarActivity extends Activity {

    // --- Views ---
    private SplitPanelLayout splitPanel;
    private View             mapPanel;
    private View             mapHint;
    private View             panel3Container;
    private View             panel3Hint;
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
    private LocationHelper         location;
    private MediaSessionController mediaCtrl;
    private LyricsProvider         lyricsProvider;
    private SpeedMonitor           speedMonitor;
    private WeatherManager         weather;
    private PrayerTimesManager     prayer;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // Media state
    private List<LyricsProvider.LyricLine> currentLyrics;
    private int  currentLyricIndex = -1;
    private long trackDurationMs   = 0;

    // Date format caches
    private SimpleDateFormat dateFmt;

    // Tick every 500ms: clock, progress, lyrics, prayer countdown
    private final Runnable tickRunnable = new Runnable() {
        @Override public void run() {
            tickClock();
            tickProgress();
            tickLyrics();
            handler.postDelayed(this, 500);
        }
    };

    // Tick prayer every minute (avoids unnecessary redraws)
    private final Runnable prayerTickRunnable = new Runnable() {
        @Override public void run() {
            prayer.tick();
            handler.postDelayed(this, 60_000);
        }
    };

    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!PermissionHelper.allGranted(this)) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);

        setContentView(R.layout.activity_car);

        cfg     = Config.load(this);
        dateFmt = new SimpleDateFormat("EEE, d MMM", Locale.getDefault());

        bindViews();
        setupDock();
        setupSplitPanel();
        setupMediaControls();
        setupManagers();
    }

    private void bindViews() {
        splitPanel      = findViewById(R.id.split_panel);
        mapPanel        = findViewById(R.id.map_panel);
        mapHint         = findViewById(R.id.map_hint);
        panel3Container = findViewById(R.id.panel3);
        panel3Hint      = findViewById(R.id.panel3_hint);
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
        dock.configureScale(cfg.uiScale); // 0 = auto from dock width; >0 = manual
        dock.setApps(cfg.dockApps, getPackageManager());
        dock.setLaunchListener(pkg -> {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) startActivity(i);
        });
    }

    private void setupSplitPanel() {
        int mode = cfg.panelCount == 1 ? SplitPanelLayout.MODE_SINGLE
                 : cfg.panelCount == 3 ? SplitPanelLayout.MODE_TRIPLE
                 : SplitPanelLayout.MODE_SPLIT;
        splitPanel.setMode(mode);
        splitPanel.setRatios(cfg.splitRatio, cfg.splitRatio2);

        splitPanel.setOnRatioChangedListener((dividerIdx, r1, r2) -> {
            cfg.splitRatio  = r1;
            cfg.splitRatio2 = r2;
            handler.removeCallbacks(relaunchPanelsRunnable);
            handler.postDelayed(relaunchPanelsRunnable, 300);
        });

        splitPanel.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override public void onGlobalLayout() {
                        splitPanel.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        launchFreeformPanels();
                    }
                });
    }

    private final Runnable relaunchPanelsRunnable = this::launchFreeformPanels;

    private void launchFreeformPanels() {
        launchPanel1();
        if (cfg.panelCount == 3) launchPanel3();
    }

    private void launchPanel1() {
        if (cfg.mapPackage.isEmpty()) { mapHint.setVisibility(View.VISIBLE); return; }
        mapHint.setVisibility(View.GONE);
        int[] loc = new int[2];
        mapPanel.getLocationOnScreen(loc);
        Rect bounds = new Rect(loc[0], loc[1],
                loc[0] + mapPanel.getWidth(), loc[1] + mapPanel.getHeight());
        FreeformLauncher.launchMap(this, cfg.mapPackage, bounds);
    }

    private void launchPanel3() {
        if (cfg.panel3Package.isEmpty()) {
            panel3Hint.setVisibility(View.VISIBLE);
            return;
        }
        panel3Hint.setVisibility(View.GONE);
        int[] loc = new int[2];
        panel3Container.getLocationOnScreen(loc);
        Rect bounds = new Rect(loc[0], loc[1],
                loc[0] + panel3Container.getWidth(), loc[1] + panel3Container.getHeight());
        FreeformLauncher.launch(this, cfg.panel3Package, bounds);
    }

    private void setupMediaControls() {
        btnPlayPause.setOnClickListener(v -> { if (mediaCtrl != null) mediaCtrl.togglePlayPause(); });
        btnNext.setOnClickListener(v -> { if (mediaCtrl != null) mediaCtrl.skipNext(); });
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser && mediaCtrl != null && trackDurationMs > 0)
                    mediaCtrl.seekTo((long) p * trackDurationMs / 1000);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
    }

    private void setupManagers() {
        // Shared location provider
        location = new LocationHelper(this);

        // Speed (separate GPS listener for 1-second updates)
        speedMonitor = new SpeedMonitor(this);
        speedMonitor.setListener(speedKmh -> {
            String val = cfg.speedUnit.equals("mph")
                    ? String.valueOf((int) (speedKmh * 0.621371f))
                    : String.valueOf((int) speedKmh);
            speedLabel.setText(val);
        });

        // Weather (triggers on location, refreshes every 30 min)
        weather = new WeatherManager();
        weather.setContext(this);
        weather.setListener(d -> dock.setWeather(d.emoji, (int) d.tempC + "°", d.cityName));
        location.addListener(weather);

        // Prayer times (triggers on location, ticks every minute)
        prayer = new PrayerTimesManager();
        prayer.setListener(d -> {
            dock.setPrayerTimes(d.currentPrayer, d.currentTime,
                    d.nextPrayer, d.nextTime,
                    d.zoneName, d.hijriDate);
            if (!d.zoneCode.isEmpty())
                android.util.Log.i("GlassAuto", "Prayer zone: " + d.zoneCode + " — " + d.zoneName);
        });
        location.addListener(prayer);

        // Media
        mediaCtrl    = new MediaSessionController(this);
        lyricsProvider = new LyricsProvider();
        mediaCtrl.setListener(mediaListener);
    }

    // -------------------------------------------------------------------------
    // Lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        ComponentName cn = new ComponentName(this, MediaListenerService.class);
        mediaCtrl.start(cn);
        speedMonitor.start();
        location.start();
        handler.post(tickRunnable);
        handler.post(prayerTickRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tickRunnable);
        handler.removeCallbacks(prayerTickRunnable);
        handler.removeCallbacks(relaunchPanelsRunnable);
        location.stop();
        speedMonitor.stop();
        mediaCtrl.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        lyricsProvider.cancel();
    }

    // -------------------------------------------------------------------------
    // Tick

    private void tickClock() {
        Date now = Calendar.getInstance().getTime();
        String time = DateFormat.getTimeFormat(this).format(now);
        String date = dateFmt.format(now);
        dock.setClock(time, date);
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
        if (activeIdx >= 0 && activeIdx < lyricsContainer.getChildCount()) {
            View v = lyricsContainer.getChildAt(activeIdx);
            findViewById(R.id.lyrics_scroll).scrollTo(0, v.getTop() - 40);
        }
    }

    // -------------------------------------------------------------------------
    // Media listener

    private final MediaSessionController.Listener mediaListener =
            new MediaSessionController.Listener() {
        @Override
        public void onTrackChanged(String title, String artist, Bitmap art) {
            String t = title  != null ? title  : "";
            String a = artist != null ? artist : "";
            trackTitle.setText(t.isEmpty() ? getString(R.string.no_media) : t);
            trackArtist.setText(a);
            albumArt.setImageBitmap(art);
            currentLyricIndex = -1;
            currentLyrics     = null;
            lyricsContainer.removeAllViews();

            if (cfg.showLyrics && !t.isEmpty()) {
                lyricsProvider.fetch(t, a, lyrics -> {
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
}
