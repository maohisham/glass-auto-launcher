package com.glass.auto;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
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
    private TextView         ecoScoreLabel;
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
    private EcoScoreManager        ecoScore;
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

    // Panel split ratio presets for 2-panel mode (fraction of total width for map panel)
    private static final float[] SPLIT_PRESETS = {0.70f, 0.50f, 0.30f};
    private int splitRatioState = 0; // index into SPLIT_PRESETS

    // Overlays — float above freeform windows so Maps can't cover them
    private WindowManager wm;
    private View          statusBarOverlayView;
    private DockView      dockOverlay;

    // Speed alert
    private android.media.ToneGenerator toneGen;
    private boolean wasOverSpeed = false;
    private long    lastBeepMs   = 0L;
    private static final long BEEP_INTERVAL_MS = 4000L;

    // Auto-play retry state
    private int autoPlayAttempts = 0;
    private static final int   AUTO_PLAY_MAX_ATTEMPTS = 4;
    private static final long[] AUTO_PLAY_DELAYS_MS   = {2000, 3000, 4000, 5000};

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
        setupStatusBarOverlay();
        setupDockOverlay();
        checkAndEnableFreeform();
        ensurePanelWatchEnabled();
        instance = this;
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
        ecoScoreLabel = findViewById(R.id.eco_score);
        albumArt      = findViewById(R.id.album_art);
        trackTitle    = findViewById(R.id.track_title);
        trackArtist   = findViewById(R.id.track_artist);
        btnPlayPause  = findViewById(R.id.btn_play_pause);
        btnNext       = findViewById(R.id.btn_next);
        progress      = findViewById(R.id.progress);
        lyricsContainer = findViewById(R.id.lyrics_container);
        dock          = findViewById(R.id.dock);

        // Eco score: tap to show breakdown popup.
        ecoScoreLabel.setOnClickListener(v -> showEcoPopup());

        // ↔ button: cycle through 3 panel split presets (70/30 → 50/50 → 30/70 → 70/30).
        findViewById(R.id.btn_resize).setOnClickListener(v -> cycleRatio());

        // Mic button: launch Gemini (or default voice assistant).
        findViewById(R.id.btn_gemini).setOnClickListener(v -> launchVoiceAssistant());

        View btnSettings = findViewById(R.id.btn_settings);
        btnSettings.setOnClickListener(v -> {
            openSettings();
        });
        btnSettings.setOnLongClickListener(v -> {
            startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        });
    }

    /**
     * Creates a TYPE_APPLICATION_OVERLAY status bar that floats above freeform windows.
     * Google Maps (and other edge-to-edge apps) extend their window above the specified
     * freeform bounds, covering the in-layout status bar. This overlay prevents that.
     */
    private void setupStatusBarOverlay() {
        if (!Settings.canDrawOverlays(this)) return; // fallback: invisible XML status bar stays

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        // Query system status bar height so we start below it
        int sysBarH = 0;
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) sysBarH = getResources().getDimensionPixelSize(resId);

        float density = getResources().getDisplayMetrics().density;
        int barH = Math.round(48 * density); // 48dp in pixels

        int screenW = getResources().getDisplayMetrics().widthPixels;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                screenW,
                barH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0; // overlay covers the system status bar — one unified top bar

        statusBarOverlayView = LayoutInflater.from(this)
                .inflate(R.layout.overlay_status_bar, null);

        // Rebind status bar fields to overlay views (main-layout status bar is now invisible)
        speedLabel    = statusBarOverlayView.findViewById(R.id.speed_label);
        ecoScoreLabel = statusBarOverlayView.findViewById(R.id.eco_score);

        ecoScoreLabel.setOnClickListener(v -> showEcoPopup());
        statusBarOverlayView.findViewById(R.id.btn_resize)
                .setOnClickListener(v -> cycleRatio());
        statusBarOverlayView.findViewById(R.id.btn_gemini)
                .setOnClickListener(v -> launchVoiceAssistant());

        View ovlSettings = statusBarOverlayView.findViewById(R.id.btn_settings);
        ovlSettings.setOnClickListener(v -> {
            openSettings();
        });
        ovlSettings.setOnLongClickListener(v -> {
            startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        });

        wm.addView(statusBarOverlayView, lp);
    }

    private void setupDockOverlay() {
        if (!Settings.canDrawOverlays(this)) return;
        if (wm == null) wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        float density = getResources().getDisplayMetrics().density;
        int dockH  = Math.round(64 * density);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                screenW, dockH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.x = 0;
        lp.y = 0;

        dockOverlay = new DockView(this, null);
        dockOverlay.setBackgroundColor(0xCC000000);
        wm.addView(dockOverlay, lp);

        // The in-layout dock would show through the translucent overlay with stale text.
        dock.setVisibility(View.GONE);
        // Redirect 'dock' so all setters (setClock, setWeather, setPrayerTimes, setApps) go to overlay
        dock = dockOverlay;

        dockOverlay.setOnAppDrawerTapListener(this::showAppDrawerPopup);
    }

    // -------------------------------------------------------------------------
    // Panel area, resize handles, app drawer

    private final View[] resizeHandles = new View[2];
    private final WindowManager.LayoutParams[] resizeLps = new WindowManager.LayoutParams[2];
    private View appDrawerView;

    /** Screen rectangle between the status bar overlay and the dock overlay. */
    private Rect contentBounds() {
        View root = findViewById(R.id.root);
        int[] r = new int[2];
        root.getLocationOnScreen(r);
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) splitPanel.getLayoutParams();
        return new Rect(r[0], r[1] + lp.topMargin,
                r[0] + root.getWidth(), r[1] + root.getHeight() - lp.bottomMargin);
    }

    /**
     * Sizes the panel area from where the overlays really sit, so freeform windows never
     * extend underneath the dock or status bar. Returns true if the margins changed.
     */
    private boolean syncPanelInsets() {
        View root = findViewById(R.id.root);
        int[] r = new int[2];
        root.getLocationOnScreen(r);
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) splitPanel.getLayoutParams();
        int top = lp.topMargin, bottom = lp.bottomMargin;
        if (statusBarOverlayView != null && statusBarOverlayView.getHeight() > 0) {
            int[] s = new int[2];
            statusBarOverlayView.getLocationOnScreen(s);
            top = Math.max(0, s[1] + statusBarOverlayView.getHeight() - r[1]);
        }
        if (dockOverlay != null && dockOverlay.getHeight() > 0) {
            int[] d = new int[2];
            dockOverlay.getLocationOnScreen(d);
            bottom = Math.max(0, r[1] + root.getHeight() - d[1]);
        }
        if (top == lp.topMargin && bottom == lp.bottomMargin) return false;
        lp.topMargin = top;
        lp.bottomMargin = bottom;
        splitPanel.setLayoutParams(lp);
        return true;
    }

    private void launchWhenOverlaysReady(int attempt) {
        boolean ready = (dockOverlay == null || dockOverlay.getHeight() > 0)
                && (statusBarOverlayView == null || statusBarOverlayView.getHeight() > 0);
        if (!ready && attempt < 15) {
            handler.postDelayed(() -> launchWhenOverlaysReady(attempt + 1), 100);
            return;
        }
        syncPanelInsets();
        handler.postDelayed(() -> {
            launchFreeformPanels();
            ensureResizeHandles();
        }, 150); // let the new margins lay out before reading panel bounds
    }

    /** Draggable divider windows floating above the freeform panels (they cover the in-layout dividers). */
    private void ensureResizeHandles() {
        if (wm == null) return;
        for (int i = 0; i < 2; i++) {
            if (resizeHandles[i] != null) continue;
            final int idx = i + 1;
            final android.graphics.Paint stripPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            stripPaint.setColor(0xFF2B2B2B);
            final android.graphics.Paint gripPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            gripPaint.setColor(0xFFFFFFFF);
            final android.graphics.RectF rect = new android.graphics.RectF();
            View v = new View(this) {
                @Override protected void onDraw(android.graphics.Canvas c) {
                    float w = getWidth(), h = getHeight();
                    float stripW = Math.max(0, w - 2 * dp(12));
                    c.drawRect((w - stripW) / 2f, 0, (w + stripW) / 2f, h, stripPaint);
                    float gw = dp(6), gh = dp(80);
                    rect.set((w - gw) / 2f, (h - gh) / 2f, (w + gw) / 2f, (h + gh) / 2f);
                    c.drawRoundRect(rect, gw / 2f, gw / 2f, gripPaint);
                }
            };
            final float[] start = new float[2]; // raw x, ratio at touch-down
            final boolean[] dragged = new boolean[1];
            final float tapSlop = dp(10);
            v.setOnTouchListener((view, e) -> {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        start[0] = e.getRawX();
                        start[1] = idx == 1 ? splitPanel.getRatio1() : splitPanel.getRatio2();
                        dragged[0] = false;
                        handler.removeCallbacks(relaunchPanelsRunnable);
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getRawX() - start[0]) > tapSlop) dragged[0] = true;
                        if (dragged[0]) splitPanel.applyDrag(idx, start[1], e.getRawX() - start[0]);
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (!dragged[0]) {
                            // A tap on the divider swaps the two sides, like the system split-screen divider.
                            if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP && idx == 1) swapPanels();
                            return true;
                        }
                        layoutPending = true;
                        cfg.splitRatio  = splitPanel.getRatio1();
                        cfg.splitRatio2 = splitPanel.getRatio2();
                        splitRatioState = closestPresetIdx(cfg.splitRatio);
                        Config.save(this, cfg);
                        handler.postDelayed(relaunchPanelsRunnable, 150);
                        return true;
                }
                return false;
            });
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            resizeHandles[i] = v;
            resizeLps[i] = lp;
            v.setVisibility(View.GONE);
            wm.addView(v, lp);
        }
        splitPanel.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> positionResizeHandles());
        positionResizeHandles();
    }

    private void positionResizeHandles() {
        if (wm == null) return;
        int slop = dp(12);
        for (int i = 0; i < 2; i++) {
            View h = resizeHandles[i];
            if (h == null) continue;
            View d = splitPanel.getChildAt(i == 0 ? 1 : 3);
            boolean show = !settingsOpen && cfg.panelCount > i + 1 && d != null
                    && d.getVisibility() == View.VISIBLE && d.getWidth() > 0;
            if (!show) { h.setVisibility(View.GONE); continue; }
            int[] loc = new int[2];
            d.getLocationOnScreen(loc);
            WindowManager.LayoutParams lp = resizeLps[i];
            lp.x = loc[0] - slop;
            lp.y = loc[1];
            lp.width  = d.getWidth() + 2 * slop;
            lp.height = d.getHeight();
            h.setVisibility(View.VISIBLE);
            try { wm.updateViewLayout(h, lp); } catch (Exception ignored) { }
        }
    }

    private void dismissAppDrawer() {
        if (appDrawerView != null && wm != null) {
            try { wm.removeView(appDrawerView); } catch (Exception ignored) { }
        }
        appDrawerView = null;
    }

    /**
     * App grid shown as an overlay window. A Dialog would be hidden behind the freeform
     * panels, because CarActivity itself is not in the foreground. Tapping an app opens it
     * in a freeform window filling the whole panel area (so it is not cut off by the dock).
     */
    private void showAppDrawerPopup() {
        if (appDrawerView != null) { dismissAppDrawer(); return; }
        if (wm == null) return;
        final List<FreeformLauncher.AppInfo> apps = FreeformLauncher.findAllApps(this);
        final Rect content = contentBounds();

        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setBackgroundColor(0xF2000000);
        frame.setOnClickListener(v -> dismissAppDrawer());

        android.widget.GridView grid = new android.widget.GridView(this);
        grid.setNumColumns(Math.max(4, Math.min(10, content.width() / dp(130))));
        grid.setStretchMode(android.widget.GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(dp(8));
        grid.setPadding(dp(16), dp(16), dp(16), dp(16));
        grid.setClipToPadding(false);
        grid.setAdapter(new android.widget.BaseAdapter() {
            @Override public int getCount() { return apps.size(); }
            @Override public Object getItem(int p) { return apps.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int pos, View cv, android.view.ViewGroup parent) {
                LinearLayout cell = new LinearLayout(CarActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(dp(4), dp(10), dp(4), dp(10));
                ImageView icon = new ImageView(CarActivity.this);
                icon.setImageDrawable(apps.get(pos).icon);
                icon.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(56)));
                cell.addView(icon);
                TextView label = new TextView(CarActivity.this);
                label.setText(apps.get(pos).label);
                label.setTextColor(0xFFFFFFFF);
                label.setTextSize(14f);
                label.setMaxLines(1);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                label.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp2.topMargin = dp(6);
                label.setLayoutParams(lp2);
                cell.addView(label);
                return cell;
            }
        });
        grid.setOnItemClickListener((parent, v, pos, id) -> {
            String pkg = apps.get(pos).packageName;
            dismissAppDrawer();
            if (!FreeformLauncher.launch(this, pkg, content, true)) {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) startActivity(i);
            }
        });
        frame.addView(grid, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                content.width(), content.height(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = content.left;
        lp.y = content.top;
        appDrawerView = frame;
        wm.addView(frame, lp);
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
                // Kill the previous media app before launching the new one so only one runs.
                String prev = lastLaunchedMediaPkg;
                if (!prev.isEmpty() && !prev.equals(pkg)) killMediaApp(prev);
                cfg.mediaPackage  = pkg;
                lastLaunchedMediaPkg = pkg;
                Config.save(this, cfg);
                // Short delay after kill so the foreground service has time to stop
                handler.postDelayed(() -> {
                    launchPanel2();
                    scheduleAutoPlay();
                }, 300);
            } else {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) startActivity(i);
            }
        });
    }

    private void setupSplitPanel() {
        splitRatioState = closestPresetIdx(cfg.splitRatio);
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
                        launchWhenOverlaysReady(0);
                    }
                });
    }

    private final Runnable relaunchPanelsRunnable = this::applyLayoutLive;

    /**
     * Android only applies launch bounds to a new task, so open panel apps are force-stopped
     * (through the accessibility service) and then reopened at the new bounds.
     */
    private void applyLayoutLive() {
        PanelWatchService svc = PanelWatchService.instance;
        java.util.Set<String> pkgs = watchedPackages();
        if (svc == null || pkgs.isEmpty()) { launchFreeformPanels(); return; }
        if (svc.isStopping()) return;
        android.widget.Toast.makeText(this, "Applying new layout…", android.widget.Toast.LENGTH_SHORT).show();
        lastOwnLaunchMs = android.os.SystemClock.uptimeMillis();
        svc.forceStopAll(pkgs, () -> {
            layoutPending = false;
            launchFreeformPanels();
        });
    }

    private void launchFreeformPanels() {
        lastLaunchedPanelCount = cfg.panelCount;
        lastLaunchedMapPkg     = cfg.mapPackage;
        lastLaunchedMediaPkg   = cfg.mediaPackage;

        launchPanel1();
        if (cfg.panelCount >= 2 && cfg.autoStartMedia) {
            launchPanel2();
            scheduleAutoPlay();
        }
        if (cfg.panelCount == 3) launchPanel3();

        // Do NOT reclaim focus — freeform apps stay visible on screen.
        // Dock and status bar are rendered as TYPE_APPLICATION_OVERLAY windows (see setupOverlayWindows).
        // bringToFrontRunnable is only used for the settings flow.
    }

    private final Runnable bringToFrontRunnable = () -> {
        if (settingsOpen) return; // don't reclaim focus while settings is visible
        Intent self = new Intent(this, CarActivity.class);
        self.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(self);
    };

    // Used by PanelWatchService
    static volatile CarActivity instance;
    volatile long lastOwnLaunchMs = 0L;

    boolean isSettingsOpen() { return settingsOpen; }

    /**
     * True after the user changed the layout (drag or swap) while the panel apps were open.
     * Open apps keep their old window bounds until restarted, so PanelWatchService must not
     * keep restarting them; it still catches apps that open outside the panel area.
     */
    volatile boolean layoutPending = false;

    boolean isOutsideContent(Rect r) {
        Rect c = contentBounds();
        int tol = 24;
        return r.top < c.top - tol || r.bottom > c.bottom + tol
                || r.left < c.left - tol || r.right > c.right + tol;
    }

    // Left panel is the map unless the user swapped the sides; panel views are fixed in the layout.
    private View mapPanelView() {
        return cfg.swapPanels && cfg.panelCount >= 2 ? splitPanel.getChildAt(2) : mapPanel;
    }

    private View mediaPanelView() {
        return cfg.swapPanels ? mapPanel : splitPanel.getChildAt(2);
    }

    private void swapPanels() {
        if (cfg.panelCount < 2) return;
        cfg.swapPanels = !cfg.swapPanels;
        // Each app keeps its size when it changes sides.
        cfg.splitRatio = 1f - splitPanel.getRatio1();
        splitPanel.setRatios(cfg.splitRatio, cfg.splitRatio2);
        splitRatioState = closestPresetIdx(cfg.splitRatio);
        Config.save(this, cfg);
        layoutPending = true;
        handler.removeCallbacks(relaunchPanelsRunnable);
        handler.postDelayed(relaunchPanelsRunnable, 150);
    }

    /** Screen rectangle this app's panel occupies right now, or null if it has none. */
    Rect panelBoundsFor(String pkg) {
        if (splitPanel == null) return null;
        View v = null;
        if (pkg.equals(cfg.mapPackage)) v = mapPanelView();
        else if (pkg.equals(cfg.mediaPackage) && cfg.panelCount >= 2) v = mediaPanelView();
        else if (pkg.equals(cfg.panel3Package) && cfg.panelCount == 3) v = panel3Container;
        if (v == null || v.getWidth() <= 0) return null;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return new Rect(loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight());
    }

    java.util.Set<String> watchedPackages() {
        java.util.Set<String> s = new java.util.HashSet<>();
        if (!cfg.mapPackage.isEmpty()) s.add(cfg.mapPackage);
        if (cfg.panelCount >= 2 && !cfg.mediaPackage.isEmpty()) s.add(cfg.mediaPackage);
        if (cfg.panelCount == 3 && !cfg.panel3Package.isEmpty()) s.add(cfg.panel3Package);
        return s;
    }

    /** Re-places a panel app that was opened outside its panel by something other than us. */
    void enforcePanelBounds(String pkg) {
        if (pkg.equals(cfg.mapPackage)) launchPanel1();
        else if (pkg.equals(cfg.mediaPackage) && cfg.panelCount >= 2) launchPanel2();
        else if (pkg.equals(cfg.panel3Package) && cfg.panelCount == 3) launchPanel3();
    }

    private void ensurePanelWatchEnabled() {
        try {
            String me = new ComponentName(this, PanelWatchService.class).flattenToString();
            String cur = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur == null || !cur.contains(me)) {
                Settings.Secure.putString(getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        (cur == null || cur.isEmpty()) ? me : cur + ":" + me);
                Settings.Secure.putInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
            // Listed as enabled but not running (e.g. after an app update or force-stop): toggle it so Android rebinds it.
            handler.postDelayed(() -> rebindPanelWatchIfIdle(me), 3000);
        } catch (SecurityException ignored) { } // needs WRITE_SECURE_SETTINGS
    }

    private void rebindPanelWatchIfIdle(String me) {
        if (PanelWatchService.instance != null) return;
        try {
            String now = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (now == null || !now.contains(me)) return;
            String without = now.replace(":" + me, "").replace(me + ":", "").replace(me, "");
            Settings.Secure.putString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without);
            handler.postDelayed(() -> {
                try {
                    String c2 = Settings.Secure.getString(getContentResolver(),
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                    if (c2 != null && c2.contains(me)) return;
                    Settings.Secure.putString(getContentResolver(),
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                            (c2 == null || c2.isEmpty()) ? me : c2 + ":" + me);
                } catch (SecurityException ignored) { }
            }, 600);
        } catch (SecurityException ignored) { }
    }

    private void launchPanel1() {
        lastOwnLaunchMs = android.os.SystemClock.uptimeMillis();
        if (cfg.mapPackage.isEmpty()) { mapHint.setVisibility(View.VISIBLE); return; }
        mapHint.setVisibility(View.GONE);
        int[] loc = new int[2];
        View mp = mapPanelView();
        mp.getLocationOnScreen(loc);
        Rect bounds = new Rect(loc[0], loc[1],
                loc[0] + mp.getWidth(), loc[1] + mp.getHeight());
        FreeformLauncher.launchMap(this, cfg.mapPackage, bounds, true);
    }

    private void launchPanel2() {
        lastOwnLaunchMs = android.os.SystemClock.uptimeMillis();
        boolean hasFreeformMedia = !cfg.mediaPackage.isEmpty();
        setBuiltinMediaVisible(!hasFreeformMedia);
        if (!hasFreeformMedia) return;
        View mediaPanel = mediaPanelView();
        if (mediaPanel == null || mediaPanel.getWidth() == 0) return;
        int[] loc = new int[2];
        mediaPanel.getLocationOnScreen(loc);
        Rect bounds = new Rect(loc[0], loc[1],
                loc[0] + mediaPanel.getWidth(), loc[1] + mediaPanel.getHeight());
        FreeformLauncher.launch(this, cfg.mediaPackage, bounds, true);
    }

    /**
     * Cycles the 2-panel split through three presets: 70/30 → 50/50 → 30/70 → 70/30.
     * Updates the SplitPanelLayout immediately and relaunches freeform windows at new bounds.
     */
    private void showEcoPopup() {
        EcoScoreManager.TripStats stats = ecoScore.calculate();
        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar);

        LinearLayout root = buildPopupRoot();
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        dialog.setContentView(scroll);

        // Header
        addPopupTitle(root, stats.score > 0
                ? stats.gradeEmoji + "  Eco Score: " + stats.score + " / 100"
                : "🌿  Eco Score");
        addPopupSub(root, stats.score > 0
                ? stats.grade + " driving efficiency"
                : "Drive to start scoring");
        addPopupDivider(root);

        if (!ecoScore.isTripActive()) {
            addPopupSub(root, "No active trip. Score updates after\nyou start driving.");
        } else {
            // Trip summary
            addPopupSection(root, "Trip Summary");
            addEcoRow(root, "📍  Distance",   String.format(Locale.US, "%.1f km", stats.distanceKm));
            addEcoRow(root, "⏱  Duration",    formatDuration(stats.durationSec));
            addEcoRow(root, "💤  Idle Time",   formatDuration(stats.idleSec)
                    + String.format(Locale.US, "  (%.0f%% of trip)", stats.idlePct));
            addEcoRow(root, "⚡  Avg Speed",   String.format(Locale.US, "%.0f km/h", stats.avgSpeedKmh));
            addEcoRow(root, "🔝  Max Speed",   String.format(Locale.US, "%.0f km/h", stats.maxSpeedKmh));
            addPopupDivider(root);

            // Score breakdown
            addPopupSection(root, "Score Breakdown");
            addScoreBar(root, "Speed Zone  (35%)",  stats.zoneScore,   0xFF66DD88);
            addScoreBar(root, "Smoothness  (25%)",  stats.smoothScore, 0xFF66AAFF);
            addScoreBar(root, "Avg Speed   (20%)",  stats.effScore,    0xFFFFCC44);
            addScoreBar(root, "Idle Penalty (15%)", stats.idleScore,   0xFFFF9944);
            addScoreBar(root, "Speed Cap    (5%)",  stats.bonusScore,  0xFFCC88FF);
            addPopupDivider(root);

            // Tip
            String tip = buildTip(stats);
            if (!tip.isEmpty()) {
                addPopupSection(root, "💡 Tip");
                addPopupSub(root, tip);
            }

            // Reset button
            Button resetBtn = new Button(this);
            resetBtn.setText("Reset Trip");
            resetBtn.setTextColor(0xFFFFFFFF);
            resetBtn.setBackgroundColor(0x33FFFFFF);
            resetBtn.setOnClickListener(bv -> {
                ecoScore.reset();
                ecoScoreLabel.setText("-- 🌿");
                ecoScoreLabel.setTextColor(0xAAFFFFFF);
                dialog.dismiss();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(16), 0, 0);
            resetBtn.setLayoutParams(lp);
            root.addView(resetBtn);
        }

        popupWindow(dialog, 0.65f);
        dialog.show();
    }

    private void addEcoRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        TextView lv = new TextView(this);
        lv.setText(label);
        lv.setTextColor(0xAAFFFFFF);
        lv.setTextSize(16f);
        lv.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(lv);

        TextView rv = new TextView(this);
        rv.setText(value);
        rv.setTextColor(0xFFFFFFFF);
        rv.setTextSize(16f);
        rv.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(rv);
        parent.addView(row);
    }

    private void addScoreBar(LinearLayout parent, String label, float componentScore, int barColor) {
        TextView lv = new TextView(this);
        lv.setText(label + "   " + Math.round(componentScore) + " / 100");
        lv.setTextColor(0xCCFFFFFF);
        lv.setTextSize(14f);
        lv.setPadding(0, dp(6), 0, dp(2));
        parent.addView(lv);

        // Bar track
        android.widget.FrameLayout track = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
        trackLp.setMargins(0, 0, 0, dp(6));
        track.setLayoutParams(trackLp);
        android.graphics.drawable.GradientDrawable trackBg = new android.graphics.drawable.GradientDrawable();
        trackBg.setColor(0x33FFFFFF);
        trackBg.setCornerRadius(dp(4));
        track.setBackground(trackBg);

        // Filled portion
        View fill = new View(this);
        float fraction = Math.max(0, Math.min(100, componentScore)) / 100f;
        android.widget.FrameLayout.LayoutParams fillLp = new android.widget.FrameLayout.LayoutParams(
                0, android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        fill.setLayoutParams(fillLp);
        android.graphics.drawable.GradientDrawable fillBg = new android.graphics.drawable.GradientDrawable();
        fillBg.setColor(barColor);
        fillBg.setCornerRadius(dp(4));
        fill.setBackground(fillBg);
        track.addView(fill);
        parent.addView(track);

        // Animate bar width after layout
        track.post(() -> {
            int totalW = track.getWidth();
            android.widget.FrameLayout.LayoutParams lp2 =
                    (android.widget.FrameLayout.LayoutParams) fill.getLayoutParams();
            lp2.width = (int)(totalW * fraction);
            fill.setLayoutParams(lp2);
        });
    }

    private String buildTip(EcoScoreManager.TripStats s) {
        if (s.idlePct > 20) return "Reduce engine idling — you idled "
                + String.format(Locale.US, "%.0f%%", s.idlePct) + " of your trip.";
        if (s.smoothScore < 50) return "Smoother acceleration and braking improves fuel economy.";
        if (s.avgSpeedKmh > 100) return "Slow down a little — fuel efficiency drops above 90 km/h.";
        if (s.avgSpeedKmh < 30 && s.distanceKm > 1) return "Short, slow trips use more fuel per km. Combine errands where possible.";
        if (s.bonusScore < 80) return "Avoid exceeding 130 km/h — fuel consumption rises sharply at high speed.";
        return "";
    }

    private static String formatDuration(long totalSec) {
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return String.format(Locale.US, "%dh %02dm", h, m);
        if (m > 0) return String.format(Locale.US, "%dm %02ds", m, s);
        return totalSec + "s";
    }

    private void launchVoiceAssistant() {
        // Launch Gemini directly. If Gemini is set as the default digital assistant
        // (Settings → Apps → Default apps → Digital assistant app → Gemini),
        // it opens in voice overlay mode. Otherwise it opens the Gemini chat UI.

        // 1. Open Gemini app (com.google.android.apps.bard) with mic/voice extras.
        try {
            Intent gemini = new Intent(Intent.ACTION_MAIN);
            gemini.setComponent(new android.content.ComponentName(
                    "com.google.android.apps.bard",
                    "com.google.android.apps.bard.shellapp.BardEntryPointActivity"));
            gemini.addCategory(Intent.CATEGORY_LAUNCHER);
            gemini.putExtra("open_mic", true);
            gemini.putExtra("autostart_mic", true);
            gemini.putExtra("start_voice_input", true);
            gemini.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(gemini);
            return;
        } catch (Exception ignored) { }

        // 2. Fallback: use ACTION_ASSIST (works correctly if Gemini is the default assistant).
        try {
            Intent assist = new Intent(Intent.ACTION_ASSIST);
            assist.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(assist);
        } catch (Exception ignored) { }
    }

    private void cycleRatio() {
        if (cfg.panelCount != 2) return;
        splitRatioState = (splitRatioState + 1) % SPLIT_PRESETS.length;
        float newRatio = SPLIT_PRESETS[splitRatioState];
        cfg.splitRatio = newRatio;
        layoutPending = true;
        Config.save(this, cfg);
        splitPanel.setRatios(newRatio, cfg.splitRatio2);
        handler.removeCallbacks(relaunchPanelsRunnable);
        handler.postDelayed(relaunchPanelsRunnable, 250);
    }

    /**
     * Stops and kills a media app before replacing it in the media panel.
     * Sends a stop transport command first so the app can tear down its foreground service,
     * then kills background processes (requires KILL_BACKGROUND_PROCESSES permission).
     */
    /**
     * Schedules repeated attempts to send a PLAY command to the newly launched media app.
     * Media apps take 2-5s to register their MediaSession, so we retry several times.
     */
    private void scheduleAutoPlay() {
        autoPlayAttempts = 0;
        handler.removeCallbacks(autoPlayRunnable);
        handler.postDelayed(autoPlayRunnable, AUTO_PLAY_DELAYS_MS[0]);
    }

    private final Runnable autoPlayRunnable = new Runnable() {
        @Override public void run() {
            if (mediaCtrl == null) return;
            // Refresh session list so we pick up the newly launched app's session
            ComponentName cn = new ComponentName(CarActivity.this, MediaListenerService.class);
            mediaCtrl.start(cn);
            if (mediaCtrl.isPlaying()) return; // already playing — done
            mediaCtrl.play();
            autoPlayAttempts++;
            if (autoPlayAttempts < AUTO_PLAY_MAX_ATTEMPTS && !mediaCtrl.isPlaying()) {
                handler.postDelayed(this, AUTO_PLAY_DELAYS_MS[
                        Math.min(autoPlayAttempts, AUTO_PLAY_DELAYS_MS.length - 1)]);
            }
        }
    };

    private void killMediaApp(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        // Send STOP transport command so the app can teardown its foreground service
        if (mediaCtrl != null) {
            try { mediaCtrl.stopPlayback(); } catch (Exception ignored) { }
        }
        android.app.ActivityManager am =
                (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am != null) am.killBackgroundProcesses(pkg);
    }

    private void checkSpeedAlert(float speedKmh) {
        if (cfg.speedLimitKmh <= 0) { wasOverSpeed = false; return; }
        boolean over = speedKmh > cfg.speedLimitKmh;
        long now = System.currentTimeMillis();
        if (over) {
            if (!wasOverSpeed || now - lastBeepMs >= BEEP_INTERVAL_MS) {
                playSpeedAlert();
                lastBeepMs = now;
            }
            wasOverSpeed = true;
        } else {
            wasOverSpeed = false;
        }
    }

    private void playSpeedAlert() {
        try {
            if (toneGen == null)
                toneGen = new android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 90);
            toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 500);
        } catch (Exception e) {
            android.util.Log.w("GlassAuto", "Speed alert tone failed: " + e.getMessage());
        }
    }

    /** Returns the index of the preset in SPLIT_PRESETS closest to the given ratio. */
    private int closestPresetIdx(float ratio) {
        int best = 0;
        float bestDiff = Math.abs(ratio - SPLIT_PRESETS[0]);
        for (int i = 1; i < SPLIT_PRESETS.length; i++) {
            float diff = Math.abs(ratio - SPLIT_PRESETS[i]);
            if (diff < bestDiff) { bestDiff = diff; best = i; }
        }
        return best;
    }

    /**
     * Checks if Android freeform window mode is enabled. If not, attempts to enable it
     * using WRITE_SECURE_SETTINGS (requires ADB grant). Shows a dialog with instructions
     * if freeform remains disabled so the user knows why panels appear blank.
     */
    private void checkAndEnableFreeform() {
        try {
            int freeform = android.provider.Settings.Global.getInt(
                    getContentResolver(), "enable_freeform_support", 0);
            if (freeform == 1) return; // already enabled

            // Try to enable it programmatically (requires WRITE_SECURE_SETTINGS)
            boolean wrote = false;
            try {
                android.provider.Settings.Global.putInt(
                        getContentResolver(), "enable_freeform_support", 1);
                android.provider.Settings.Global.putInt(
                        getContentResolver(), "force_resizable_activities", 1);
                wrote = true;
            } catch (SecurityException ignored) { }

            if (wrote) {
                android.widget.Toast.makeText(this,
                        "Freeform windows enabled — please restart the app",
                        android.widget.Toast.LENGTH_LONG).show();
            } else {
                showFreeformSetupDialog();
            }
        } catch (Exception e) {
            // Silently ignore if we can't check (e.g. restricted ROM)
        }
    }

    private void showFreeformSetupDialog() {
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar);
        LinearLayout root = buildPopupRoot();
        d.setContentView(root);

        addPopupTitle(root, "⚠️  Freeform Windows Disabled");
        addPopupSub(root, "The split-panel layout requires Android freeform\n" +
                "window mode. Run these ADB commands once, then restart the app:");

        String cmds = "adb shell pm grant com.glass.auto \\\n" +
                "  android.permission.WRITE_SECURE_SETTINGS\n\n" +
                "adb shell settings put global \\\n" +
                "  enable_freeform_support 1\n\n" +
                "adb shell settings put global \\\n" +
                "  force_resizable_activities 1";

        TextView tv = new TextView(this);
        tv.setText(cmds);
        tv.setTextColor(0xFFB0E0B0);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setPadding(32, 24, 32, 24);
        tv.setBackgroundColor(0xFF1A1A1A);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(16, 8, 16, 16);
        root.addView(tv, lp);

        addPopupSub(root, "Or connect via USB to this device and run the commands above.");

        Button btn = new Button(this);
        btn.setText("OK, I'll do it later");
        btn.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        bp.setMargins(0, 16, 0, 16);
        root.addView(btn, bp);

        popupWindow(d, 0.85f);
        d.show();
    }

    private void setBuiltinMediaVisible(boolean visible) {
        int vis = visible ? View.VISIBLE : View.GONE;
        findViewById(R.id.now_playing_row).setVisibility(vis);
        progress.setVisibility(vis);
        findViewById(R.id.lyrics_scroll).setVisibility(vis);
    }

    private void launchPanel3() {
        lastOwnLaunchMs = android.os.SystemClock.uptimeMillis();
        if (cfg.panel3Package.isEmpty()) {
            panel3Hint.setVisibility(View.VISIBLE);
            return;
        }
        panel3Hint.setVisibility(View.GONE);
        int[] loc = new int[2];
        panel3Container.getLocationOnScreen(loc);
        Rect bounds = new Rect(loc[0], loc[1],
                loc[0] + panel3Container.getWidth(), loc[1] + panel3Container.getHeight());
        FreeformLauncher.launch(this, cfg.panel3Package, bounds, true);
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
        ecoScore = new EcoScoreManager();
        ecoScore.setListener(stats -> {
            String label = stats.score + " " + stats.gradeEmoji;
            ecoScoreLabel.setText(label);
            // Brighten colour based on grade
            int color = stats.score >= 75 ? 0xFF66DD88
                      : stats.score >= 60 ? 0xFFFFCC44
                      : 0xFFFF6655;
            ecoScoreLabel.setTextColor(color);
        });

        speedMonitor.setListener(speedKmh -> {
            String val = cfg.speedUnit.equals("mph")
                    ? String.valueOf((int) (speedKmh * 0.621371f))
                    : String.valueOf((int) speedKmh);
            speedLabel.setText(val);
            ecoScore.onSpeed(speedKmh);
            checkSpeedAlert(speedKmh);
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

    /**
     * Settings opens as its own freeform window over the panel area. A normal fullscreen
     * task would sit behind the freeform panel apps and be unreachable.
     */
    private void openSettings() {
        settingsOpen = true;
        configDirty = true;
        handler.removeCallbacks(bringToFrontRunnable);
        Intent i = new Intent(this, CarSettingsActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
        opts.setLaunchBounds(contentBounds());
        positionResizeHandles();
        startActivity(i, opts.toBundle());
    }

    /** Called by CarSettingsActivity when it closes; CarActivity may not get onResume because it stays visible behind the panels. */
    void onSettingsClosed() {
        settingsOpen = false;
        applyConfigChanges();
        positionResizeHandles();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyConfigChanges();

        ComponentName cn = new ComponentName(this, MediaListenerService.class);
        mediaCtrl.start(cn);
        speedMonitor.start();
        location.start();
        handler.removeCallbacks(tickRunnable);
        handler.removeCallbacks(prayerTickRunnable);
        handler.post(tickRunnable);
        handler.post(prayerTickRunnable);
    }

    private void applyConfigChanges() {
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
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(relaunchPanelsRunnable);
        // Clock, speed, location and media tracking deliberately keep running while paused:
        // the panels are other apps' windows that cover this activity, but the dock and
        // status bar overlays (driven by these) must stay live. Stopped in onDestroy.
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
        if (instance == this) instance = null;
        handler.removeCallbacks(bringToFrontRunnable);   // fix #12
        handler.removeCallbacks(tickRunnable);
        handler.removeCallbacks(prayerTickRunnable);
        handler.removeCallbacks(relaunchPanelsRunnable);
        handler.removeCallbacks(autoPlayRunnable);
        if (lyricsProvider != null) lyricsProvider.cancel();
        if (toneGen != null) { toneGen.release(); toneGen = null; }
        if (location != null) location.stop();
        if (speedMonitor != null) speedMonitor.stop();
        if (mediaCtrl != null) mediaCtrl.stop();
        dismissAppDrawer();
        for (int i = 0; i < resizeHandles.length; i++) {
            if (wm != null && resizeHandles[i] != null) {
                try { wm.removeView(resizeHandles[i]); } catch (Exception ignored) {}
            }
        }
        if (wm != null && statusBarOverlayView != null) {
            try { wm.removeView(statusBarOverlayView); } catch (Exception ignored) {}
        }
        if (wm != null && dockOverlay != null) {
            try { wm.removeView(dockOverlay); } catch (Exception ignored) {}
        }
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

        dialog.setCanceledOnTouchOutside(true);

        Window w = dialog.getWindow();
        w.setBackgroundDrawableResource(android.R.color.transparent);
        w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        w.setLayout(widthPx, WindowManager.LayoutParams.WRAP_CONTENT);
        w.setGravity(Gravity.CENTER);
        // Allow touches outside the dialog bounds to reach the dismiss handler
        w.addFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
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
