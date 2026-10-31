package com.glass.auto;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * Horizontal split layout: left panel (map) | divider | right panel (media).
 * The divider is draggable; the split ratio is stored and restored via Config.
 *
 * Children must be added in order: [0] map panel, [1] divider, [2] media panel.
 */
public class SplitPanelLayout extends ViewGroup {

    interface OnRatioChangedListener {
        void onRatioChanged(float ratio);
    }

    private static final float MIN_RATIO = 0.25f;
    private static final float MAX_RATIO = 0.82f;
    private static final int   DIVIDER_INDEX = 1;

    private float ratio = 0.6f;
    private int dividerW;
    private float dragStartX;
    private float dragStartRatio;
    private boolean dragging;
    private OnRatioChangedListener listener;

    public SplitPanelLayout(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        setWillNotDraw(false);
    }

    public void setRatio(float r) {
        ratio = clamp(r);
        requestLayout();
    }

    public float getRatio() { return ratio; }

    public void setOnRatioChangedListener(OnRatioChangedListener l) { listener = l; }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int h = b - t;
        if (getChildCount() < 3) return;

        View map     = getChildAt(0);
        View divider = getChildAt(DIVIDER_INDEX);
        View media   = getChildAt(2);

        dividerW = divider.getMeasuredWidth();
        int mapW   = (int) (ratio * (w - dividerW));
        int mediaW = w - mapW - dividerW;

        map.layout(0, 0, mapW, h);
        divider.layout(mapW, 0, mapW + dividerW, h);
        media.layout(mapW + dividerW, 0, w, h);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, h);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (getChildCount() < 3) return false;
        View divider = getChildAt(DIVIDER_INDEX);
        int touchSlop = 24; // px — wider touch target for the divider
        float x = ev.getX();
        float divX = divider.getLeft();
        if (ev.getAction() == MotionEvent.ACTION_DOWN
                && x >= divX - touchSlop && x <= divX + divider.getWidth() + touchSlop) {
            dragStartX = x;
            dragStartRatio = ratio;
            dragging = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!dragging) return false;
        int w = getWidth();
        switch (ev.getAction()) {
            case MotionEvent.ACTION_MOVE: {
                float delta = ev.getX() - dragStartX;
                float newRatio = dragStartRatio + delta / (w - dividerW);
                ratio = clamp(newRatio);
                requestLayout();
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (listener != null) listener.onRatioChanged(ratio);
                break;
        }
        return true;
    }

    private float clamp(float r) {
        return Math.max(MIN_RATIO, Math.min(MAX_RATIO, r));
    }
}
