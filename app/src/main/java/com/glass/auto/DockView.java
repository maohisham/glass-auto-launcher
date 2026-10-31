package com.glass.auto;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-width glass dock drawn at the bottom of the screen.
 * Shows app icons; tapping one launches the app (outside the freeform map bounds,
 * so it opens normally over the full screen).
 */
public class DockView extends View {

    public interface LaunchListener {
        void onLaunch(String packageName);
    }

    private static final int ICON_DP  = 44;
    private static final int PAD_DP   = 12;
    private static final int RADIUS_DP = 10;

    private final Paint bgPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF itemRect   = new RectF();
    private final float density;

    private List<DockItem> items = new ArrayList<>();
    private int selectedIndex = -1;
    private LaunchListener launchListener;

    public DockView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        density = ctx.getResources().getDisplayMetrics().density;
        bgPaint.setColor(0x22FFFFFF);
        bgPaint.setStyle(Paint.Style.FILL);
        glowPaint.setColor(0x55FFFFFF);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(1.5f * density);
    }

    public void setApps(List<String> packageNames, PackageManager pm) {
        items.clear();
        for (String pkg : packageNames) {
            try {
                DockItem item = new DockItem();
                item.packageName = pkg;
                item.icon = pm.getApplicationIcon(pkg);
                item.label = pm.getApplicationLabel(
                        pm.getApplicationInfo(pkg, 0)).toString();
                items.add(item);
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        invalidate();
    }

    public void setLaunchListener(LaunchListener l) { launchListener = l; }

    @Override
    protected void onDraw(Canvas canvas) {
        if (items.isEmpty()) return;
        float iconPx = ICON_DP * density;
        float padPx  = PAD_DP  * density;
        float totalW = items.size() * (iconPx + padPx) - padPx;
        float startX = (getWidth() - totalW) / 2f;
        float centerY = getHeight() / 2f;
        float radius = RADIUS_DP * density;

        for (int i = 0; i < items.size(); i++) {
            float left = startX + i * (iconPx + padPx);
            itemRect.set(left - 4 * density, centerY - iconPx / 2f - 4 * density,
                    left + iconPx + 4 * density, centerY + iconPx / 2f + 4 * density);

            if (i == selectedIndex) {
                canvas.drawRoundRect(itemRect, radius, radius, bgPaint);
                canvas.drawRoundRect(itemRect, radius, radius, glowPaint);
            }

            Drawable icon = items.get(i).icon;
            if (icon != null) {
                int l = (int) left;
                int t = (int) (centerY - iconPx / 2f);
                icon.setBounds(l, t, l + (int) iconPx, t + (int) iconPx);
                icon.draw(canvas);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (items.isEmpty()) return false;
        float iconPx = ICON_DP * density;
        float padPx  = PAD_DP  * density;
        float totalW = items.size() * (iconPx + padPx) - padPx;
        float startX = (getWidth() - totalW) / 2f;

        float x = ev.getX();
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
        String packageName;
        String label;
        Drawable icon;
    }
}
