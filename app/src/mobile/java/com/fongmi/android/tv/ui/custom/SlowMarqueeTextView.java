package com.fongmi.android.tv.ui.custom;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;

import androidx.appcompat.widget.AppCompatTextView;

import com.fongmi.android.tv.utils.ResUtil;

public class SlowMarqueeTextView extends AppCompatTextView {

    private ValueAnimator animator;
    private float offset;
    private float textWidth;
    private float contentWidth;
    private float loopWidth;

    public SlowMarqueeTextView(Context context) {
        super(context);
    }

    public SlowMarqueeTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SlowMarqueeTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(this::startMarquee);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopMarquee();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        post(this::startMarquee);
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        post(this::startMarquee);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!shouldMarquee()) {
            super.onDraw(canvas);
            return;
        }
        CharSequence text = getText();
        if (text == null) return;
        Paint.FontMetrics fontMetrics = getPaint().getFontMetrics();
        float centerY = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom()) / 2f;
        float baseline = centerY - (fontMetrics.ascent + fontMetrics.descent) / 2f;
        float startX = getPaddingLeft() - offset;
        canvas.save();
        canvas.clipRect(getPaddingLeft(), 0, getWidth() - getPaddingRight(), getHeight());
        while (startX > getPaddingLeft() - loopWidth) startX -= loopWidth;
        while (startX < getWidth() - getPaddingRight()) {
            canvas.drawText(text.toString(), startX, baseline, getPaint());
            startX += loopWidth;
        }
        canvas.restore();
    }

    private void startMarquee() {
        stopMarquee();
        if (getWidth() <= 0 || getText() == null) return;
        textWidth = getPaint().measureText(getText().toString());
        contentWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        if (!shouldMarquee()) {
            offset = 0;
            invalidate();
            return;
        }
        loopWidth = textWidth + ResUtil.dp2px(16);
        animator = ValueAnimator.ofFloat(0, loopWidth);
        animator.setStartDelay(1200);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.setInterpolator(new LinearInterpolator());
        animator.setDuration(Math.max(6000, (long) (loopWidth / ResUtil.dp2px(14) * 1000)));
        animator.addUpdateListener(animation -> {
            offset = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void stopMarquee() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        offset = 0;
    }

    private boolean shouldMarquee() {
        return textWidth > contentWidth && contentWidth > 0;
    }
}
