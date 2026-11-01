package com.glass.auto;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Tiny screen outline with a filled bar on the edge the dock currently sits on. */
public class DockPositionIcon extends View {

    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF screen  = new RectF();
    private final RectF dock    = new RectF();
    private int position = DockView.POS_BOTTOM;

    public DockPositionIcon(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        outline.setStyle(Paint.Style.STROKE);
        outline.setColor(0xFFFFFFFF);
        bar.setStyle(Paint.Style.FILL);
        bar.setColor(0xFF5CF29A);
    }

    public void setPosition(int pos) {
        if (position == pos) return;
        position = pos;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float sw = Math.max(2f, Math.min(w, h) * 0.07f);
        outline.setStrokeWidth(sw);
        float iw = w * 0.74f, ih = Math.min(h * 0.62f, iw * 0.7f);
        float l = (w - iw) / 2f, t = (h - ih) / 2f;
        screen.set(l, t, l + iw, t + ih);
        c.drawRoundRect(screen, ih * 0.12f, ih * 0.12f, outline);
        float in = sw * 1.2f;
        float bw = iw * 0.26f, bh = ih * 0.30f;
        switch (position) {
            case DockView.POS_TOP:   dock.set(l + in, t + in, l + iw - in, t + in + bh); break;
            case DockView.POS_LEFT:  dock.set(l + in, t + in, l + in + bw, t + ih - in); break;
            case DockView.POS_RIGHT: dock.set(l + iw - in - bw, t + in, l + iw - in, t + ih - in); break;
            default:                 dock.set(l + in, t + ih - in - bh, l + iw - in, t + ih - in);
        }
        c.drawRoundRect(dock, sw, sw, bar);
    }
}
