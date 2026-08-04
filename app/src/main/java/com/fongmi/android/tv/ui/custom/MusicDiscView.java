package com.fongmi.android.tv.ui.custom;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * A professional rotating vinyl disc view with album art and tonearm.
 * Inspired by jiefly/DiscView (https://github.com/jiefly/DiscView).
 * Redesigned with smooth ValueAnimator rotation and programmatic drawing.
 */
public class MusicDiscView extends View {

    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint albumPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groovePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private ValueAnimator rotateAnimator;
    private Bitmap albumBitmap;
    private Bitmap circleAlbum;

    private float rotation = 0f;
    private float needleRotation = -30f; // -30 = lifted, 0 = on disc
    private boolean isPlaying = false;

    private int discSize;
    private int centerX;
    private int centerY;
    private int albumRadius;
    private int discRadius;

    private static final float ALBUM_RATIO = 0.62f; // album art / disc
    private static final float CENTER_DOT_RATIO = 0.06f;
    private static final int ROTATE_DURATION = 20000; // 20s per revolution
    private static final int NEEDLE_DURATION = 300;

    public MusicDiscView(Context context) {
        this(context, null);
    }

    public MusicDiscView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MusicDiscView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        groovePaint.setStyle(Paint.Style.STROKE);
        groovePaint.setColor(Color.parseColor("#1A1A1A"));
        ringPaint.setColor(Color.parseColor("#333333"));
        needlePaint.setColor(Color.parseColor("#B0B0B0"));
        centerDotPaint.setColor(Color.parseColor("#FF5722"));
    }

    public void setAlbumBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            albumBitmap = null;
            circleAlbum = null;
            invalidate();
            return;
        }
        albumBitmap = bitmap;
        circleAlbum = createCircleBitmap(bitmap);
        invalidate();
    }

    public void setAlbumResource(int resId) {
        Drawable drawable = ContextCompat.getDrawable(getContext(), resId);
        if (drawable == null) return;
        if (drawable instanceof BitmapDrawable) {
            setAlbumBitmap(((BitmapDrawable) drawable).getBitmap());
        } else {
            Bitmap bmp = Bitmap.createBitmap(drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : 300,
                    drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : 300, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
            setAlbumBitmap(bmp);
        }
    }

    public void play() {
        if (isPlaying) return;
        isPlaying = true;
        startRotation();
        animateNeedle(0f);
    }

    public void pause() {
        if (!isPlaying) return;
        isPlaying = false;
        stopRotation();
        animateNeedle(-30f);
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    private void startRotation() {
        if (rotateAnimator != null && rotateAnimator.isRunning()) return;
        rotateAnimator = ValueAnimator.ofFloat(rotation, rotation + 360f);
        rotateAnimator.setDuration(ROTATE_DURATION);
        rotateAnimator.setInterpolator(new LinearInterpolator());
        rotateAnimator.setRepeatCount(ValueAnimator.INFINITE);
        rotateAnimator.addUpdateListener(animation -> {
            rotation = (float) animation.getAnimatedValue();
            invalidate();
        });
        rotateAnimator.start();
    }

    private void stopRotation() {
        if (rotateAnimator != null) {
            rotation = (float) rotateAnimator.getAnimatedValue();
            rotateAnimator.cancel();
            rotateAnimator = null;
        }
    }

    private void animateNeedle(float target) {
        ValueAnimator anim = ValueAnimator.ofFloat(needleRotation, target);
        anim.setDuration(NEEDLE_DURATION);
        anim.addUpdateListener(animation -> {
            needleRotation = (float) animation.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = Math.min(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec));
        if (size <= 0) size = 600;
        setMeasuredDimension(size, (int) (size * 1.2f));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        discSize = Math.min(w, (int) (h / 1.2f));
        centerX = w / 2;
        centerY = (int) (discSize / 2.0 + (h - discSize * 1.2) / 2 + discSize * 0.1);
        discRadius = discSize / 2;
        albumRadius = (int) (discRadius * ALBUM_RATIO);
        if (albumBitmap != null) {
            circleAlbum = createCircleBitmap(albumBitmap);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Draw disc (vinyl record)
        canvas.save();
        canvas.translate(centerX, centerY);
        canvas.rotate(rotation);

        // Outer dark ring
        discPaint.setColor(Color.parseColor("#1A1A1A"));
        canvas.drawCircle(0, 0, discRadius, discPaint);

        // Vinyl record body
        discPaint.setColor(Color.parseColor("#0D0D0D"));
        canvas.drawCircle(0, 0, discRadius - 4, discPaint);

        // Grooves
        int grooveCount = 8;
        for (int i = 1; i <= grooveCount; i++) {
            float r = discRadius - 4 - (discRadius * 0.35f * i / grooveCount);
            if (r < albumRadius + 10) break;
            groovePaint.setStrokeWidth(1f);
            groovePaint.setAlpha(30 + i * 8);
            canvas.drawCircle(0, 0, r, groovePaint);
        }

        // Album art (circular)
        if (circleAlbum != null) {
            float albumSize = albumRadius * 2;
            float left = -albumSize / 2f;
            float top = -albumSize / 2f;
            discPaint.reset();
            discPaint.setAntiAlias(true);
            canvas.drawBitmap(circleAlbum, left, top, discPaint);
        } else {
            // Default album art - gradient circle
            Shader shader = new RadialGradient(0, 0, albumRadius,
                    new int[]{Color.parseColor("#444444"), Color.parseColor("#222222")},
                    null, Shader.TileMode.CLAMP);
            discPaint.setShader(shader);
            canvas.drawCircle(0, 0, albumRadius, discPaint);
            discPaint.setShader(null);
        }

        // Ring around album
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(3f);
        canvas.drawCircle(0, 0, albumRadius + 2, ringPaint);

        // Center dot
        float dotR = discRadius * CENTER_DOT_RATIO;
        centerDotPaint.setColor(Color.parseColor("#333333"));
        canvas.drawCircle(0, 0, dotR, centerDotPaint);
        centerDotPaint.setColor(Color.parseColor("#FF5722"));
        canvas.drawCircle(0, 0, dotR * 0.4f, centerDotPaint);

        canvas.restore();

        // Draw tonearm (needle)
        drawNeedle(canvas);
    }

    private void drawNeedle(Canvas canvas) {
        canvas.save();
        // Pivot point at top-right area
        float pivotX = centerX + discRadius * 0.75f;
        float pivotY = centerY - discRadius * 0.85f;

        canvas.rotate(needleRotation, pivotX, pivotY);

        needlePaint.setColor(Color.parseColor("#999999"));
        needlePaint.setStyle(Paint.Style.FILL);

        // Base circle (pivot)
        canvas.drawCircle(pivotX, pivotY, discRadius * 0.08f, needlePaint);
        needlePaint.setColor(Color.parseColor("#666666"));
        canvas.drawCircle(pivotX, pivotY, discRadius * 0.05f, needlePaint);

        // Arm
        needlePaint.setColor(Color.parseColor("#B0B0B0"));
        needlePaint.setStrokeWidth(discRadius * 0.035f);
        needlePaint.setStyle(Paint.Style.STROKE);
        needlePaint.setStrokeCap(Paint.Cap.ROUND);

        // Draw arm from pivot toward disc center area
        float armEndX = centerX - discRadius * 0.05f;
        float armEndY = centerY - discRadius * 0.3f;
        canvas.drawLine(pivotX, pivotY, armEndX, armEndY, needlePaint);

        // Needle tip
        needlePaint.setColor(Color.parseColor("#CCCCCC"));
        needlePaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(armEndX, armEndY, discRadius * 0.025f, needlePaint);

        canvas.restore();
    }

    private Bitmap createCircleBitmap(Bitmap src) {
        if (src == null) return null;
        int targetSize = albumRadius * 2;
        if (targetSize <= 0) targetSize = Math.min(src.getWidth(), src.getHeight());

        // Scale source to square
        int minDim = Math.min(src.getWidth(), src.getHeight());
        float scale = (float) targetSize / minDim;
        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);
        Bitmap scaled = Bitmap.createBitmap(src, 0, 0, minDim, minDim, matrix, true);

        // Create circular bitmap
        Bitmap output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        canvas.drawCircle(targetSize / 2f, targetSize / 2f, targetSize / 2f, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(scaled, 0, 0, paint);
        paint.setXfermode(null);

        if (scaled != src && !scaled.isRecycled()) scaled.recycle();
        return output;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopRotation();
        if (circleAlbum != null && !circleAlbum.isRecycled() && circleAlbum != albumBitmap) {
            circleAlbum.recycle();
        }
    }
}
