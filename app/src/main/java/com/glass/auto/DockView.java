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
 * Layout (left → right): [Prayer (270dp)] | [Icons (flex)] | [Weather (110dp)] | [Clock (130dp)] | [Drawer (50dp)]
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
    private static final float CLOCK_W    = 130f;
    private static final float WEATHER_W  = 110f;
    private static final float PRAYER_W   = 310f;
    private static final float DRAWER_W   = 50f;
    private static final int   MAX_ICONS  = 6;
    private static final float ICON_SIZE  = 40f;
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

        canvas.drawLine(0, 1f, w, 1f, rimPaint);
        canvas.drawLine(xIcons,   8 * dp, xIcons,   h - 8 * dp, rimPaint);
        canvas.drawLine(xWeather, 8 * dp, xWeather, h - 8 * dp, rimPaint);
        canvas.drawLine(xClock,   8 * dp, xClock,   h - 8 * dp, rimPaint);
        canvas.drawLine(xDrawer,  8 * dp, xDrawer,  h - 8 * dp, rimPaint);

        drawPrayer(canvas, 0, prayerW, cy, h);
        drawIcons(canvas, xIcons, iconW, cy);
        drawWeather(canvas, xWeather, weatherW, cy);
        drawClock(canvas, xClock, clockW, cy);
        drawAppDrawer(canvas, xDrawer, drawerW, cy);
    }

    private void drawClock(Canvas c, float x, float w, float cy) {
        clockPaint.getTextBounds(clockText, 0, clockText.length(), textBounds);
        float timeH = textBounds.height();
        float timeY = cy - 4 * dp;
        c.drawText(clockText, x + (w - textBounds.width()) / 2f, timeY, clockPaint);
        datePaint.getTextBounds(dateText, 0, dateText.length(), textBounds);
        c.drawText(dateText, x + (w - textBounds.width()) / 2f,
                timeY + timeH * 0.5f + 14 * dp, datePaint);
    }

    private void drawIcons(Canvas c, float startX, float totalW, float cy) {
        if (items.isEmpty()) return;
        float iconPx = ICON_SIZE * rawDp;
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

    private void drawWeather(Canvas c, float x, float w, float cy) {
        float topShift = hasCity ? 8f * dp : 4f * dp;
        valuePaintLarge.getTextBounds(emojiTempStr, 0, emojiTempStr.length(), textBounds);
        c.drawText(emojiTempStr, x + (w - textBounds.width()) / 2f, cy - topShift, valuePaintLarge);

        if (hasCity) {
            // Truncate to fit width (truncation is fast; no new Paint created)
            String city = weatherCity;
            cityPaint.getTextBounds(city, 0, city.length(), textBounds);
            while (textBounds.width() > w - 8 * dp && city.length() > 3) {
                city = city.substring(0, city.length() - 1);
                cityPaint.getTextBounds(city + "…", 0, city.length() + 1, textBounds);
            }
            if (!city.equals(weatherCity)) city = city + "…";
            cityPaint.getTextBounds(city, 0, city.length(), textBounds);
            c.drawText(city, x + (w - textBounds.width()) / 2f,
                    cy - topShift + 20f * dp, cityPaint);
        }
    }

    private void drawPrayer(Canvas c, float x, float w, float cy, float h) {
        float pad = 10 * dp;

        dimPaint.setTextSize(h * 0.14f);
        if (prayerCurrent.isEmpty() && prayerNext.isEmpty()) {
            c.drawText("Prayer loading…", x + pad, cy + 5 * dp, dimPaint);
            return;
        }

        // Row 1: location · hijri date, centred, light teal
        if (hasInfo) {
            infoPaint.setColor(COL_INFO);
            fitText(infoPaint, infoLineStr, w - 2 * pad, h * 0.17f);
            infoPaint.getTextBounds(infoLineStr, 0, infoLineStr.length(), textBounds);
            c.drawText(infoLineStr, x + (w - textBounds.width()) / 2f, h * 0.18f, infoPaint);
        }

        // Left column (centred): next prayer name + time
        float leftW = w * 0.56f - pad;
        float lcx   = x + pad + leftW / 2f;

        String name = prayerNext.isEmpty() ? "—" : prayerNext;
        fitText(valuePaint, name, leftW, h * 0.31f);
        valuePaint.getTextBounds(name, 0, name.length(), textBounds);
        c.drawText(name, lcx - textBounds.width() / 2f, h * 0.55f, valuePaint);

        if (!nextTime12.isEmpty()) {
            fitText(timePaint, nextTime12, leftW, h * 0.24f);
            timePaint.getTextBounds(nextTime12, 0, nextTime12.length(), textBounds);
            c.drawText(nextTime12, lcx - textBounds.width() / 2f, h * 0.80f, timePaint);
        }

        // Right column: countdown pill
        if (!countdownStr.isEmpty()) {
            float px0 = x + w * 0.58f, px1 = x + w - pad;
            float py0 = h * 0.27f,     py1 = h * 0.85f;
            selRect.set(px0, py0, px1, py1);
            c.drawRoundRect(selRect, 12 * dp, 12 * dp, pillFill);
            c.drawRoundRect(selRect, 12 * dp, 12 * dp, pillStroke);

            float pcx = (px0 + px1) / 2f;
            cdPaint.setColor(COL_TAG);
            fitText(cdPaint, "NEXT IN", px1 - px0 - 12 * dp, h * 0.125f);
            cdPaint.getTextBounds("NEXT IN", 0, 7, textBounds);
            c.drawText("NEXT IN", pcx - textBounds.width() / 2f, py0 + (py1 - py0) * 0.34f, cdPaint);

            fitText(countPaint, countdownStr, px1 - px0 - 12 * dp, h * 0.29f);
            countPaint.getTextBounds(countdownStr, 0, countdownStr.length(), textBounds);
            c.drawText(countdownStr, pcx - textBounds.width() / 2f, py0 + (py1 - py0) * 0.86f, countPaint);
        }

        // Footer: "NOW  Isyak 7:30 PM"
        if (!prayerCurrent.isEmpty()) {
            String lead = "NOW  ";
            String foot = prayerCurrent;
            if (!currentTime12.isEmpty()) foot += "  " + currentTime12;
            fitText(infoPaint, lead + foot, w - 2 * pad, h * 0.15f);
            float fx = x + (w - infoPaint.measureText(lead + foot)) / 2f;
            infoPaint.setColor(COL_TAG);
            c.drawText(lead, fx, h * 0.97f, infoPaint);
            infoPaint.setColor(0xFFFFFFFF);
            c.drawText(foot, fx + infoPaint.measureText(lead), h * 0.97f, infoPaint);
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
        float iconPx    = ICON_SIZE * rawDp;
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
