package com.glass.auto;

import android.content.Context;
import android.content.pm.PackageManager;
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
 * Layout (left → right):
 *   [Clock/Date (140dp)] | [App icons (flex)] | [Weather (100dp)] | [Prayer (200dp)]
 *
 * All data is pushed in via setters; DockView never does I/O itself.
 */
public class DockView extends View {

    public interface LaunchListener { void onLaunch(String packageName); }
    public interface OnSectionTapListener {
        void onWeatherTap();
        void onPrayerTap();
    }

    // --- Paints ---
    private final Paint bgPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint datePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF selRect    = new RectF();
    private final Rect  textBounds = new Rect();

    private final float rawDp;  // physical display density (immutable)
    private       float dp;     // effective scale = rawDp * uiScale (recomputed on resize/config)
    private       float uiScale  = 1.0f;
    private       boolean autoScale = true;
    // Reference dock height in dp (matches android:layout_height in activity_car.xml)
    private static final float REF_DOCK_DP = 96f;

    // --- State ---
    private String  clockText   = "00:00";
    private String  dateText    = "Mon, 1 Jan";
    private String  weatherEmoji = "–";
    private String  weatherTemp  = "–°";
    private String  weatherCity  = "";
    private String  prayerCurrent = "";
    private String  prayerCurrentTime = "";
    private String  prayerNext   = "";
    private String  prayerNextTime = "";
    private String  prayerLocation = "";
    private String  prayerHijri   = "";

    private List<DockItem> items = new ArrayList<>();
    private int selectedIndex    = -1;
    private LaunchListener launchListener;
    private OnSectionTapListener sectionTapListener;

    // Section widths in dp
    private static final float CLOCK_W    = 130f;
    private static final float WEATHER_W  = 110f;
    private static final float PRAYER_W   = 270f; // wider: fits location + hijri on two rows
    private static final int   MAX_ICONS  = 6;
    private static final float ICON_SIZE  = 40f;
    private static final float SEL_RADIUS = 8f;

    public DockView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        rawDp = ctx.getResources().getDisplayMetrics().density;
        dp    = rawDp; // will be recomputed in onSizeChanged once width is known

        bgPaint.setColor(0x00000000);
        bgPaint.setStyle(Paint.Style.FILL);

        rimPaint.setColor(0x22FFFFFF);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(1f * dp);

        selPaint.setColor(0x33FFFFFF);
        selPaint.setStyle(Paint.Style.FILL);

        clockPaint.setColor(0xFFFFFFFF);
        clockPaint.setTextSize(28f * dp);
        clockPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        datePaint.setColor(0xFFFFFFFF);
        datePaint.setTextSize(13f * dp);
        datePaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));

        valuePaint.setColor(0xFFFFFFFF);
        valuePaint.setTextSize(18f * dp);
        valuePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        labelPaint.setColor(0x88FFFFFF);
        labelPaint.setTextSize(12f * dp);

        dimPaint.setColor(0x66FFFFFF);
        dimPaint.setTextSize(16f * dp);

        setWillNotDraw(false);
    }

    /**
     * Set a manual UI scale override.
     * Pass 0 (or any value ≤ 0) to re-enable auto-scaling from the dock's pixel width.
     * Auto-scale formula: clamp(dockWidthPx / 1280, 0.6, 2.0).
     */
    public void configureScale(float cfgScale) {
        if (cfgScale > 0f) {
            autoScale = false;
            uiScale   = cfgScale;
        } else {
            autoScale = true;
            if (getWidth() > 0) recomputeScale(getWidth());
        }
        dp = rawDp * uiScale;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Width-based text scale; icons bypass this and use rawDp directly (see drawIcons).
        if (autoScale && w > 0) recomputeScale(w);
        dp = rawDp * uiScale;
    }

    private static final float REF_WIDTH_PX = 1280f;

    private void recomputeScale(int widthPx) {
        // Scale text relative to a 1280px reference. Cap at 1.4 so text fits the dock height.
        float raw = widthPx / REF_WIDTH_PX;
        uiScale = Math.max(0.6f, Math.min(1.4f, raw));
    }

    // --- Setters (called from CarActivity tick) ---

    public void setClock(String time, String date) {
        clockText = time;
        dateText  = date;
        invalidate();
    }

    public void setWeather(String emoji, String tempC, String city) {
        weatherEmoji = emoji;
        weatherTemp  = tempC;
        weatherCity  = city != null ? city : "";
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
        invalidate();
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

    // --- Drawing ---

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float cy = h / 2f;

        // Layout (left → right): Prayer | Icons | Weather | Clock
        float prayerW  = PRAYER_W  * dp;
        float weatherW = WEATHER_W * dp;
        float clockW   = CLOCK_W   * dp;
        float iconW    = w - prayerW - weatherW - clockW;

        float xPrayer  = 0;
        float xIcons   = prayerW;
        float xWeather = xIcons + iconW;
        float xClock   = xWeather + weatherW;

        // Top separator between panel area and dock
        canvas.drawLine(0, 1f, w, 1f, rimPaint);

        // Vertical section dividers
        canvas.drawLine(xIcons,   8 * dp, xIcons,   h - 8 * dp, rimPaint);
        canvas.drawLine(xWeather, 8 * dp, xWeather, h - 8 * dp, rimPaint);
        canvas.drawLine(xClock,   8 * dp, xClock,   h - 8 * dp, rimPaint);

        drawPrayer(canvas, xPrayer, prayerW, cy, h);
        drawIcons(canvas, xIcons, iconW, cy);
        drawWeather(canvas, xWeather, weatherW, cy, h);
        drawClock(canvas, xClock, clockW, cy, h);
    }

    private void drawClock(Canvas c, float x, float w, float cy, float h) {
        // Time (large)
        clockPaint.getTextBounds(clockText, 0, clockText.length(), textBounds);
        float timeH = textBounds.height();
        float timeY = cy - 4 * dp;
        c.drawText(clockText, x + (w - textBounds.width()) / 2f,
                timeY, clockPaint);
        // Date (small, below)
        datePaint.getTextBounds(dateText, 0, dateText.length(), textBounds);
        c.drawText(dateText, x + (w - textBounds.width()) / 2f,
                timeY + timeH * 0.5f + 14 * dp, datePaint);
    }

    private void drawIcons(Canvas c, float startX, float totalW, float cy) {
        if (items.isEmpty()) return;
        // Icons use rawDp (no text scale) — fixed physical size regardless of screen width
        float iconPx = ICON_SIZE * rawDp;
        int   n      = items.size();
        // Distribute evenly across the zone: equal gap before, between, and after icons
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

    private void drawWeather(Canvas c, float x, float w, float cy, float h) {
        boolean hasCity = weatherCity != null && !weatherCity.isEmpty();
        // Align temp baseline with the clock baseline (cy - 4dp) so both sections
        // sit at the same visual height. Shift slightly higher when city is shown
        // so that both lines centre around cy.
        float topShift = hasCity ? 8f * dp : 4f * dp;

        valuePaint.setTextSize(22f * dp);
        String emojiTemp = weatherEmoji + " " + weatherTemp;
        valuePaint.getTextBounds(emojiTemp, 0, emojiTemp.length(), textBounds);
        c.drawText(emojiTemp,
                x + (w - textBounds.width()) / 2f,
                cy - topShift, valuePaint);

        if (hasCity) {
            Paint cityPaint = new Paint(labelPaint);
            cityPaint.setTextSize(13f * dp);
            String city = weatherCity;
            cityPaint.getTextBounds(city, 0, city.length(), textBounds);
            while (textBounds.width() > w - 8 * dp && city.length() > 3) {
                city = city.substring(0, city.length() - 1);
                cityPaint.getTextBounds(city + "…", 0, city.length() + 1, textBounds);
            }
            if (!city.equals(weatherCity)) city = city + "…";
            cityPaint.getTextBounds(city, 0, city.length(), textBounds);
            c.drawText(city,
                    x + (w - textBounds.width()) / 2f,
                    cy - topShift + 20f * dp, cityPaint);
        }

        valuePaint.setTextSize(18f * dp); // reset
    }

    private void drawPrayer(Canvas c, float x, float w, float cy, float h) {
        if (prayerCurrent.isEmpty() && prayerNext.isEmpty()) {
            dimPaint.getTextBounds("Prayer loading…", 0, 14, textBounds);
            c.drawText("Prayer loading…", x + 12 * dp, cy + 5 * dp, dimPaint);
            return;
        }

        // Two info rows above prayer times: zone name (row 1) + hijri date (row 2)
        boolean hasLoc   = !prayerLocation.isEmpty();
        boolean hasHijri = !prayerHijri.isEmpty();
        boolean hasInfo  = hasLoc || hasHijri;
        float infoOffset = hasInfo ? 10f * dp : 0f;

        if (hasInfo) {
            Paint infoPaint = new Paint(labelPaint);
            infoPaint.setTextSize(10f * dp);
            infoPaint.setColor(0xFFFFFFFF);
            float maxW = w - 8 * dp;

            // Single line: "🕌 CityName · HijriDate"
            String infoLine = "";
            if (hasLoc) {
                String loc = prayerLocation;
                int slash = loc.indexOf(" / ");
                if (slash > 0) loc = loc.substring(0, slash);
                infoLine = "🕌 " + loc;
            }
            if (hasHijri) {
                infoLine += (infoLine.isEmpty() ? "" : " · ") + prayerHijri;
            }
            while (infoPaint.measureText(infoLine) > maxW && infoLine.length() > 4)
                infoLine = infoLine.substring(0, infoLine.length() - 1);
            infoPaint.getTextBounds(infoLine, 0, infoLine.length(), textBounds);
            c.drawText(infoLine, x + (w - textBounds.width()) / 2f,
                    cy - infoOffset - 2 * dp, infoPaint);
        }

        float colW = w / 2f;
        float prayerCy = cy + infoOffset / 2f;
        // LEFT: current prayer
        drawPrayerColumn(c, x, colW, prayerCy,
                prayerCurrent, to12h(prayerCurrentTime), true, null);
        // Arrow
        dimPaint.setTextSize(20f * dp);
        c.drawText("›", x + colW - 8 * dp, prayerCy + 7 * dp, dimPaint);
        dimPaint.setTextSize(16f * dp);
        // RIGHT: next prayer + countdown
        drawPrayerColumn(c, x + colW, colW - 8 * dp, prayerCy,
                prayerNext, to12h(prayerNextTime), false, countdown(prayerNextTime));
    }

    /** Returns "in Xh Ym" or "in Ym" until the next prayer whose raw 24h time is {@code t}. */
    private static String countdown(String t) {
        if (t == null || t.isEmpty()) return "";
        try {
            int colon = t.indexOf(':');
            if (colon < 1) return "";
            int nextH = Integer.parseInt(t.substring(0, colon).trim());
            int nextM = Integer.parseInt(t.substring(colon + 1, Math.min(colon + 3, t.length())).trim());
            java.util.Calendar now = java.util.Calendar.getInstance();
            int diff = (nextH * 60 + nextM) - (now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE));
            if (diff <= 0) diff += 24 * 60;
            int h = diff / 60, m = diff % 60;
            return h > 0 ? "in " + h + "h " + m + "m" : "in " + m + "m";
        } catch (Exception e) { return ""; }
    }

    private static String to12h(String t) {
        if (t == null || t.isEmpty()) return t;
        try {
            int colon = t.indexOf(':');
            if (colon < 1) return t;
            int h = Integer.parseInt(t.substring(0, colon).trim());
            String min = t.substring(colon + 1, Math.min(colon + 3, t.length()));
            String ampm = h < 12 ? "AM" : "PM";
            h = h % 12;
            if (h == 0) h = 12;
            return h + ":" + min + " " + ampm;
        } catch (Exception e) {
            return t;
        }
    }

    private void drawPrayerColumn(Canvas c, float x, float w, float cy,
                                  String name, String time, boolean isCurrent, String cdText) {
        labelPaint.getTextBounds(name, 0, name.length(), textBounds);
        c.drawText(name, x + (w - textBounds.width()) / 2f,
                cy - 6 * dp, labelPaint);
        Paint tp = isCurrent ? valuePaint : dimPaint;
        tp.getTextBounds(time, 0, time.length(), textBounds);
        c.drawText(time, x + (w - textBounds.width()) / 2f,
                cy + 14 * dp, tp);
        // Countdown shown only for the next prayer
        if (cdText != null && !cdText.isEmpty()) {
            Paint cdPaint = new Paint(labelPaint);
            cdPaint.setTextSize(10f * dp);
            cdPaint.setColor(0xAAFFFFFF);
            cdPaint.getTextBounds(cdText, 0, cdText.length(), textBounds);
            c.drawText(cdText, x + (w - textBounds.width()) / 2f,
                    cy + 26 * dp, cdPaint);
        }
    }

    // --- Touch ---

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        float prayerW   = PRAYER_W  * dp;
        float weatherW  = WEATHER_W * dp;
        float clockW    = CLOCK_W   * dp;
        float iconZoneW = getWidth() - prayerW - weatherW - clockW;
        float xWeather  = prayerW + iconZoneW;
        float xClock    = xWeather + weatherW;
        float iconPx    = ICON_SIZE * rawDp;
        int   n         = items.size();
        float gap       = n > 0 ? (iconZoneW - n * iconPx) / (n + 1f) : 0;
        float x         = ev.getX();

        boolean inPrayer  = x < prayerW;
        boolean inWeather = x >= xWeather && x < xClock;

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
