package com.glass.auto;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * The selection highlight: a bright rim, subtle fill, and a layered outer glow built from
 * concentric semi-transparent strokes. All drawn in hardware so the spring animation stays smooth.
 */
public class GlassView extends View {

    private final GlassRim rim;
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF glowRect = new RectF();
    private final float density;
    private float radius;

    // Glow layers: each entry is {expand-dp, alpha 0-255}
    private static final int[][] GLOW_LAYERS = {
            {10, 0x55}, {7, 0x77}, {4, 0x99}, {2, 0xBB}
    };
    private static final int[][] GLOW_LAYERS_REDUCED = {
            {4, 0x99}, {2, 0xBB}
    };
    private boolean reducedMotion;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 20f * density;
        rim = new GlassRim(2.5f * density, 0xFFFFFFFF, 0x88FFFFFF);

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(0x28FFFFFF);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(3f * density);
        glowPaint.setColor(0xFFFFFFFF);

        // Hardware layer: keeps the spring animation smooth (only translation changes).
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    void setRadius(float px) {
        radius = px;
        invalidate();
    }

    void setReducedMotion(boolean reduced) {
        reducedMotion = reduced;
    }

    void setGlowColor(int color) {
        glowPaint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = 6f * density;
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        // Layered outer glow: concentric strokes from large+faint to small+bright.
        for (int[] layer : reducedMotion ? GLOW_LAYERS_REDUCED : GLOW_LAYERS) {
            float expand = layer[0] * density;
            int alpha = layer[1];
            glowRect.set(rect.left - expand, rect.top - expand,
                    rect.right + expand, rect.bottom + expand);
            glowPaint.setAlpha(alpha);
            canvas.drawRoundRect(glowRect, radius + expand, radius + expand, glowPaint);
        }

        // Subtle fill.
        canvas.drawRoundRect(rect, radius, radius, fillPaint);

        // Bright shining rim.
        rim.fit(rect);
        canvas.drawRoundRect(rect, radius, radius, rim.paint);
    }
}
