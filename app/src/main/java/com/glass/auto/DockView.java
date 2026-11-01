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
 * Layout (left → right): [Prayer (300dp)] | [Icons (flex)] | [Weather (105dp)] | [Clock (150dp)] | [Drawer (44dp)]
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
    private final RectF selRect    = new RectF();
    private final Rect  textBounds = new Rect();

    private final float rawDp;
    private       float dp;
    private       float uiScale   = 1.0f;
    private       boolean autoScale = true;

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

    // Section widths in dp
    private static final float CLOCK_W    = 150f;
    private static final float WEATHER_W  = 105f;
    private static final float PRAYER_W   = 300f;
    private static final float DRAWER_W   = 44f;
    private static final int   MAX_ICONS  = 6;
    private static final float ICON_SIZE  = 40f;
    private static final float ICON_MAX_H = 0.62f; // icon height as a fraction of the dock height
    private static final float SEL_RADIUS = 8f;
    private static final float REF_WIDTH_PX = 1280f;

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

    public void configureScale(float cfgScale) {
        if (cfgScale > 0f) {
            autoScale = false;
            uiScale   = cfgScale;
        } else {
            autoScale = true;
            if (getWidth() > 0) recomputeScale(getWidth());
        }
        dp = rawDp * uiScale;
        updatePaintSizes();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (autoScale && w > 0) recomputeScale(w);
        dp = rawDp * uiScale;
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

    private void recomputeScale(int widthPx) {
        float raw = widthPx / REF_WIDTH_PX;
        uiScale = Math.max(0.6f, Math.min(1.4f, raw));
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

    // --- Drawing (zero allocations) ---

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float cy = h / 2f;

        float drawerW  = DRAWER_W  * dp;
        float prayerW  = PRAYER_W  * dp;
        float weatherW = WEATHER_W * dp;
        float clockW   = CLOCK_W   * dp;
        float iconW    = w - prayerW - weatherW - clockW - drawerW;

        float xIcons   = prayerW;
        float xWeather = xIcons + iconW;
        float xClock   = xWeather + weatherW;
        float xDrawer  = xClock + clockW;

        float m = h * 0.14f;
        canvas.drawLine(0, 1f, w, 1f, rimPaint);
        canvas.drawLine(xIcons,   m, xIcons,   h - m, rimPaint);
        canvas.drawLine(xWeather, m, xWeather, h - m, rimPaint);
        canvas.drawLine(xClock,   m, xClock,   h - m, rimPaint);
        canvas.drawLine(xDrawer,  m, xDrawer,  h - m, rimPaint);

        drawPrayer(canvas, 0, prayerW, h);
        drawIcons(canvas, xIcons, iconW, cy, iconPx(h));
        drawWeather(canvas, xWeather, weatherW, h);
        drawClock(canvas, xClock, clockW, h);
        drawAppDrawer(canvas, xDrawer, drawerW, cy);
    }

    private float iconPx(float h) {
        return Math.min(ICON_SIZE * rawDp, h * ICON_MAX_H);
    }

    /** Time on top, weekday/date in the middle, Hijri date at the bottom. */
    private void drawClock(Canvas c, float x, float w, float h) {
        float pad = 6 * dp;
        fitText(clockPaint, clockText, w - 2 * pad, h * 0.38f);
        c.drawText(clockText, x + (w - clockPaint.measureText(clockText)) / 2f, h * 0.45f, clockPaint);

        fitText(datePaint, dateText, w - 2 * pad, h * 0.20f);
        c.drawText(dateText, x + (w - datePaint.measureText(dateText)) / 2f, h * 0.71f, datePaint);

        if (!prayerHijri.isEmpty()) {
            infoPaint.setColor(COL_INFO);
            fitText(infoPaint, prayerHijri, w - 2 * pad, h * 0.18f);
            c.drawText(prayerHijri, x + (w - infoPaint.measureText(prayerHijri)) / 2f, h * 0.93f, infoPaint);
        }
    }

    private void drawIcons(Canvas c, float startX, float totalW, float cy, float iconPx) {
        if (items.isEmpty()) return;
        int   n      = items.size();
        float gap    = (totalW - n * iconPx) / (n + 1f);

        for (int i = 0; i < n; i++) {
            float left = startX + gap * (i + 1) + iconPx * i;
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

    // --- Touch ---

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        float drawerW   = DRAWER_W  * dp;
        float prayerW   = PRAYER_W  * dp;
        float weatherW  = WEATHER_W * dp;
        float clockW    = CLOCK_W   * dp;
        float iconZoneW = getWidth() - prayerW - weatherW - clockW - drawerW;
        float xWeather  = prayerW + iconZoneW;
        float xClock    = xWeather + weatherW;
        float xDrawer   = xClock + clockW;
        float iconPx    = iconPx(getHeight());
        int   n         = items.size();
        float gap       = n > 0 ? (iconZoneW - n * iconPx) / (n + 1f) : 0;
        float x         = ev.getX();

        boolean inPrayer  = x < prayerW;
        boolean inWeather = x >= xWeather && x < xClock;
        boolean inDrawer  = x >= xDrawer;

        int tapped = -1;
        for (int i = 0; i < n; i++) {
            float left = prayerW + gap * (i + 1) + iconPx * i;
            if (x >= left && x <= left + iconPx) { tapped = i; break; }
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
