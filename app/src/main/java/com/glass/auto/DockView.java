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

    private final float dp;

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

    private List<DockItem> items = new ArrayList<>();
    private int selectedIndex    = -1;
    private LaunchListener launchListener;

    // Section widths in dp
    private static final float CLOCK_W    = 130f;
    private static final float WEATHER_W  = 120f; // wider to fit city name
    private static final float PRAYER_W   = 190f;
    private static final float ICON_SIZE  = 44f;
    private static final float ICON_PAD   = 10f;
    private static final float SEL_RADIUS = 10f;

    public DockView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        dp = ctx.getResources().getDisplayMetrics().density;

        bgPaint.setColor(0x00000000);
        bgPaint.setStyle(Paint.Style.FILL);

        rimPaint.setColor(0x22FFFFFF);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(1f * dp);

        selPaint.setColor(0x33FFFFFF);
        selPaint.setStyle(Paint.Style.FILL);

        clockPaint.setColor(0xFFFFFFFF);
        clockPaint.setTextSize(24f * dp);
        clockPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));

        datePaint.setColor(0x99FFFFFF);
        datePaint.setTextSize(11f * dp);

        valuePaint.setColor(0xFFFFFFFF);
        valuePaint.setTextSize(15f * dp);
        valuePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        labelPaint.setColor(0x88FFFFFF);
        labelPaint.setTextSize(10f * dp);

        dimPaint.setColor(0x66FFFFFF);
        dimPaint.setTextSize(13f * dp);

        setWillNotDraw(false);
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
                               String next, String nextTime) {
        prayerCurrent     = current;
        prayerCurrentTime = currentTime;
        prayerNext        = next;
        prayerNextTime    = nextTime;
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
        // Shift content up slightly when city name is shown so all three rows fit
        float topShift = hasCity ? 8f * dp : 0f;

        // Emoji + temperature on one row (side by side, centred)
        valuePaint.setTextSize(18f * dp);
        String emojiTemp = weatherEmoji + " " + weatherTemp;
        valuePaint.getTextBounds(emojiTemp, 0, emojiTemp.length(), textBounds);
        c.drawText(emojiTemp,
                x + (w - textBounds.width()) / 2f,
                cy - topShift, valuePaint);

        // City name below
        if (hasCity) {
            labelPaint.getTextBounds(weatherCity, 0, weatherCity.length(), textBounds);
            // Truncate if too wide for the section
            String city = weatherCity;
            while (textBounds.width() > w - 8 * dp && city.length() > 3) {
                city = city.substring(0, city.length() - 1);
                labelPaint.getTextBounds(city + "…", 0, city.length() + 1, textBounds);
            }
            if (!city.equals(weatherCity)) city = city + "…";
            labelPaint.getTextBounds(city, 0, city.length(), textBounds);
            c.drawText(city,
                    x + (w - textBounds.width()) / 2f,
                    cy - topShift + 16f * dp, labelPaint);
        }

        valuePaint.setTextSize(15f * dp); // reset
    }

    private void drawPrayer(Canvas c, float x, float w, float cy, float h) {
        if (prayerCurrent.isEmpty() && prayerNext.isEmpty()) {
            dimPaint.getTextBounds("Prayer loading…", 0, 14, textBounds);
            c.drawText("Prayer loading…", x + 12 * dp, cy + 5 * dp, dimPaint);
            return;
        }

        float colW = w / 2f;
        // LEFT: current prayer
        drawPrayerColumn(c, x, colW, cy,
                prayerCurrent, prayerCurrentTime, true);
        // Arrow
        dimPaint.setTextSize(16f * dp);
        c.drawText("›", x + colW - 8 * dp, cy + 5 * dp, dimPaint);
        dimPaint.setTextSize(13f * dp);
        // RIGHT: next prayer
        drawPrayerColumn(c, x + colW, colW - 8 * dp, cy,
                prayerNext, prayerNextTime, false);
    }

    private void drawPrayerColumn(Canvas c, float x, float w, float cy,
                                  String name, String time, boolean isCurrent) {
        // Name label (small, dim)
        labelPaint.getTextBounds(name, 0, name.length(), textBounds);
        c.drawText(name, x + (w - textBounds.width()) / 2f,
                cy - 6 * dp, labelPaint);
        // Time (bold, white if current / dim if next)
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
