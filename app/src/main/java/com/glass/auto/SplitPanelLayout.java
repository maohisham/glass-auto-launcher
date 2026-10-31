package com.glass.auto;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * Horizontal multi-panel layout supporting 1, 2, or 3 panels separated by draggable dividers.
 *
 * Expected child order (all must be present in XML, visibility managed here):
 *   0 — panel1 (map / single app)
 *   1 — divider1
 *   2 — panel2 (media, launcher-owned)
 *   3 — divider2
 *   4 — panel3 (third freeform app)
 *
 * Ratios stored as fractions of total usable width (after dividers):
 *   ratio1 — panel1 fraction
 *   ratio2 — panel2 fraction  (mode 3 only; panel3 gets remainder)
 *
 * In mode 1: panel1 = full width; divider1/panel2/divider2/panel3 hidden.
 * In mode 2: panel1 = ratio1; panel2 = remainder; divider2/panel3 hidden.
 * In mode 3: panel1 = ratio1; panel2 = ratio2 of remainder; panel3 = rest.
 */
public class SplitPanelLayout extends ViewGroup {

    public static final int MODE_SINGLE = 1;
    public static final int MODE_SPLIT  = 2;
    public static final int MODE_TRIPLE = 3;

    private static final float MIN_R = 0.15f;
    private static final float MAX_R = 0.80f;

    interface OnRatioChangedListener {
        /** Called when the user releases a divider. dividerIndex is 1 or 2. */
        void onRatioChanged(int dividerIndex, float ratio1, float ratio2);
    }

    private int   mode   = MODE_SPLIT;
    private float ratio1 = 0.55f; // panel1 fraction
    private float ratio2 = 0.50f; // panel2 fraction of remaining width (mode 3)

    private int dividerW;
    private OnRatioChangedListener listener;

    // Drag state
    private int   activeDivider = 0; // 1 or 2
    private float dragStartX, dragStartRatio;
    private boolean dragging;

    public SplitPanelLayout(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        setWillNotDraw(false);
    }

    // --- Config ---

    public void setMode(int m) {
        mode = m;
        applyVisibility();
        requestLayout();
    }

    public int getMode() { return mode; }

    public void setRatios(float r1, float r2) {
        ratio1 = clamp(r1);
        ratio2 = clamp(r2);
        requestLayout();
    }

    public float getRatio1() { return ratio1; }
    public float getRatio2() { return ratio2; }

    public void setOnRatioChangedListener(OnRatioChangedListener l) { listener = l; }

    // --- Visibility ---

    private void applyVisibility() {
        if (getChildCount() < 5) return;
        switch (mode) {
            case MODE_SINGLE:
                child(0).setVisibility(VISIBLE);
                child(1).setVisibility(GONE);
                child(2).setVisibility(GONE);
                child(3).setVisibility(GONE);
                child(4).setVisibility(GONE);
                break;
            case MODE_SPLIT:
                child(0).setVisibility(VISIBLE);
                child(1).setVisibility(VISIBLE);
                child(2).setVisibility(VISIBLE);
                child(3).setVisibility(GONE);
                child(4).setVisibility(GONE);
                break;
            case MODE_TRIPLE:
                for (int i = 0; i < 5; i++) child(i).setVisibility(VISIBLE);
                break;
        }
    }

    // --- Layout ---

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (getChildCount() < 5) return;
        int w = r - l;
        int h = b - t;
        dividerW = child(1).getMeasuredWidth();

        switch (mode) {
            case MODE_SINGLE:
                child(0).layout(0, 0, w, h);
                break;
            case MODE_SPLIT: {
                int p1w = (int) (ratio1 * (w - dividerW));
                int p2w = w - p1w - dividerW;
                child(0).layout(0,      0, p1w,              h);
                child(1).layout(p1w,    0, p1w + dividerW,   h);
                child(2).layout(p1w + dividerW, 0, w,        h);
                break;
            }
            case MODE_TRIPLE: {
                int totalDiv = dividerW * 2;
                int usable   = w - totalDiv;
                int p1w = (int) (ratio1 * usable);
                int p2w = (int) (ratio2 * (usable - p1w));
                int p3w = usable - p1w - p2w;

                int x0 = 0;
                int x1 = x0 + p1w;
                int x2 = x1 + dividerW;
                int x3 = x2 + p2w;
                int x4 = x3 + dividerW;
                int x5 = x4 + p3w;

                child(0).layout(x0, 0, x1, h);
                child(1).layout(x1, 0, x2, h);
                child(2).layout(x2, 0, x3, h);
                child(3).layout(x3, 0, x4, h);
                child(4).layout(x4, 0, x5, h);
                break;
            }
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        for (int i = 0; i < getChildCount(); i++) {
            child(i).measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
        applyVisibility();
        setMeasuredDimension(w, h);
    }

    // --- Touch (drag dividers) ---

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getAction() != MotionEvent.ACTION_DOWN) return false;
        int slop = 28; // px wider touch target
        float x  = ev.getX();

        if (mode == MODE_SPLIT || mode == MODE_TRIPLE) {
            View d1 = child(1);
            if (d1.getVisibility() == VISIBLE
                    && x >= d1.getLeft() - slop && x <= d1.getRight() + slop) {
                activeDivider = 1;
                dragStartX     = x;
                dragStartRatio = ratio1;
                dragging       = true;
                return true;
            }
        }
        if (mode == MODE_TRIPLE) {
            View d2 = child(3);
            if (d2.getVisibility() == VISIBLE
                    && x >= d2.getLeft() - slop && x <= d2.getRight() + slop) {
                activeDivider = 2;
                dragStartX     = x;
                dragStartRatio = ratio2;
                dragging       = true;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!dragging) return false;
        int w = getWidth();
        int totalDiv = dividerW * (mode == MODE_TRIPLE ? 2 : 1);
        int usable   = w - totalDiv;

        switch (ev.getAction()) {
            case MotionEvent.ACTION_MOVE: {
                float delta = ev.getX() - dragStartX;
                if (activeDivider == 1) {
                    float newR = dragStartRatio + delta / usable;
                    ratio1 = clamp(newR);
                } else {
                    // divider2 moves within the remaining space after panel1+divider1
                    int after1 = (int) (ratio1 * usable) + dividerW;
                    float remaining = usable - (int) (ratio1 * usable);
                    float newR = dragStartRatio + delta / remaining;
                    ratio2 = clamp(newR);
                }
                requestLayout();
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (listener != null) listener.onRatioChanged(activeDivider, ratio1, ratio2);
                break;
        }
        return true;
    }

    private View child(int i) { return getChildAt(i); }
    private float clamp(float r) { return Math.max(MIN_R, Math.min(MAX_R, r)); }
}
