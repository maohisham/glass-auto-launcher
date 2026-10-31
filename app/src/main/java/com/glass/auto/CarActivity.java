package com.glass.auto;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    private String lastTrackKey    = ""; // "title::artist" — avoids clearing lyrics on same track

    // Panel launch state — only relaunch when config actually changes
    private int    lastLaunchedPanelCount = -1;
    private String lastLaunchedMapPkg     = "";
    private String lastLaunchedMediaPkg   = "";

    // True while CarSettingsActivity is open — suppresses bringToFrontRunnable
    private boolean settingsOpen = false;

    // Last received data — used by popup dialogs
    private PrayerTimesManager.PrayerData lastPrayerData;
    private WeatherManager.WeatherData    lastWeatherData;

    // Date/time format caches (created once — fix #3)
    private SimpleDateFormat  dateFmt;
    private java.text.DateFormat timeFmt;

    // Config dirty flag — only reload from disk after returning from Settings (fix #4)
    private boolean configDirty = false;

    // Playback state — gate progress polling (fix #11)
    private boolean isPlaying = false;

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

        // In Samsung Desktop Mode (or any freeform environment), the activity launches as a
        // floating window. Resize to fill the full display so panel screen coordinates are correct.
        if (isInMultiWindowMode()) {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            getWindow().setLayout(dm.widthPixels, dm.heightPixels);
            getWindow().getAttributes().x = 0;
            getWindow().getAttributes().y = 0;
            getWindow().setAttributes(getWindow().getAttributes());
        }

        setContentView(R.layout.activity_car);

        cfg     = Config.load(this);
        dateFmt = new SimpleDateFormat("EEE, d MMM", Locale.getDefault());
        timeFmt = DateFormat.getTimeFormat(this); // cached once (fix #3)

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

        // ↔ button: move freeform apps off-screen so the divider becomes visible and draggable.
        // After the user drags and releases, onRatioChanged → relaunchPanels at new bounds.
        findViewById(R.id.btn_resize).setOnClickListener(v -> enterResizeMode());

        findViewById(R.id.btn_settings).setOnClickListener(v -> {
            settingsOpen = true;
            // Cancel any pending bring-to-front so it doesn't steal focus back from settings
            handler.removeCallbacks(bringToFrontRunnable);
            // Move freeform windows off-screen so they don't visually block settings
            Rect offScreen = new Rect(0, -5000, 1, -4999);
            if (!cfg.mapPackage.isEmpty()) FreeformLauncher.launchMap(this, cfg.mapPackage, offScreen);
            if (cfg.panelCount == 3 && !cfg.panel3Package.isEmpty())
                FreeformLauncher.launch(this, cfg.panel3Package, offScreen);
            if (!cfg.mediaPackage.isEmpty())
                FreeformLauncher.launch(this, cfg.mediaPackage, offScreen);
            configDirty = true; // settings may have changed — reload on next resume (fix #4)
        handler.postDelayed(() -> startActivity(new Intent(this, CarSettingsActivity.class)), 300);
        });
    }

    private void setupDock() {
        dock.configureScale(cfg.uiScale); // 0 = auto from dock width; >0 = manual
        dock.setApps(cfg.dockApps, getPackageManager());
        dock.setOnSectionTapListener(new DockView.OnSectionTapListener() {
            @Override public void onWeatherTap() { showWeatherPopup(); }
            @Override public void onPrayerTap()  { showPrayerPopup(); }
        });
        dock.setLaunchListener(pkg -> {
            if (splitPanel.getMode() != SplitPanelLayout.MODE_SINGLE) {
                // In split/triple mode: tapping a dock icon fills the right media panel.
                cfg.mediaPackage  = pkg;
                lastLaunchedMediaPkg = pkg;
                Config.save(this, cfg);
                launchPanel2();
                handler.removeCallbacks(bringToFrontRunnable);
                handler.postDelayed(bringToFrontRunnable, 700);
            } else {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) startActivity(i);
            }
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

        // Hide freeform windows while user drags the divider so they can see the resize
        splitPanel.setOnDragStartListener(() -> {
            Rect offScreen = new Rect(0, -5000, 1, -4999);
            if (!cfg.mapPackage.isEmpty()) FreeformLauncher.launchMap(this, cfg.mapPackage, offScreen);
            if (cfg.panelCount == 3 && !cfg.panel3Package.isEmpty())
                FreeformLauncher.launch(this, cfg.panel3Package, offScreen);
            if (!cfg.mediaPackage.isEmpty())
                FreeformLauncher.launch(this, cfg.mediaPackage, offScreen);
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
        lastLaunchedPanelCount = cfg.panelCount;
        lastLaunchedMapPkg     = cfg.mapPackage;
        lastLaunchedMediaPkg   = cfg.mediaPackage;

        launchPanel1();
        if (cfg.panelCount >= 2) launchPanel2();
        if (cfg.panelCount == 3) launchPanel3();

        // Reclaim CarActivity after freeform apps steal focus, unless settings is open
        if (!settingsOpen) {
            handler.removeCallbacks(bringToFrontRunnable);
            handler.postDelayed(bringToFrontRunnable, 700);
        }
    }

    private final Runnable bringToFrontRunnable = () -> {
        if (settingsOpen) return; // don't reclaim focus while settings is visible
        Intent self = new Intent(this, CarActivity.class);
        self.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(self);
    };

    private void launchPanel1() {
        if (cfg.mapPackage.isEmpty()) { mapHint.setVisibility(View.VISIBLE); return; }
        mapHint.setVisibility(View.GONE);
        int[] loc = new int[2];
        mapPanel.getLocationOnScreen(loc);
        Rect bounds = new Rect(loc[0], loc[1],
                loc[0] + mapPanel.getWidth(), loc[1] + mapPanel.getHeight());
        FreeformLauncher.launchMap(this, cfg.mapPackage, bounds);
    }

    private void launchPanel2() {
        // If a media app is selected, launch it as freeform to fill the media panel.
        // Hide built-in media controls (behind the freeform window) to avoid a ghost UI.
        boolean hasFreeformMedia = !cfg.mediaPackage.isEmpty();
        setBuiltinMediaVisible(!hasFreeformMedia);
        if (!hasFreeformMedia) return;
        View mediaPanel = splitPanel.getChildAt(2);
        if (mediaPanel == null || mediaPanel.getWidth() == 0) return;
        int[] loc = new int[2];
        mediaPanel.getLocationOnScreen(loc);
        Rect bounds = new Rect(loc[0], loc[1],
                loc[0] + mediaPanel.getWidth(), loc[1] + mediaPanel.getHeight());
        FreeformLauncher.launch(this, cfg.mediaPackage, bounds);
    }

    /** Moves all freeform apps off-screen so CarActivity's divider is visible and touchable. */
    private void enterResizeMode() {
        Rect offScreen = new Rect(0, -5000, 1, -4999);
        if (!cfg.mapPackage.isEmpty()) FreeformLauncher.launchMap(this, cfg.mapPackage, offScreen);
        if (!cfg.mediaPackage.isEmpty()) FreeformLauncher.launch(this, cfg.mediaPackage, offScreen);
        if (cfg.panelCount == 3 && !cfg.panel3Package.isEmpty())
            FreeformLauncher.launch(this, cfg.panel3Package, offScreen);
        // After apps move off-screen, bring CarActivity to front so the divider is touchable
        handler.removeCallbacks(bringToFrontRunnable);
        handler.postDelayed(bringToFrontRunnable, 500);
    }

    private void setBuiltinMediaVisible(boolean visible) {
        int vis = visible ? View.VISIBLE : View.GONE;
        findViewById(R.id.now_playing_row).setVisibility(vis);
        progress.setVisibility(vis);
        findViewById(R.id.lyrics_scroll).setVisibility(vis);
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
        weather.setListener(d -> {
            lastWeatherData = d;
            dock.setWeather(d.emoji, (int) d.tempC + "°", d.cityName);
        });
        location.addListener(weather);

        // Prayer times (triggers on location, ticks every minute)
        prayer = new PrayerTimesManager();
        prayer.setListener(d -> {
            lastPrayerData = d;
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
        settingsOpen = false;

        // Only reload config from disk when settings were opened (fix #4).
        // bringToFrontRunnable also triggers onResume; skip the disk read there.
        if (configDirty) {
            configDirty = false;
            cfg = Config.load(this);
            dock.setApps(cfg.dockApps, getPackageManager());
            dock.configureScale(cfg.uiScale);
        }

        int mode = cfg.panelCount == 1 ? SplitPanelLayout.MODE_SINGLE
                 : cfg.panelCount == 3 ? SplitPanelLayout.MODE_TRIPLE
                 : SplitPanelLayout.MODE_SPLIT;
        splitPanel.setMode(mode);
        splitPanel.setRatios(cfg.splitRatio, cfg.splitRatio2);

        // Only relaunch freeform panels when the relevant config actually changed.
        // Relaunching on every resume causes an infinite Maps-focus-steal loop.
        boolean panelChanged = cfg.panelCount   != lastLaunchedPanelCount
                || !cfg.mapPackage.equals(lastLaunchedMapPkg)
                || !cfg.mediaPackage.equals(lastLaunchedMediaPkg);
        if (panelChanged) {
            handler.removeCallbacks(relaunchPanelsRunnable);
            handler.postDelayed(relaunchPanelsRunnable, 300);
        }

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
        // bringToFrontRunnable is intentionally NOT cancelled here — it must fire after
        // Maps takes focus so CarActivity can reclaim the foreground. It is cancelled
        // explicitly before opening Settings to avoid interrupting the settings flow.
        location.stop();
        speedMonitor.stop();
        mediaCtrl.stop();
    }

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode, android.content.res.Configuration newConfig) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig);
        // Relaunch panels after window resize (e.g. user maximizes in Samsung Desktop Mode)
        handler.removeCallbacks(relaunchPanelsRunnable);
        handler.postDelayed(relaunchPanelsRunnable, 400);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(bringToFrontRunnable);   // fix #12
        handler.removeCallbacks(tickRunnable);
        handler.removeCallbacks(prayerTickRunnable);
        handler.removeCallbacks(relaunchPanelsRunnable);
        if (lyricsProvider != null) lyricsProvider.cancel();
    }

    // -------------------------------------------------------------------------
    // Tick

    private void tickClock() {
        Date now = Calendar.getInstance().getTime();
        dock.setClock(timeFmt.format(now), dateFmt.format(now));
    }

    private void tickProgress() {
        if (!isPlaying || mediaCtrl == null || trackDurationMs <= 0) return; // fix #11
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

    private int lastHighlightedIdx = -1;

    private void highlightLyricLine(int activeIdx) {
        // Only update the two lines that changed — avoids 100× setTextSize() per tick (fix #2)
        if (lastHighlightedIdx >= 0 && lastHighlightedIdx < lyricsContainer.getChildCount()) {
            TextView prev = (TextView) lyricsContainer.getChildAt(lastHighlightedIdx);
            prev.setTextColor(0x77FFFFFF);
            prev.setTextSize(13f);
        }
        if (activeIdx >= 0 && activeIdx < lyricsContainer.getChildCount()) {
            TextView active = (TextView) lyricsContainer.getChildAt(activeIdx);
            active.setTextColor(Color.WHITE);
            active.setTextSize(15f);
            findViewById(R.id.lyrics_scroll).scrollTo(0, active.getTop() - 40);
        }
        lastHighlightedIdx = activeIdx;
    }

    // -------------------------------------------------------------------------
    // Media listener

    private final MediaSessionController.Listener mediaListener =
            new MediaSessionController.Listener() {
        @Override
        public void onTrackChanged(String title, String artist, Bitmap art) {
            String t = title  != null ? title  : "";
            String a = artist != null ? artist : "";
            String key = t + "::" + a;

            albumArt.setImageBitmap(art);

            // Same track (e.g. session reconnect after bringToFront) — keep lyrics as-is
            if (key.equals(lastTrackKey)) return;
            lastTrackKey = key;

            trackTitle.setText(t.isEmpty() ? getString(R.string.no_media) : t);
            trackArtist.setText(a);
            currentLyricIndex  = -1;
            lastHighlightedIdx = -1;
            currentLyrics      = null;
            lyricsContainer.removeAllViews();

            if (cfg.showLyrics && !t.isEmpty()) {
                final String fetchKey = key;
                lyricsProvider.fetch(t, a, lyrics -> {
                    if (!fetchKey.equals(lastTrackKey)) return; // track changed while fetching
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
            isPlaying = playing; // track state for tickProgress() gate (fix #11)
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

    // -------------------------------------------------------------------------
    // Popup dialogs

    private void showPrayerPopup() {
        PrayerTimesManager.PrayerData data = prayer != null ? prayer.getCached() : lastPrayerData;
        if (data == null || data.times.isEmpty()) return;

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = buildPopupRoot();
        scroll.addView(root);

        addPopupTitle(root, "Waktu Solat");
        if (!data.zoneName.isEmpty())  addPopupSub(root, "📍 " + data.zoneName);
        if (!data.hijriDate.isEmpty()) addPopupSub(root, "🗓 " + data.hijriDate);
        addPopupDivider(root);

        String[][] rows = {
            {"Subuh",   data.times.get("Subuh")},
            {"Syuruk",  data.syurukTime},
            {"Zohor",   data.times.get("Zohor")},
            {"Asar",    data.times.get("Asar")},
            {"Maghrib", data.times.get("Maghrib")},
            {"Isyak",   data.times.get("Isyak")},
        };
        for (String[] row : rows) {
            String name = row[0], raw = row[1];
            if (raw == null || raw.isEmpty()) continue;
            boolean isCurrent = name.equals(data.currentPrayer);
            boolean isNext    = name.equals(data.nextPrayer);
            addPrayerRow(root, name, popupTo12h(raw), isCurrent, isNext);
        }

        addPopupDivider(root);
        String cd = popupCountdown(data.nextTime);
        if (!cd.isEmpty()) {
            addPopupSub(root, "Next: " + data.nextPrayer + " — " + cd);
        }

        dialog.setContentView(scroll);
        popupWindow(dialog, 0.62f);
        dialog.show();
    }

    private void showWeatherPopup() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = buildPopupRoot();
        scroll.addView(root);

        WeatherManager.WeatherData cur = lastWeatherData;
        if (cur != null) {
            addPopupTitle(root, cur.emoji + "  " + (int) cur.tempC + "°C  " + cur.description);
            if (!cur.cityName.isEmpty()) addPopupSub(root, "📍 " + cur.cityName);
            addPopupSub(root, "Humidity: " + cur.humidity + "%");
        } else {
            addPopupTitle(root, "Weather");
        }
        addPopupDivider(root);

        TextView loadingTv = new TextView(this);
        loadingTv.setText("Loading forecast…");
        loadingTv.setTextColor(0x88FFFFFF);
        loadingTv.setTextSize(16f);
        loadingTv.setPadding(0, dp(12), 0, dp(12));
        root.addView(loadingTv);

        dialog.setContentView(scroll);
        popupWindow(dialog, 0.72f);
        dialog.show();

        weather.fetchForecast(forecast -> {
            if (!dialog.isShowing()) return;
            root.removeView(loadingTv);
            fillForecastInto(root, forecast);
        });
    }

    private void fillForecastInto(LinearLayout root, WeatherManager.ForecastData forecast) {
        if (!forecast.hourly.isEmpty()) {
            addPopupSection(root, "Next 24 Hours");
            HorizontalScrollView hsv = new HorizontalScrollView(this);
            hsv.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(4), 0, dp(4));
            for (WeatherManager.HourlyPoint p : forecast.hourly) {
                row.addView(buildHourlyCell(p));
            }
            hsv.addView(row);
            root.addView(hsv);
            addPopupDivider(root);
        }

        if (!forecast.daily.isEmpty()) {
            addPopupSection(root, "7-Day Forecast");
            String[] dayNames = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
            for (WeatherManager.DailyPoint p : forecast.daily) {
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(p.timeEpoch * 1000);
                String dayName = dayNames[cal.get(Calendar.DAY_OF_WEEK) - 1];
                addDailyRow(root, dayName, p);
            }
            addPopupDivider(root);
        }

        if (forecast.aqi != null) {
            addPopupSection(root, "Air Quality");
            addPopupSub(root, forecast.aqi.label() + " — AQI " + forecast.aqi.europeanAqi);
            addPopupSub(root, String.format(Locale.US, "PM2.5: %.1f µg/m³   PM10: %.1f µg/m³",
                    forecast.aqi.pm25, forecast.aqi.pm10));
        }
    }

    private View buildHourlyCell(WeatherManager.HourlyPoint p) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(14), dp(8), dp(14), dp(8));

        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(p.timeEpoch * 1000);
        int h = cal.get(Calendar.HOUR_OF_DAY);
        String ampm = h < 12 ? "AM" : "PM";
        h = h % 12; if (h == 0) h = 12;

        addCellText(cell, h + " " + ampm, 14f, 0xAAFFFFFF, false);
        addCellText(cell, emojiFor(p.weatherCode), 28f, 0xFFFFFFFF, false);
        addCellText(cell, (int) p.tempC + "°", 18f, 0xFFFFFFFF, true);
        if (p.precipPct > 0)
            addCellText(cell, p.precipPct + "%", 13f, 0xFF6EB5FF, false);
        return cell;
    }

    private void addDailyRow(LinearLayout parent, String dayName, WeatherManager.DailyPoint p) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(12), dp(4), dp(12));

        TextView day = new TextView(this);
        day.setText(dayName);
        day.setTextColor(0xFFFFFFFF);
        day.setTextSize(18f);
        day.setTypeface(null, Typeface.BOLD);
        day.setLayoutParams(new LinearLayout.LayoutParams(dp(60), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(day);

        TextView emoji = new TextView(this);
        emoji.setText(emojiFor(p.weatherCode));
        emoji.setTextSize(24f);
        emoji.setLayoutParams(new LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(emoji);

        TextView temps = new TextView(this);
        temps.setText((int) p.tempMaxC + "°  /  " + (int) p.tempMinC + "°");
        temps.setTextColor(0xFFFFFFFF);
        temps.setTextSize(18f);
        temps.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(temps);

        if (p.precipPct > 0) {
            TextView rain = new TextView(this);
            rain.setText("💧 " + p.precipPct + "%");
            rain.setTextColor(0xFF6EB5FF);
            rain.setTextSize(15f);
            row.addView(rain);
        }
        parent.addView(row);
    }

    private void addCellText(LinearLayout parent, String text, float size, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(size);
        tv.setTextColor(color);
        tv.setGravity(Gravity.CENTER);
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        parent.addView(tv);
    }

    private void addPrayerRow(LinearLayout parent, String name, String time12,
                              boolean isCurrent, boolean isNext) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(14), dp(12), dp(14));

        if (isCurrent) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x33FFFFFF);
            bg.setCornerRadius(dp(10));
            row.setBackground(bg);
        }

        TextView nameTv = new TextView(this);
        nameTv.setText(name);
        nameTv.setTextColor(isCurrent ? 0xFFFFFFFF : 0xCCFFFFFF);
        nameTv.setTextSize(isCurrent ? 22f : 20f);
        if (isCurrent) nameTv.setTypeface(null, Typeface.BOLD);
        nameTv.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(nameTv);

        TextView timeTv = new TextView(this);
        timeTv.setText(time12);
        timeTv.setTextColor(isCurrent ? 0xFFFFFFFF : 0xAAFFFFFF);
        timeTv.setTextSize(isCurrent ? 22f : 20f);
        if (isCurrent) timeTv.setTypeface(null, Typeface.BOLD);
        row.addView(timeTv);

        if (isNext) {
            TextView badge = new TextView(this);
            badge.setText("  next →");
            badge.setTextColor(0xFF6EB5FF);
            badge.setTextSize(14f);
            row.addView(badge);
        }
        parent.addView(row);
    }

    // --- Popup builder helpers ---

    private LinearLayout buildPopupRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(24), dp(28), dp(28));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2060C18);
        bg.setCornerRadius(dp(20));
        bg.setStroke(1, 0x44FFFFFF);
        root.setBackground(bg);
        return root;
    }

    /** widthFraction: fraction of real display width (e.g. 0.65 = 65%). */
    private void popupWindow(Dialog dialog, float widthFraction) {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int widthPx = (int) (dm.widthPixels * widthFraction);

        Window w = dialog.getWindow();
        w.setBackgroundDrawableResource(android.R.color.transparent);
        w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        w.setLayout(widthPx, WindowManager.LayoutParams.WRAP_CONTENT);
        w.setGravity(Gravity.CENTER);
    }

    private void addPopupTitle(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(24f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, 0, 0, dp(6));
        parent.addView(tv);
    }

    private void addPopupSub(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xBBFFFFFF);
        tv.setTextSize(16f);
        tv.setPadding(0, dp(3), 0, dp(3));
        parent.addView(tv);
    }

    private void addPopupSection(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0x88FFFFFF);
        tv.setTextSize(13f);
        tv.setAllCaps(true);
        tv.setLetterSpacing(0.12f);
        tv.setPadding(0, dp(4), 0, dp(8));
        parent.addView(tv);
    }

    private void addPopupDivider(LinearLayout parent) {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(0, dp(14), 0, dp(14));
        v.setLayoutParams(lp);
        v.setBackgroundColor(0x33FFFFFF);
        parent.addView(v);
    }

    private int dp(int val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
    }

    private static String popupTo12h(String t) {
        if (t == null || t.isEmpty()) return t;
        try {
            int colon = t.indexOf(':');
            if (colon < 1) return t;
            int h = Integer.parseInt(t.substring(0, colon).trim());
            String min = t.substring(colon + 1, Math.min(colon + 3, t.length()));
            String ampm = h < 12 ? "AM" : "PM";
            h = h % 12; if (h == 0) h = 12;
            return h + ":" + min + " " + ampm;
        } catch (Exception e) { return t; }
    }

    private static String popupCountdown(String t) {
        if (t == null || t.isEmpty()) return "";
        try {
            int colon = t.indexOf(':');
            if (colon < 1) return "";
            int nextH = Integer.parseInt(t.substring(0, colon).trim());
            int nextM = Integer.parseInt(t.substring(colon + 1, Math.min(colon + 3, t.length())).trim());
            Calendar now = Calendar.getInstance();
            int diff = (nextH * 60 + nextM) - (now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE));
            if (diff <= 0) diff += 24 * 60;
            int h = diff / 60, m = diff % 60;
            return h > 0 ? "in " + h + "h " + m + "m" : "in " + m + "m";
        } catch (Exception e) { return ""; }
    }

    private static String emojiFor(int code) {
        if (code == 0)       return "☀";
        if (code <= 2)       return "🌤";
        if (code == 3)       return "☁";
        if (code <= 48)      return "🌫";
        if (code <= 55)      return "🌦";
        if (code <= 65)      return "🌧";
        if (code <= 67)      return "🌨";
        if (code <= 77)      return "❄";
        if (code <= 82)      return "🌦";
        if (code <= 86)      return "🌨";
        if (code <= 99)      return "⛈";
        return "🌡";
    }
}
