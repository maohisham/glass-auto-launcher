package com.glass.auto;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-width glass dock drawn entirely in Canvas for performance.
 *
 * Layout (left → right): [Prayer 358] | [Icons (flex)] | [Weather 134] | [Clock 186] | [Drawer 58]  (units = dock height / 64)
 * Every text size is a fraction of the dock height so the content scales when the dock is resized.
 *
 * All data is pushed in via setters. onDraw() does zero allocation — all strings
 * and computations are pre-cached in the setters or in updatePaintSizes().
 */
public class DockView extends View {

    public interface LaunchListener { void onLaunch(String packageName); }
    public interface OnSectionTapListener {
        void onWeatherTap();
        void onPrayerTap();
    }
    public interface OnAppDrawerTapListener { void onAppDrawerTap(); }
    /** which: 0 = Back, 1 = Home, 2 = Recents */
    public interface OnNavTapListener { void onNavTap(int which); }

    // --- Paints (all allocated once, sized in updatePaintSizes()) ---
    private final Paint bgPaint         = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint        = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selPaint        = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clockPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint datePaint       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint      = new Paint(Paint.ANTI_ALIAS_FLAG); // 18dp — prayer time
    private final Paint valuePaintLarge = new Paint(Paint.ANTI_ALIAS_FLAG); // 22dp — weather temp
    private final Paint dimPaint        = new Paint(Paint.ANTI_ALIAS_FLAG); // 16dp — next prayer
    private final Paint dimPaintArrow   = new Paint(Paint.ANTI_ALIAS_FLAG); // 20dp — › arrow
    private final Paint infoPaint       = new Paint(Paint.ANTI_ALIAS_FLAG); // 10dp — prayer info line
    private final Paint cityPaint       = new Paint(Paint.ANTI_ALIAS_FLAG); // 13dp — weather city
    private final Paint cdPaint         = new Paint(Paint.ANTI_ALIAS_FLAG); // 10dp — countdown
    private final Paint dotPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint timePaint   = new Paint(Paint.ANTI_ALIAS_FLAG); // prayer time
    private final Paint countPaint  = new Paint(Paint.ANTI_ALIAS_FLAG); // countdown
    private final Paint pillFill    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillStroke  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final int COL_TAG   = 0xFFFFC857; // gold
    private static final int COL_TIME  = 0xFF5CE1E6; // cyan
    private static final int COL_COUNT = 0xFF5CF29A; // green
    private static final int COL_INFO  = 0xFF8FE9D8; // light teal
    private final Paint navPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path navPath = new android.graphics.Path();
    private final RectF selRect    = new RectF();
    private final Rect  textBounds = new Rect();

    private final float rawDp;
    private       float dp; // 1 unit = dock height / 64 (set in onSizeChanged)

    // --- Raw state (kept for setters/popups) ---
    private String clockText        = "00:00";
    private String dateText         = "Mon, 1 Jan";
    private String weatherEmoji     = "–";
    private String weatherTemp      = "–°";
    private String weatherCity      = "";
    private String prayerCurrent    = "";
    private String prayerCurrentTime = "";
    private String prayerNext       = "";
    private String prayerNextTime   = "";
    private String prayerLocation   = "";
    private String prayerHijri      = "";

    // --- Pre-computed draw strings (no allocation in onDraw) ---
    private String emojiTempStr  = "– –°";   // updated in setWeather()
    private String infoLineStr   = "";        // updated in setPrayerTimes()
    private String currentTime12 = "";        // pre-converted 12h
    private String nextTime12    = "";        // pre-converted 12h
    private String countdownStr  = "";        // pre-computed, updated in setPrayerTimes()
    private boolean hasCity      = false;
    private boolean hasInfo      = false;

    private List<DockItem> items = new ArrayList<>();
    private int selectedIndex    = -1;
    private LaunchListener        launchListener;
    private OnSectionTapListener  sectionTapListener;
    private OnAppDrawerTapListener drawerTapListener;
    private OnNavTapListener      navTapListener;
    private boolean               navButtons;
    private boolean               compact;

    public static final int POS_BOTTOM = 0, POS_TOP = 1, POS_LEFT = 2, POS_RIGHT = 3;
    private int position = POS_BOTTOM;
    private int topInset; // px reserved at the top of the view (the status bar sits over it) for top/left/right docks

    private boolean vertical() { return position >= POS_LEFT; }
    private float contentH() { return Math.max(0, getHeight() - topInset); }

    /** Moves the dock to an edge. {@code topInsetPx} is the status bar height to keep clear at the top of the view. */
    public void setPosition(int pos, int topInsetPx) {
        if (position == pos && topInset == topInsetPx) return;
        position = pos;
        topInset = topInsetPx;
        updateUnits();
        invalidate();
    }

    private void updateUnits() {
        float base = vertical() ? getWidth() : contentH();
        dp = base > 0 ? base / 64f : rawDp;
        updatePaintSizes();
    }

    // Section widths in dp
    private static final float CLOCK_W    = 250f;
    private static final float WEATHER_W  = 134f;
    private static final float PRAYER_W   = 358f;
    private static final float DRAWER_W   = 58f;
    private static final float NAV_W      = 180f; // Back / Home / Recents, only when the dock covers the system nav bar
    private static final int   MAX_ICONS  = 6;
    private static final float ICON_SIZE  = 40f;
    private static final float ICON_MAX_H = 0.62f; // icon height as a fraction of the dock height
    private static final float SEL_RADIUS = 8f;

    public DockView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        rawDp = ctx.getResources().getDisplayMetrics().density;
        dp    = rawDp;

        bgPaint.setColor(0x00000000);
        bgPaint.setStyle(Paint.Style.FILL);

        rimPaint.setColor(0x22FFFFFF);
        rimPaint.setStyle(Paint.Style.STROKE);

        selPaint.setColor(0x33FFFFFF);
        selPaint.setStyle(Paint.Style.FILL);

        clockPaint.setColor(0xFFFFFFFF);
        clockPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        datePaint.setColor(0xFFFFFFFF);
        datePaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));

        valuePaint.setColor(0xFFFFFFFF);
        valuePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        timePaint.setColor(COL_TIME);
        timePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        countPaint.setColor(COL_COUNT);
        countPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        pillFill.setColor(0x3352E08A);
        pillFill.setStyle(Paint.Style.FILL);
        pillStroke.setColor(COL_COUNT);
        pillStroke.setStyle(Paint.Style.STROKE);
        pillStroke.setStrokeWidth(1.5f * rawDp);

        valuePaintLarge.setColor(0xFFFFFFFF);
        valuePaintLarge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        labelPaint.setColor(0xFFFFFFFF);

        dimPaint.setColor(0xFFFFFFFF);
        dimPaintArrow.setColor(0xFFFFFFFF);

        infoPaint.setColor(0xFFFFFFFF);
        cityPaint.setColor(0xFFFFFFFF);
        cdPaint.setColor(0xFFFFFFFF);

        dotPaint.setColor(0xFFFFFFFF);
        dotPaint.setStyle(Paint.Style.FILL);

        navPaint.setColor(0xFFFFFFFF);
        navPaint.setStyle(Paint.Style.STROKE);
        navPaint.setStrokeJoin(Paint.Join.ROUND);

        updatePaintSizes();
        setWillNotDraw(false);
    }

    /** Recalculate all paint text sizes after dp changes. Called from constructor + onSizeChanged. */
    private void updatePaintSizes() {
        rimPaint.setStrokeWidth(1f * dp);
        clockPaint.setTextSize(28f * dp);
        datePaint.setTextSize(13f * dp);
        valuePaint.setTextSize(18f * dp);
        valuePaintLarge.setTextSize(22f * dp);
        labelPaint.setTextSize(12f * dp);
        dimPaint.setTextSize(16f * dp);
        dimPaintArrow.setTextSize(20f * dp);
        infoPaint.setTextSize(10f * dp);
        cityPaint.setTextSize(13f * dp);
        cdPaint.setTextSize(10f * dp);
    }

    /**
     * Kept for the "UI Scale" setting. Section widths and text are now sized from the dock
     * height (1 unit = height / 64), so they always fit together; the setting has no effect here.
     */
    public void configureScale(float cfgScale) {
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateUnits();
        updatePaintSizes();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Pre-warm the color emoji font so the first onDraw() doesn't stutter.
        post(() -> {
            Bitmap bm = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            Canvas warmup = new Canvas(bm);
            warmup.drawText("☀🌤🌧⛈🌫❄🌦🌨⛅🕌", 0, 10, valuePaintLarge);
            bm.recycle();
        });
    }

    // --- Setters ---

    /** Only redraws if the displayed text actually changed. */
    public void setClock(String time, String date) {
        if (time.equals(clockText) && date.equals(dateText)) return;
        clockText = time;
        dateText  = date;
        invalidate();
    }

    public void setWeather(String emoji, String tempC, String city) {
        weatherEmoji = emoji;
        weatherTemp  = tempC;
        weatherCity  = city != null ? city : "";
        hasCity      = !weatherCity.isEmpty();
        emojiTempStr = weatherEmoji + " " + weatherTemp; // pre-build, no alloc in onDraw
        invalidate();
    }

    public void setPrayerTimes(String current, String currentTime,
                               String next, String nextTime,
                               String location, String hijriDate) {
        prayerCurrent     = current;
        prayerCurrentTime = currentTime;
        prayerNext        = next;
        prayerNextTime    = nextTime;
        prayerLocation    = location != null ? location : "";
        prayerHijri       = hijriDate != null ? hijriDate : "";
        // Pre-compute everything so onDraw() does zero work beyond drawing
        currentTime12 = to12h(prayerCurrentTime);
        nextTime12    = to12h(prayerNextTime);
        countdownStr  = computeCountdown(prayerNextTime);
        hasInfo       = !prayerLocation.isEmpty() || !prayerHijri.isEmpty();
        rebuildInfoLine();
        invalidate();
    }

    private void rebuildInfoLine() {
        if (!hasInfo) { infoLineStr = ""; return; }
        String line = "";
        if (!prayerLocation.isEmpty()) {
            String loc = prayerLocation;
            int slash = loc.indexOf(" / ");
            if (slash > 0) loc = loc.substring(0, slash);
            line = "🕌 " + loc;
        }
        if (!prayerHijri.isEmpty())
            line += (line.isEmpty() ? "" : " · ") + prayerHijri;
        infoLineStr = line;
    }

    public void setApps(List<String> packageNames, PackageManager pm) {
        items.clear();
        for (String pkg : packageNames) {
            if (items.size() >= MAX_ICONS) break;
            try {
                DockItem item = new DockItem();
                item.packageName = pkg;
                item.icon = pm.getApplicationIcon(pkg);
                items.add(item);
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        invalidate();
    }

    public void setLaunchListener(LaunchListener l) { launchListener = l; }
    public void setOnSectionTapListener(OnSectionTapListener l) { sectionTapListener = l; }
    public void setOnAppDrawerTapListener(OnAppDrawerTapListener l) { drawerTapListener = l; }
    public void setOnNavTapListener(OnNavTapListener l) { navTapListener = l; }

    /** Shows Back/Home/Recents at the right end (used when the dock sits over the system navigation bar). */
    public void setNavButtons(boolean on) {
        if (navButtons == on) return;
        navButtons = on;
        invalidate();
    }

    // --- Drawing (zero allocations) ---

    // Section geometry shared by onDraw() and onTouchEvent(); no allocation.
    private final float[] iconLeft = new float[MAX_ICONS];
    private float iconSz, prayerW, weatherW, clockW, drawerW, navW, xClock, xWeather, xDrawer, xNav;

    /**
     * Prayer on the left, weather/drawer/nav on the right, the clock at the horizontal centre.
     * The app icons fill the space on either side of the clock; the clock slides off-centre
     * (or the icons shrink) only when the screen is too narrow to keep both.
     */
    private void computeLayout() {
        float w = getWidth(), h = contentH();
        prayerW  = PRAYER_W  * dp;
        weatherW = WEATHER_W * dp;
        clockW   = CLOCK_W   * dp;
        drawerW  = DRAWER_W  * dp;
        navW     = navButtons ? NAV_W * dp : 0f;
        float rightFixed = weatherW + drawerW + navW;
        int n = items.size(), nl = (n + 1) / 2, nr = n - nl;
        iconSz = iconPx(h);
        float step = iconSz * 1.35f;
        float minLeft = prayerW + nl * step;
        float maxLeft = w - rightFixed - clockW - nr * step;
        if (maxLeft < minLeft) {
            float avail = Math.max(0f, w - prayerW - rightFixed - clockW);
            step = n > 0 ? avail / n : step;
            iconSz = Math.min(iconSz, step * 0.8f);
            minLeft = maxLeft = prayerW + nl * step;
        }
        xClock   = Math.max(minLeft, Math.min(maxLeft, (w - clockW) / 2f));
        xWeather = w - rightFixed;
        xDrawer  = xWeather + weatherW;
        xNav     = xDrawer + drawerW;
        placeIcons(0, nl, prayerW, xClock);
        placeIcons(nl, nr, xClock + clockW, xWeather);
    }

    private void placeIcons(int first, int count, float x0, float x1) {
        if (count <= 0) return;
        float gap = (x1 - x0 - count * iconSz) / (count + 1f);
        for (int i = 0; i < count; i++) iconLeft[first + i] = x0 + gap * (i + 1) + iconSz * i;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.translate(0, topInset);
        if (vertical()) drawVertical(canvas, getWidth(), (int) contentH());
        else drawHorizontal(canvas, getWidth(), (int) contentH());
        canvas.restore();
    }

    private void drawHorizontal(Canvas canvas, int w, int h) {
        float cy = h / 2f;
        computeLayout();

        float m = h * 0.14f;
        float rimY = position == POS_TOP ? h - 1f : 1f;
        canvas.drawLine(0, rimY, w, rimY, rimPaint);
        canvas.drawLine(prayerW,         m, prayerW,         h - m, rimPaint);
        canvas.drawLine(xClock,          m, xClock,          h - m, rimPaint);
        canvas.drawLine(xClock + clockW, m, xClock + clockW, h - m, rimPaint);
        canvas.drawLine(xWeather,        m, xWeather,        h - m, rimPaint);
        canvas.drawLine(xDrawer,         m, xDrawer,         h - m, rimPaint);
        if (navButtons) canvas.drawLine(xNav, m, xNav, h - m, rimPaint);

        drawPrayer(canvas, 0, prayerW, h);
        drawIcons(canvas, cy);
        drawClock(canvas, xClock, clockW, h);
        drawWeather(canvas, xWeather, weatherW, h);
        drawAppDrawer(canvas, xDrawer, drawerW, cy);
        if (navButtons) drawNav(canvas, xNav, navW, h);
    }

    /** Back (◀), Home (●) and Recents (■) drawn as outlines, for when the dock covers the system navigation bar. */
    private void drawNav(Canvas c, float x, float w, float h) {
        float cell = w / 3f, s = h * 0.17f, cy = h / 2f;
        navPaint.setStrokeWidth(h * 0.055f);
        float bx = x + cell * 0.5f, hx = x + cell * 1.5f, rx = x + cell * 2.5f;
        navPath.reset();
        navPath.moveTo(bx + s * 0.8f, cy - s);
        navPath.lineTo(bx - s * 0.8f, cy);
        navPath.lineTo(bx + s * 0.8f, cy + s);
        navPath.close();
        c.drawPath(navPath, navPaint);
        c.drawCircle(hx, cy, s, navPaint);
        selRect.set(rx - s, cy - s, rx + s, cy + s);
        c.drawRoundRect(selRect, s * 0.3f, s * 0.3f, navPaint);
    }

    private float iconPx(float h) {
        return Math.min(ICON_SIZE * rawDp, h * (compact ? 0.78f : ICON_MAX_H));
    }

    /** Slim two-row layout for a dock that has to sit over the apps (no system bar to hide behind). */
    public void setCompact(boolean on) {
        if (compact == on) return;
        compact = on;
        invalidate();
    }

    private void drawClockCompact(Canvas c, float x, float w, float h) {
        float pad = 6 * dp;
        fitText(clockPaint, clockText, w - 2 * pad, h * 0.58f);
        c.drawText(clockText, x + (w - clockPaint.measureText(clockText)) / 2f, h * 0.62f, clockPaint);
        fitText(datePaint, dateText, w - 2 * pad, h * 0.29f);
        c.drawText(dateText, x + (w - datePaint.measureText(dateText)) / 2f, h * 0.95f, datePaint);
    }

    private void drawWeatherCompact(Canvas c, float x, float w, float h) {
        float pad = 6 * dp;
        fitText(valuePaintLarge, emojiTempStr, w - 2 * pad, h * 0.48f);
        c.drawText(emojiTempStr, x + (w - valuePaintLarge.measureText(emojiTempStr)) / 2f,
                hasCity ? h * 0.52f : h * 0.68f, valuePaintLarge);
        if (hasCity) {
            String city = weatherCity;
            cityPaint.setTextSize(h * 0.27f);
            while (cityPaint.measureText(city) > w - 2 * pad && city.length() > 3)
                city = city.substring(0, city.length() - 1);
            if (!city.equals(weatherCity)) city = city + "…";
            c.drawText(city, x + (w - cityPaint.measureText(city)) / 2f, h * 0.93f, cityPaint);
        }
    }

    /** Next prayer name over its time, with the countdown in a pill; the current prayer is left out. */
    private void drawPrayerCompact(Canvas c, float x, float w, float h) {
        float pad = 8 * dp;
        if (prayerNext.isEmpty()) {
            dimPaint.setTextSize(h * 0.30f);
            c.drawText("Prayer loading…", x + pad, h * 0.62f, dimPaint);
            return;
        }
        float blockW = w * 0.56f - pad, cx = x + pad + blockW / 2f;
        fitText(valuePaint, prayerNext, blockW, h * 0.42f);
        c.drawText(prayerNext, cx - valuePaint.measureText(prayerNext) / 2f, h * 0.47f, valuePaint);
        if (!nextTime12.isEmpty()) {
            timePaint.setColor(COL_TIME);
            fitText(timePaint, nextTime12, blockW, h * 0.36f);
            c.drawText(nextTime12, cx - timePaint.measureText(nextTime12) / 2f, h * 0.93f, timePaint);
        }
        if (!countdownStr.isEmpty()) {
            float px0 = x + w * 0.58f, px1 = x + w - pad;
            selRect.set(px0, h * 0.14f, px1, h * 0.86f);
            c.drawRoundRect(selRect, 8 * dp, 8 * dp, pillFill);
            c.drawRoundRect(selRect, 8 * dp, 8 * dp, pillStroke);
            float inner = px1 - px0 - 10 * dp;
            fitText(countPaint, countdownStr, inner * 0.78f, h * 0.38f);
            float cw = countPaint.measureText(countdownStr);
            cdPaint.setColor(COL_TAG);
            cdPaint.setTextSize(countPaint.getTextSize() * 0.62f);
            float lw = cdPaint.measureText("in ");
            float sx = (px0 + px1) / 2f - (lw + cw) / 2f;
            c.drawText("in ", sx, h * 0.64f, cdPaint);
            c.drawText(countdownStr, sx + lw, h * 0.64f, countPaint);
        }
    }

    /** Time on top, weekday/date in the middle, Hijri date at the bottom. */
    private void drawClock(Canvas c, float x, float w, float h) {
        if (compact) { drawClockCompact(c, x, w, h); return; }
        float pad = 6 * dp;
        fitText(clockPaint, clockText, w - 2 * pad, h * 0.47f);
        c.drawText(clockText, x + (w - clockPaint.measureText(clockText)) / 2f, h * 0.50f, clockPaint);

        fitText(datePaint, dateText, w - 2 * pad, h * 0.23f);
        c.drawText(dateText, x + (w - datePaint.measureText(dateText)) / 2f, h * 0.76f, datePaint);

        if (!prayerHijri.isEmpty()) {
            infoPaint.setColor(COL_INFO);
            fitText(infoPaint, prayerHijri, w - 2 * pad, h * 0.19f);
            c.drawText(prayerHijri, x + (w - infoPaint.measureText(prayerHijri)) / 2f, h * 0.95f, infoPaint);
        }
    }

    private void drawIcons(Canvas c, float cy) {
        if (items.isEmpty()) return;
        int   n      = items.size();
        float iconPx = iconSz;

        for (int i = 0; i < n; i++) {
            float left = iconLeft[i];
            float top  = cy - iconPx / 2f;
            if (i == selectedIndex) {
                selRect.set(left - 6 * rawDp, top - 6 * rawDp,
                        left + iconPx + 6 * rawDp, top + iconPx + 6 * rawDp);
                c.drawRoundRect(selRect, SEL_RADIUS * rawDp, SEL_RADIUS * rawDp, selPaint);
            }
            Drawable icon = items.get(i).icon;
            if (icon != null) {
                icon.setBounds((int) left, (int) top,
                        (int) (left + iconPx), (int) (top + iconPx));
                icon.draw(c);
            }
        }
    }

    private void drawAppDrawer(Canvas c, float x, float w, float cy) {
        float dotR   = 2.5f * dp;
        float dotGap = 4.5f * dp;
        float step   = dotR * 2 + dotGap;
        float gridW  = 3 * step - dotGap;
        float gridH  = gridW;
        float startX = x + (w - gridW) / 2f + dotR;
        float startY = cy - gridH / 2f + dotR;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                c.drawCircle(startX + col * step, startY + row * step, dotR, dotPaint);
            }
        }
    }

    private void drawWeather(Canvas c, float x, float w, float h) {
        if (compact) { drawWeatherCompact(c, x, w, h); return; }
        float pad = 6 * dp;
        fitText(valuePaintLarge, emojiTempStr, w - 2 * pad, h * 0.38f);
        c.drawText(emojiTempStr, x + (w - valuePaintLarge.measureText(emojiTempStr)) / 2f,
                hasCity ? h * 0.50f : h * 0.62f, valuePaintLarge);

        if (hasCity) {
            // Truncate to fit width (truncation is fast; no new Paint created)
            String city = weatherCity;
            cityPaint.setTextSize(h * 0.21f);
            while (cityPaint.measureText(city) > w - 2 * pad && city.length() > 3) {
                city = city.substring(0, city.length() - 1);
            }
            if (!city.equals(weatherCity)) city = city + "…";
            c.drawText(city, x + (w - cityPaint.measureText(city)) / 2f, h * 0.84f, cityPaint);
        }
    }

    /** Three columns: NOW | NEXT | countdown pill. Every row sits on the same baseline across columns. */
    private void drawPrayer(Canvas c, float x, float w, float h) {
        if (compact) { drawPrayerCompact(c, x, w, h); return; }
        float pad  = 8 * dp;
        float colW = (w - 2 * pad) / 3f;

        if (prayerCurrent.isEmpty() && prayerNext.isEmpty()) {
            dimPaint.setTextSize(h * 0.22f);
            c.drawText("Prayer loading…", x + pad, h * 0.58f, dimPaint);
            return;
        }

        // Column 1: current prayer
        float c1 = x + pad + colW / 2f;
        drawPrayerColumn(c, c1, colW - 6 * dp, "NOW",
                prayerCurrent.isEmpty() ? "—" : prayerCurrent, currentTime12,
                h * 0.29f, h * 0.24f, COL_INFO, h);
        c.drawLine(x + pad + colW, h * 0.2f, x + pad + colW, h * 0.8f, rimPaint);

        // Column 2: next prayer (larger)
        float c2 = x + pad + colW * 1.5f;
        drawPrayerColumn(c, c2, colW - 6 * dp, "NEXT",
                prayerNext.isEmpty() ? "—" : prayerNext, nextTime12,
                h * 0.35f, h * 0.29f, COL_TIME, h);

        // Column 3: countdown pill
        if (!countdownStr.isEmpty()) {
            float px0 = x + pad + colW * 2f + 4 * dp, px1 = x + w - pad;
            selRect.set(px0, h * 0.10f, px1, h * 0.90f);
            c.drawRoundRect(selRect, 10 * dp, 10 * dp, pillFill);
            c.drawRoundRect(selRect, 10 * dp, 10 * dp, pillStroke);

            float pcx = (px0 + px1) / 2f, inner = px1 - px0 - 12 * dp;
            cdPaint.setColor(COL_TAG);
            fitText(cdPaint, "NEXT IN", inner, h * 0.18f);
            c.drawText("NEXT IN", pcx - cdPaint.measureText("NEXT IN") / 2f, h * 0.35f, cdPaint);

            fitText(countPaint, countdownStr, inner, h * 0.36f);
            c.drawText(countdownStr, pcx - countPaint.measureText(countdownStr) / 2f, h * 0.78f, countPaint);
        }
    }

    /** Gold tag, white bold name, coloured time — all centred on {@code cx}. */
    private void drawPrayerColumn(Canvas c, float cx, float maxW, String tag, String name, String time,
                                  float nameSize, float timeSize, int timeColor, float h) {
        cdPaint.setColor(COL_TAG);
        fitText(cdPaint, tag, maxW, h * 0.18f);
        c.drawText(tag, cx - cdPaint.measureText(tag) / 2f, h * 0.27f, cdPaint);

        fitText(valuePaint, name, maxW, nameSize);
        c.drawText(name, cx - valuePaint.measureText(name) / 2f, h * 0.60f, valuePaint);

        if (!time.isEmpty()) {
            timePaint.setColor(timeColor);
            fitText(timePaint, time, maxW, timeSize);
            c.drawText(time, cx - timePaint.measureText(time) / 2f, h * 0.91f, timePaint);
        }
    }

    /** Sets the paint to {@code size}, shrinking it so {@code s} fits in {@code maxW}. */
    private static void fitText(Paint p, String s, float maxW, float size) {
        p.setTextSize(size);
        float mw = p.measureText(s);
        if (mw > maxW && mw > 0) p.setTextSize(size * maxW / mw);
    }

    // --- Helpers (static, no allocation) ---

    /** Compute "in Xh Ym" without Calendar.getInstance() — called from setters, not onDraw. */
    private static String computeCountdown(String t) {
        if (t == null || t.isEmpty()) return "";
        try {
            int colon = t.indexOf(':');
            if (colon < 1) return "";
            int nextH = Integer.parseInt(t.substring(0, colon).trim());
            int nextM = Integer.parseInt(t.substring(colon + 1, Math.min(colon + 3, t.length())).trim());
            java.util.Calendar now = java.util.Calendar.getInstance();
            int diff = (nextH * 60 + nextM)
                    - (now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE));
            if (diff <= 0) diff += 24 * 60;
            int h = diff / 60, m = diff % 60;
            return h > 0 ? h + "h " + m + "m" : m + "m";
        } catch (Exception e) { return ""; }
    }

    static String to12h(String t) {
        if (t == null || t.isEmpty()) return t != null ? t : "";
        try {
            int colon = t.indexOf(':');
            if (colon < 1) return t;
            int h = Integer.parseInt(t.substring(0, colon).trim());
            String min  = t.substring(colon + 1, Math.min(colon + 3, t.length()));
            String ampm = h < 12 ? "AM" : "PM";
            h = h % 12;
            if (h == 0) h = 12;
            return h + ":" + min + " " + ampm;
        } catch (Exception e) { return t; }
    }

    // --- Vertical dock (left / right edge) ---

    private float vUnit, vClockY, vClockH, vPrayerY, vPrayerH, vWeatherY, vWeatherH,
            vIconsY0, vIconsY1, vDrawerY, vDrawerH, vNavY, vNavH, vIconSz;
    private boolean vPrayerFull;
    private final float[] iconTopV = new float[MAX_ICONS];
    private final float[] iconLeftV = new float[MAX_ICONS];

    // Section heights in dock units (1 unit = dock width / 64).
    private static final float V_CLOCK = 72f, V_PRAYER = 108f, V_PRAYER_SLIM = 72f, V_WEATHER = 64f, V_DRAWER = 44f, V_NAV = 132f;

    /**
     * Sections stacked top to bottom: clock, next prayer, weather, app icons (flexible), drawer, nav.
     * On a short screen the least important parts go first (weather, then the countdown pill), and only
     * then does everything shrink — so the text stays at a readable size and the sections stay spaced.
     */
    private void computeVertical() {
        float w = getWidth(), h = contentH();
        int n = items.size();
        float u = w / 64f;
        float navUnits = navButtons ? V_NAV : 0f;
        float iconsMin = n * 46f; // px
        boolean weather = true;
        vPrayerFull = true;
        float fixed = V_CLOCK + V_PRAYER + V_WEATHER + V_DRAWER + navUnits;
        if (fixed * u + iconsMin > h) { weather = false; fixed -= V_WEATHER; }
        if (fixed * u + iconsMin > h) { vPrayerFull = false; fixed -= V_PRAYER - V_PRAYER_SLIM; }
        float s = 1f;
        if (fixed * u + iconsMin > h) s = Math.max(0.65f, (h - iconsMin) / (fixed * u));
        float uu = u * s;
        vUnit = uu;
        float y = 0;
        vClockY = y;   vClockH = V_CLOCK * uu;   y += vClockH;
        vPrayerY = y;  vPrayerH = (vPrayerFull ? V_PRAYER : V_PRAYER_SLIM) * uu;  y += vPrayerH;
        vWeatherY = y; vWeatherH = weather ? V_WEATHER * uu : 0f; y += vWeatherH;
        vIconsY0 = y;
        vIconsY1 = Math.max(y, h - (V_DRAWER + navUnits) * uu);
        vDrawerY = vIconsY1; vDrawerH = V_DRAWER * uu;
        vNavY = vDrawerY + vDrawerH; vNavH = navUnits * uu;
        float zone = vIconsY1 - vIconsY0;
        // One column of icons; two when a single column would have to squeeze them.
        int cols = 1;
        vIconSz = Math.min(ICON_SIZE * rawDp, w * 0.7f);
        if (n > 2 && n * vIconSz * 1.3f > zone) {
            cols = 2;
            vIconSz = Math.min(ICON_SIZE * rawDp, w * 0.40f);
        }
        int rows = n > 0 ? (n + cols - 1) / cols : 0;
        float step = vIconSz * 1.3f;
        if (rows > 0 && rows * step > zone) { step = zone / rows; vIconSz = Math.min(vIconSz, step * 0.85f); }
        float gap = rows > 0 ? (zone - rows * vIconSz) / (rows + 1f) : 0f;
        float colGap = (w - cols * vIconSz) / (cols + 1f);
        for (int i = 0; i < n; i++) {
            int r = i / cols, c = i % cols;
            iconTopV[i] = vIconsY0 + gap * (r + 1) + vIconSz * r;
            boolean loneLast = r == rows - 1 && n % cols != 0; // centre a last icon that has no partner
            iconLeftV[i] = loneLast ? (w - vIconSz) / 2f : colGap * (c + 1) + vIconSz * c;
        }
    }

    private void drawVertical(Canvas c, int w, int h) {
        computeVertical();
        float u = vUnit, cx = w / 2f, m = 8 * u, inner = w - 2 * m;
        float rimX = position == POS_LEFT ? w - 1f : 1f;
        c.drawLine(rimX, 0, rimX, h, rimPaint);
        c.drawLine(m, vPrayerY,  w - m, vPrayerY,  rimPaint);
        if (vWeatherH > 0) c.drawLine(m, vWeatherY, w - m, vWeatherY, rimPaint);
        c.drawLine(m, vIconsY0,  w - m, vIconsY0,  rimPaint);
        c.drawLine(m, vDrawerY,  w - m, vDrawerY,  rimPaint);
        if (navButtons) c.drawLine(m, vNavY, w - m, vNavY, rimPaint);

        // Clock
        fitText(clockPaint, clockText, inner, 20 * u);
        c.drawText(clockText, cx - clockPaint.measureText(clockText) / 2f, vClockY + 38 * u, clockPaint);
        fitText(datePaint, dateText, inner, 12 * u);
        c.drawText(dateText, cx - datePaint.measureText(dateText) / 2f, vClockY + 62 * u, datePaint);

        // Next prayer
        if (prayerNext.isEmpty()) {
            fitText(dimPaint, "Prayer…", inner, 13 * u);
            c.drawText("Prayer…", cx - dimPaint.measureText("Prayer…") / 2f, vPrayerY + vPrayerH * 0.55f, dimPaint);
        } else {
            cdPaint.setColor(COL_TAG);
            fitText(cdPaint, "NEXT", inner, 11 * u);
            c.drawText("NEXT", cx - cdPaint.measureText("NEXT") / 2f, vPrayerY + 20 * u, cdPaint);
            fitText(valuePaint, prayerNext, inner, 19 * u);
            c.drawText(prayerNext, cx - valuePaint.measureText(prayerNext) / 2f, vPrayerY + 45 * u, valuePaint);
            if (!nextTime12.isEmpty()) {
                timePaint.setColor(COL_TIME);
                fitText(timePaint, nextTime12, inner, 15 * u);
                c.drawText(nextTime12, cx - timePaint.measureText(nextTime12) / 2f, vPrayerY + 67 * u, timePaint);
            }
            if (vPrayerFull && !countdownStr.isEmpty())
                drawCountdownPill(c, m, vPrayerY + 78 * u, w - m, vPrayerY + 102 * u, 14 * u);
        }

        // Weather
        if (vWeatherH > 0) {
            fitText(valuePaintLarge, emojiTempStr, inner, 19 * u);
            c.drawText(emojiTempStr, cx - valuePaintLarge.measureText(emojiTempStr) / 2f,
                    vWeatherY + (hasCity ? 29 : 40) * u, valuePaintLarge);
            if (hasCity) {
                String city = weatherCity;
                cityPaint.setTextSize(11 * u);
                while (cityPaint.measureText(city) > inner && city.length() > 3)
                    city = city.substring(0, city.length() - 1);
                if (!city.equals(weatherCity)) city = city + "…";
                c.drawText(city, cx - cityPaint.measureText(city) / 2f, vWeatherY + 51 * u, cityPaint);
            }
        }

        // App icons
        for (int i = 0; i < items.size(); i++) {
            float left = iconLeftV[i];
            float top = iconTopV[i];
            if (i == selectedIndex) {
                selRect.set(left - 6 * rawDp, top - 6 * rawDp, left + vIconSz + 6 * rawDp, top + vIconSz + 6 * rawDp);
                c.drawRoundRect(selRect, SEL_RADIUS * rawDp, SEL_RADIUS * rawDp, selPaint);
            }
            Drawable icon = items.get(i).icon;
            if (icon != null) {
                icon.setBounds((int) left, (int) top, (int) (left + vIconSz), (int) (top + vIconSz));
                icon.draw(c);
            }
        }

        drawAppDrawer(c, 0, w, vDrawerY + vDrawerH / 2f);
        if (navButtons) drawNavVertical(c, w);
    }

    /** "in 9h 15m" in a rounded pill. */
    private void drawCountdownPill(Canvas c, float l, float t, float r, float b, float textSize) {
        float rad = (b - t) / 2f;
        selRect.set(l, t, r, b);
        c.drawRoundRect(selRect, rad, rad, pillFill);
        c.drawRoundRect(selRect, rad, rad, pillStroke);
        fitText(countPaint, countdownStr, (r - l) * 0.74f, textSize);
        float cw = countPaint.measureText(countdownStr);
        cdPaint.setColor(COL_TAG);
        cdPaint.setTextSize(countPaint.getTextSize() * 0.62f);
        float lw = cdPaint.measureText("in ");
        float sx = (l + r) / 2f - (lw + cw) / 2f;
        float base = (t + b) / 2f + countPaint.getTextSize() * 0.35f;
        c.drawText("in ", sx, base, cdPaint);
        c.drawText(countdownStr, sx + lw, base, countPaint);
    }

    private void drawNavVertical(Canvas c, int w) {
        float cell = vNavH / 3f, s = Math.min(cell * 0.28f, w * 0.17f), cx = w / 2f;
        navPaint.setStrokeWidth(Math.max(2f, s * 0.32f));
        float by = vNavY + cell * 0.5f, hy = vNavY + cell * 1.5f, ry = vNavY + cell * 2.5f;
        navPath.reset();
        navPath.moveTo(cx + s * 0.8f, by - s);
        navPath.lineTo(cx - s * 0.8f, by);
        navPath.lineTo(cx + s * 0.8f, by + s);
        navPath.close();
        c.drawPath(navPath, navPaint);
        c.drawCircle(cx, hy, s, navPaint);
        selRect.set(cx - s, ry - s, cx + s, ry + s);
        c.drawRoundRect(selRect, s * 0.3f, s * 0.3f, navPaint);
    }

    // --- Touch ---

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int   n = items.size();
        float x = ev.getX();
        boolean inPrayer, inWeather, inNav, inDrawer;
        int navIdx;
        int tapped = -1;
        if (vertical()) {
            computeVertical();
            float y = ev.getY() - topInset;
            inPrayer  = y >= vPrayerY && y < vWeatherY;
            inWeather = vWeatherH > 0 && y >= vWeatherY && y < vIconsY0;
            inNav     = navButtons && y >= vNavY;
            inDrawer  = y >= vDrawerY && !inNav;
            navIdx    = vNavH > 0 ? Math.min(2, (int) ((y - vNavY) / (vNavH / 3f))) : 0;
            for (int i = 0; i < n; i++) {
                if (y >= iconTopV[i] && y <= iconTopV[i] + vIconSz
                        && x >= iconLeftV[i] && x <= iconLeftV[i] + vIconSz) { tapped = i; break; }
            }
        } else {
            computeLayout();
            inPrayer  = x < prayerW;
            inWeather = x >= xWeather && x < xDrawer;
            inNav     = navButtons && x >= xNav;
            inDrawer  = x >= xDrawer && !inNav;
            navIdx    = navW > 0 ? Math.min(2, (int) ((x - xNav) / (navW / 3f))) : 0;
            for (int i = 0; i < n; i++) {
                if (x >= iconLeft[i] && x <= iconLeft[i] + iconSz) { tapped = i; break; }
            }
        }

        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                selectedIndex = tapped;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (tapped >= 0 && tapped == selectedIndex && launchListener != null) {
                    launchListener.onLaunch(items.get(tapped).packageName);
                } else if (inPrayer && sectionTapListener != null) {
                    sectionTapListener.onPrayerTap();
                } else if (inWeather && sectionTapListener != null) {
                    sectionTapListener.onWeatherTap();
                } else if (inDrawer && drawerTapListener != null) {
                    drawerTapListener.onAppDrawerTap();
                } else if (inNav && navTapListener != null) {
                    navTapListener.onNavTap(navIdx);
                }
                selectedIndex = -1;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                selectedIndex = -1;
                invalidate();
                return true;
        }
        return false;
    }

    private static class DockItem {
        String   packageName;
        Drawable icon;
    }
}
