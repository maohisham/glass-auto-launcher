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
    private static final float REF_WIDTH_PX = 1280f; // reference screen width for auto-scale

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

    // Section widths in dp
    private static final float CLOCK_W    = 130f;
    private static final float WEATHER_W  = 120f;
    private static final float PRAYER_W   = 230f; // wider to fit location + hijri row
    private static final float ICON_SIZE  = 44f;
    private static final float ICON_PAD   = 10f;
    private static final float SEL_RADIUS = 10f;

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
        clockPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));

        datePaint.setColor(0x99FFFFFF);
        datePaint.setTextSize(13f * dp);

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
            // recompute now if we already have a size
            if (getWidth() > 0) recomputeScale(getWidth());
        }
        dp = rawDp * uiScale;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (autoScale && w > 0) recomputeScale(w);
        dp = rawDp * uiScale;
    }

    private void recomputeScale(int widthPx) {
        float raw = widthPx / REF_WIDTH_PX;
        uiScale = Math.max(0.6f, Math.min(2.0f, raw));
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

    // --- Drawing ---

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float cy = h / 2f;

        float clockW   = CLOCK_W   * dp;
        float weatherW = WEATHER_W * dp;
        float prayerW  = PRAYER_W  * dp;
        float iconW    = w - clockW - weatherW - prayerW;

        // Section x starts
        float xClock   = 0;
        float xIcons   = clockW;
        float xWeather = xIcons + iconW;
        float xPrayer  = xWeather + weatherW;

        // Hairline dividers
        canvas.drawLine(xIcons,   8 * dp, xIcons,   h - 8 * dp, rimPaint);
        canvas.drawLine(xWeather, 8 * dp, xWeather, h - 8 * dp, rimPaint);
        canvas.drawLine(xPrayer,  8 * dp, xPrayer,  h - 8 * dp, rimPaint);

        drawClock(canvas, xClock, clockW, cy, h);
        drawIcons(canvas, xIcons, iconW, cy);
        drawWeather(canvas, xWeather, weatherW, cy, h);
        drawPrayer(canvas, xPrayer, prayerW, cy, h);
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
        float iconPx = ICON_SIZE * dp;
        float padPx  = ICON_PAD  * dp;
        float rowW   = items.size() * iconPx + (items.size() - 1) * padPx;
        float x      = startX + (totalW - rowW) / 2f;

        for (int i = 0; i < items.size(); i++) {
            float left = x + i * (iconPx + padPx);
            float top  = cy - iconPx / 2f;

            if (i == selectedIndex) {
                selRect.set(left - 6 * dp, top - 6 * dp,
                        left + iconPx + 6 * dp, top + iconPx + 6 * dp);
                c.drawRoundRect(selRect, SEL_RADIUS * dp, SEL_RADIUS * dp, selPaint);
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
        float topShift = hasCity ? 11f * dp : 0f;

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
                    cy - topShift + 22f * dp, cityPaint);
        }

        valuePaint.setTextSize(18f * dp); // reset
    }

    private void drawPrayer(Canvas c, float x, float w, float cy, float h) {
        if (prayerCurrent.isEmpty() && prayerNext.isEmpty()) {
            dimPaint.getTextBounds("Prayer loading…", 0, 14, textBounds);
            c.drawText("Prayer loading…", x + 12 * dp, cy + 5 * dp, dimPaint);
            return;
        }

        // Top info row: "{location}  ·  {hijriDate}" in 9sp dim text
        boolean hasInfo = !prayerLocation.isEmpty() || !prayerHijri.isEmpty();
        float infoOffset = hasInfo ? 14f * dp : 0f;

        if (hasInfo) {
            Paint infoPaint = new Paint(labelPaint);
            infoPaint.setTextSize(11f * dp);
            infoPaint.setColor(0x66FFFFFF);
            StringBuilder info = new StringBuilder();
            if (!prayerLocation.isEmpty()) info.append(prayerLocation);
            if (!prayerLocation.isEmpty() && !prayerHijri.isEmpty()) info.append("  ·  ");
            if (!prayerHijri.isEmpty()) info.append(prayerHijri);
            String infoStr = info.toString();
            // Truncate if wider than section
            while (infoPaint.measureText(infoStr) > w - 8 * dp && infoStr.length() > 6) {
                infoStr = infoStr.substring(0, infoStr.length() - 1);
            }
            infoPaint.getTextBounds(infoStr, 0, infoStr.length(), textBounds);
            c.drawText(infoStr,
                    x + (w - textBounds.width()) / 2f,
                    cy - infoOffset - 4 * dp,
                    infoPaint);
        }

        float colW = w / 2f;
        float prayerCy = cy + infoOffset / 2f; // shift prayer content down when info row present
        // LEFT: current prayer
        drawPrayerColumn(c, x, colW, prayerCy,
                prayerCurrent, prayerCurrentTime, true);
        // Arrow
        dimPaint.setTextSize(20f * dp);
        c.drawText("›", x + colW - 8 * dp, prayerCy + 7 * dp, dimPaint);
        dimPaint.setTextSize(16f * dp);
        // RIGHT: next prayer
        drawPrayerColumn(c, x + colW, colW - 8 * dp, prayerCy,
                prayerNext, prayerNextTime, false);
    }

    private void drawPrayerColumn(Canvas c, float x, float w, float cy,
                                  String name, String time, boolean isCurrent) {
        labelPaint.getTextBounds(name, 0, name.length(), textBounds);
        c.drawText(name, x + (w - textBounds.width()) / 2f,
                cy - 6 * dp, labelPaint);
        Paint tp = isCurrent ? valuePaint : dimPaint;
        tp.getTextBounds(time, 0, time.length(), textBounds);
        c.drawText(time, x + (w - textBounds.width()) / 2f,
                cy + 14 * dp, tp);
    }

    // --- Touch ---

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (items.isEmpty()) return false;
        float dp     = this.dp;
        float clockW = CLOCK_W * dp;
        float iconW  = getWidth() - clockW - (WEATHER_W + PRAYER_W) * dp;
        float iconPx = ICON_SIZE * dp;
        float padPx  = ICON_PAD  * dp;
        float rowW   = items.size() * iconPx + (items.size() - 1) * padPx;
        float startX = clockW + (iconW - rowW) / 2f;
        float x      = ev.getX();

        int tapped = -1;
        for (int i = 0; i < items.size(); i++) {
            float left = startX + i * (iconPx + padPx);
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
