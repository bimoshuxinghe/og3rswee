package com.fongmi.android.tv.ui.custom;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * Professional 3D-style vinyl disc view with realistic lighting, grooves, and tonearm.
 * Inspired by RetroBeat's programmatic groove drawing and DiscView's tonearm mechanics.
 * Uses Canvas perspective tricks (elliptical compression + light sweep) to simulate 3D depth.
 */
public class MusicDiscView extends View {

    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint albumPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groovePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private ValueAnimator rotateAnimator;
    private Bitmap albumBitmap;
    private Bitmap circleAlbum;

    private float rotation = 0f;
    private float needleRotation = -25f;
    private boolean isPlaying = false;

    private int viewWidth;
    private int viewHeight;
    private int centerX;
    private int centerY;
    private int discRadius;
    private int albumRadius;

    // 3D perspective parameters
    private static final float PERSPECTIVE_Y_RATIO = 0.88f; // vertical compression for 3D look
    private static final float ALBUM_RATIO = 0.58f;
    private static final float CENTER_DOT_RATIO = 0.05f;
    private static final int ROTATE_DURATION = 18000; // 18s per revolution
    private static final int NEEDLE_DURATION = 400;

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
        ringPaint.setStyle(Paint.Style.STROKE);
        needlePaint.setStyle(Paint.Style.FILL);
        setLayerType(LAYER_TYPE_HARDWARE, null);
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
        animateNeedle(-25f);
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
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(animation -> {
            needleRotation = (float) animation.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        if (w <= 0) w = 320;
        if (h <= 0) h = 400;
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        viewWidth = w;
        viewHeight = h;
        discRadius = Math.min(w, (int)(h / 1.15f)) / 2;
        centerX = w / 2;
        centerY = (int) (h * 0.42f);
        albumRadius = (int) (discRadius * ALBUM_RATIO);
        if (albumBitmap != null) {
            circleAlbum = createCircleBitmap(albumBitmap);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Draw shadow under disc (3D depth)
        drawDiscShadow(canvas);

        // Draw vinyl disc with 3D perspective
        drawVinylDisc(canvas);

        // Draw tonearm
        drawTonearm(canvas);
    }

    private void drawDiscShadow(Canvas canvas) {
        canvas.save();
        canvas.translate(centerX, centerY + discRadius * 0.08f);
        shadowPaint.setColor(Color.parseColor("#40000000"));
        shadowPaint.setAntiAlias(true);
        canvas.drawCircle(0, 0, discRadius * 1.02f, shadowPaint);
        shadowPaint.setColor(Color.parseColor("#20000000"));
        canvas.drawCircle(0, 0, discRadius * 1.08f, shadowPaint);
        canvas.restore();
    }

    private void drawVinylDisc(Canvas canvas) {
        canvas.save();
        canvas.translate(centerX, centerY);
        // Apply vertical compression for 3D perspective look
        canvas.scale(1f, PERSPECTIVE_Y_RATIO);
        canvas.rotate(rotation);

        // Outer rim - glossy black with light reflection
        Shader rimShader = new RadialGradient(0, 0, discRadius,
                new int[]{Color.parseColor("#2A2A2A"), Color.parseColor("#0A0A0A"), Color.parseColor("#1A1A1A")},
                new float[]{0f, 0.7f, 1f}, Shader.TileMode.CLAMP);
        discPaint.setShader(rimShader);
        canvas.drawCircle(0, 0, discRadius, discPaint);
        discPaint.setShader(null);

        // Vinyl body
        discPaint.setColor(Color.parseColor("#080808"));
        canvas.drawCircle(0, 0, discRadius - 3, discPaint);

        // Grooves - dense concentric circles for realistic vinyl texture
        int grooveCount = 40;
        float grooveOuterStart = discRadius - 5;
        float grooveInnerEnd = albumRadius + 8;
        float grooveRange = grooveOuterStart - grooveInnerEnd;
        for (int i = 0; i < grooveCount; i++) {
            float t = (float) i / grooveCount;
            float r = grooveOuterStart - grooveRange * t;
            // Alternate between dark and slightly lighter grooves
            if (i % 3 == 0) {
                groovePaint.setColor(Color.parseColor("#1C1C1C"));
                groovePaint.setAlpha(180);
            } else {
                groovePaint.setColor(Color.parseColor("#101010"));
                groovePaint.setAlpha(120);
            }
            groovePaint.setStrokeWidth(i % 5 == 0 ? 1.5f : 0.8f);
            canvas.drawCircle(0, 0, r, groovePaint);
        }

        // Light reflection sweep - rotating highlight for 3D effect
        canvas.save();
        canvas.rotate(-rotation * 0.3f); // counter-rotate slowly for light effect
        SweepGradient lightSweep = new SweepGradient(0, 0,
                new int[]{Color.parseColor("#00FFFFFF"), Color.parseColor("#15FFFFFF"),
                        Color.parseColor("#00FFFFFF"), Color.parseColor("#08FFFFFF"),
                        Color.parseColor("#00FFFFFF")},
                null);
        highlightPaint.setShader(lightSweep);
        highlightPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(0, 0, discRadius - 5, highlightPaint);
        highlightPaint.setShader(null);
        canvas.restore();

        // Album art (circular)
        if (circleAlbum != null) {
            float albumSize = albumRadius * 2;
            float left = -albumSize / 2f;
            float top = -albumSize / 2f;
            albumPaint.reset();
            albumPaint.setAntiAlias(true);
            canvas.drawBitmap(circleAlbum, left, top, albumPaint);

            // Glossy reflection on album art (top half)
            Shader glossShader = new LinearGradient(0, -albumRadius, 0, 0,
                    new int[]{Color.parseColor("#40FFFFFF"), Color.parseColor("#00FFFFFF")},
                    null, Shader.TileMode.CLAMP);
            albumPaint.setShader(glossShader);
            canvas.drawCircle(0, 0, albumRadius, albumPaint);
            albumPaint.setShader(null);
        } else {
            // Default album art - dark gradient with subtle pattern
            Shader shader = new RadialGradient(0, 0, albumRadius,
                    new int[]{Color.parseColor("#3A3A3A"), Color.parseColor("#1A1A1A"), Color.parseColor("#0A0A0A")},
                    null, Shader.TileMode.CLAMP);
            discPaint.setShader(shader);
            canvas.drawCircle(0, 0, albumRadius, discPaint);
            discPaint.setShader(null);
        }

        // Ring around album (metallic look)
        ringPaint.setStrokeWidth(2f);
        ringPaint.setColor(Color.parseColor("#444444"));
        canvas.drawCircle(0, 0, albumRadius + 1, ringPaint);
        ringPaint.setStrokeWidth(1f);
        ringPaint.setColor(Color.parseColor("#666666"));
        canvas.drawCircle(0, 0, albumRadius + 3, ringPaint);

        // Center label (the small circle in the middle of vinyl)
        float labelR = albumRadius * 0.15f;
        labelPaint.setColor(Color.parseColor("#FF5722"));
        labelPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(0, 0, labelR, labelPaint);
        labelPaint.setColor(Color.parseColor("#CC3D00"));
        canvas.drawCircle(0, 0, labelR * 0.6f, labelPaint);

        // Center hole
        float dotR = discRadius * CENTER_DOT_RATIO;
        centerDotPaint.setColor(Color.parseColor("#000000"));
        centerDotPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(0, 0, dotR * 0.3f, centerDotPaint);

        canvas.restore();
    }

    private void drawTonearm(Canvas canvas) {
        canvas.save();

        // Pivot point at upper right area
        float pivotX = centerX + discRadius * 0.72f;
        float pivotY = centerY - discRadius * 0.92f;

        // Apply same perspective compression to tonearm area
        canvas.scale(1f, PERSPECTIVE_Y_RATIO, pivotX, pivotY);
        canvas.rotate(needleRotation, pivotX, pivotY);

        // Tonearm base mount (cylinder shape)
        needlePaint.setStyle(Paint.Style.FILL);
        needlePaint.setColor(Color.parseColor("#555555"));
        canvas.drawCircle(pivotX, pivotY, discRadius * 0.1f, needlePaint);
        needlePaint.setColor(Color.parseColor("#777777"));
        canvas.drawCircle(pivotX, pivotY, discRadius * 0.07f, needlePaint);
        needlePaint.setColor(Color.parseColor("#999999"));
        canvas.drawCircle(pivotX, pivotY, discRadius * 0.04f, needlePaint);

        // Tonearm shaft (with gradient for metallic look)
        float armEndX = centerX - discRadius * 0.08f;
        float armEndY = centerY - discRadius * 0.25f;

        Shader armShader = new LinearGradient(pivotX, pivotY, armEndX, armEndY,
                new int[]{Color.parseColor("#AAAAAA"), Color.parseColor("#DDDDDD"), Color.parseColor("#888888")},
                null, Shader.TileMode.CLAMP);
        needlePaint.setShader(armShader);
        needlePaint.setStyle(Paint.Style.STROKE);
        needlePaint.setStrokeWidth(discRadius * 0.04f);
        needlePaint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(pivotX, pivotY, armEndX, armEndY, needlePaint);
        needlePaint.setShader(null);

        // Tonearm highlight (thin bright line on top)
        needlePaint.setColor(Color.parseColor("#EEEEEE"));
        needlePaint.setStrokeWidth(discRadius * 0.012f);
        canvas.drawLine(pivotX, pivotY - discRadius * 0.015f, armEndX, armEndY - discRadius * 0.015f, needlePaint);

        // Cartridge/headshell at the end of tonearm
        needlePaint.setStyle(Paint.Style.FILL);
        needlePaint.setColor(Color.parseColor("#333333"));
        RectF headshell = new RectF(
                armEndX - discRadius * 0.06f, armEndY - discRadius * 0.04f,
                armEndX + discRadius * 0.06f, armEndY + discRadius * 0.02f);
        canvas.drawRoundRect(headshell, discRadius * 0.02f, discRadius * 0.02f, needlePaint);

        // Needle tip (stylus)
        needlePaint.setColor(Color.parseColor("#CCCCCC"));
        canvas.drawCircle(armEndX, armEndY + discRadius * 0.03f, discRadius * 0.015f, needlePaint);

        canvas.restore();
    }

    private Bitmap createCircleBitmap(Bitmap src) {
        if (src == null) return null;
        int targetSize = albumRadius * 2;
        if (targetSize <= 0) targetSize = Math.min(src.getWidth(), src.getHeight());

        int minDim = Math.min(src.getWidth(), src.getHeight());
        float scale = (float) targetSize / minDim;
        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);
        Bitmap scaled = Bitmap.createBitmap(src, 0, 0, minDim, minDim, matrix, true);

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
