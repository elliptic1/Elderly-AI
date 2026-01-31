/*
 * Copyright 2024 ElderCare AI
 *
 * SmileyView - Custom view that draws a friendly smiley face
 */
package com.eldercare.app.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

/**
 * A custom view that draws a large, friendly smiley face.
 *
 * Features:
 * - Yellow circular face with eyes and smile
 * - Pulse animation when listening (scale up/down)
 * - Color changes based on state
 */
public class SmileyView extends View {

    private static final int COLOR_FACE = 0xFFFFD93D;      // Yellow
    private static final int COLOR_OUTLINE = 0xFF333333;   // Dark gray
    private static final int COLOR_LISTENING = 0xFF4CAF50; // Green glow when listening
    private static final int COLOR_ERROR = 0xFFF44336;     // Red for error

    private Paint facePaint;
    private Paint outlinePaint;
    private Paint eyePaint;
    private Paint mouthPaint;
    private Paint glowPaint;

    private RectF faceRect;
    private RectF mouthRect;

    private float scale = 1.0f;
    private boolean isListening = false;
    private boolean isError = false;
    private ValueAnimator pulseAnimator;

    public SmileyView(Context context) {
        super(context);
        init();
    }

    public SmileyView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SmileyView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // Face fill
        facePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        facePaint.setColor(COLOR_FACE);
        facePaint.setStyle(Paint.Style.FILL);

        // Face outline
        outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        outlinePaint.setColor(COLOR_OUTLINE);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(8f);

        // Eyes
        eyePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        eyePaint.setColor(COLOR_OUTLINE);
        eyePaint.setStyle(Paint.Style.FILL);

        // Mouth
        mouthPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mouthPaint.setColor(COLOR_OUTLINE);
        mouthPaint.setStyle(Paint.Style.STROKE);
        mouthPaint.setStrokeWidth(12f);
        mouthPaint.setStrokeCap(Paint.Cap.ROUND);

        // Glow effect when listening
        glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowPaint.setColor(COLOR_LISTENING);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(16f);

        faceRect = new RectF();
        mouthRect = new RectF();

        setupPulseAnimation();
    }

    private void setupPulseAnimation() {
        pulseAnimator = ValueAnimator.ofFloat(1.0f, 1.08f, 1.0f);
        pulseAnimator.setDuration(1200);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        pulseAnimator.addUpdateListener(animation -> {
            scale = (float) animation.getAnimatedValue();
            invalidate();
        });
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        float size = Math.min(width, height);
        float centerX = width / 2f;
        float centerY = height / 2f;
        float radius = (size / 2f - 20f) * scale;

        // Draw glow ring when listening
        if (isListening) {
            glowPaint.setColor(COLOR_LISTENING);
            canvas.drawCircle(centerX, centerY, radius + 12f, glowPaint);
        } else if (isError) {
            glowPaint.setColor(COLOR_ERROR);
            canvas.drawCircle(centerX, centerY, radius + 12f, glowPaint);
        }

        // Draw face circle
        faceRect.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
        canvas.drawOval(faceRect, facePaint);
        canvas.drawOval(faceRect, outlinePaint);

        // Draw eyes (positioned at ~35% from center, ~25% up from center)
        float eyeOffsetX = radius * 0.35f;
        float eyeOffsetY = radius * 0.25f;
        float eyeRadius = radius * 0.1f;

        // Left eye
        canvas.drawCircle(centerX - eyeOffsetX, centerY - eyeOffsetY, eyeRadius, eyePaint);
        // Right eye
        canvas.drawCircle(centerX + eyeOffsetX, centerY - eyeOffsetY, eyeRadius, eyePaint);

        // Draw smile (arc at bottom of face)
        float mouthWidth = radius * 0.6f;
        float mouthHeight = radius * 0.3f;
        float mouthY = centerY + radius * 0.15f;

        mouthRect.set(
            centerX - mouthWidth,
            mouthY - mouthHeight,
            centerX + mouthWidth,
            mouthY + mouthHeight
        );

        // Draw smile arc (from 20 to 160 degrees)
        canvas.drawArc(mouthRect, 20f, 140f, false, mouthPaint);
    }

    /**
     * Set the listening state - enables pulse animation and green glow.
     */
    public void setListening(boolean listening) {
        this.isListening = listening;
        this.isError = false;
        if (listening && !pulseAnimator.isRunning()) {
            pulseAnimator.start();
        } else if (!listening && pulseAnimator.isRunning()) {
            pulseAnimator.cancel();
            scale = 1.0f;
        }
        invalidate();
    }

    /**
     * Set error state - shows red glow.
     */
    public void setError(boolean error) {
        this.isError = error;
        if (error) {
            this.isListening = false;
            if (pulseAnimator.isRunning()) {
                pulseAnimator.cancel();
            }
            scale = 1.0f;
        }
        invalidate();
    }

    /**
     * Reset to default idle state.
     */
    public void setIdle() {
        this.isListening = false;
        this.isError = false;
        if (pulseAnimator.isRunning()) {
            pulseAnimator.cancel();
        }
        scale = 1.0f;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (pulseAnimator != null && pulseAnimator.isRunning()) {
            pulseAnimator.cancel();
        }
    }
}
