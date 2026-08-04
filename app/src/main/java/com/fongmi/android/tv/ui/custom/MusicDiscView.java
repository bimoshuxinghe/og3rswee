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
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * Turntable-style music disc view with 3D base, dynamic colors, and album art.
 * Features: acrylic-style base, vibrant vinyl disc, metallic tonearm, album art center.
 */
public class MusicDiscView extends View {

    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint albumPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groovePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private ValueAnimator rotateAnimator;
    private Bitmap albumBitmap;
    private Bitmap circleAlbum;

    private float rotation = 0f;
    private float needleRotation = -20f;
    private boolean isPlaying = false;

    private int centerX;
    private int centerY;
    private int discRadius;
    private int albumRadius;

    // Dynamic color scheme (updated per song)
    private int accentColor = Color.parseColor("#FF5722");
    private int discColor = Color.parseColor("#1A1A1A");
    private int baseColor = Color.parseColor("#2A2A2A");

    private static final float ALBUM_RATIO = 0.55f;
    private static final int ROTATE_DURATION = 16000;
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
    }

    public void setAccentColor(int color) {
        this.accentColor = color;
        invalidate();
    }

    public void setDiscColor(int color) {
        this.discColor = color;
        invalidate();
    }

    public void setBaseColor(int color) {
        this.baseColor = color;
        invalidate();
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
        animateNeedle(-20f);
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
        if (h <= 0) h = 380;
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        discRadius = Math.min(w, (int)(h * 0.72f)) / 2;
        centerX = w / 2;
        centerY = (int) (h * 0.40f);
        albumRadius = (int) (discRadius * ALBUM_RATIO);
        if (albumBitmap != null) {
            circleAlbum = createCircleBitmap(albumBitmap);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawTurntableBase(canvas);
        drawDiscShadow(canvas);
        drawVinylDisc(canvas);
        drawTonearm(canvas);
    }

    /**
     * Draw the turntable base - a rounded rectangle with acrylic/resin look.
     */
    private void drawTurntableBase(Canvas canvas) {
        float baseW = discRadius * 2.3f;
        float baseH = discRadius * 0.45f;
        float baseLeft = centerX - baseW / 2f;
        float baseTop = centerY + discRadius * 0.75f;
        RectF baseRect = new RectF(baseLeft, baseTop, baseLeft + baseW, baseTop + baseH);

        // Base shadow
        shadowPaint.setColor(Color.parseColor("#30000000"));
        canvas.drawRoundRect(
                new RectF(baseLeft + 4, baseTop + 6, baseLeft + baseW + 4, baseTop + baseH + 6),
                baseH / 2f, baseH / 2f, shadowPaint);

        // Base body with gradient
        Shader baseShader = new LinearGradient(0, baseTop, 0, baseTop + baseH,
                lighten(baseColor, 0.3f), darken(baseColor, 0.2f),
                Shader.TileMode.CLAMP);
        basePaint.setShader(baseShader);
        canvas.drawRoundRect(baseRect, baseH / 2f, baseH / 2f, basePaint);
        basePaint.setShader(null);

        // Top highlight (acrylic gloss)
        highlightPaint.setColor(Color.parseColor("#30FFFFFF"));
        canvas.drawRoundRect(
                new RectF(baseLeft + baseH * 0.3f, baseTop + 2, baseLeft + baseW - baseH * 0.3f, baseTop + baseH * 0.35f),
                baseH * 0.15f, baseH * 0.15f, highlightPaint);

        // Accent line on base front
        needlePaint.setColor(accentColor);
        needlePaint.setStrokeWidth(3f);
        needlePaint.setStyle(Paint.Style.STROKE);
        RectF accentRect = new RectF(baseLeft + baseW * 0.3f, baseTop + baseH * 0.6f,
                baseLeft + baseW * 0.7f, baseTop + baseH * 0.85f);
        canvas.drawRoundRect(accentRect, 4f, 4f, needlePaint);
        needlePaint.setStyle(Paint.Style.FILL);
    }

    private void drawDiscShadow(Canvas canvas) {
        canvas.save();
        canvas.translate(centerX, centerY + discRadius * 0.05f);
        shadowPaint.setColor(Color.parseColor("#35000000"));
        canvas.drawCircle(0, 0, discRadius * 1.05f, shadowPaint);
        shadowPaint.setColor(Color.parseColor("#18000000"));
        canvas.drawCircle(0, 0, discRadius * 1.12f, shadowPaint);
        canvas.restore();
    }

    private void drawVinylDisc(Canvas canvas) {
        canvas.save();
        canvas.translate(centerX, centerY);
        canvas.rotate(rotation);

        // Outer rim - accent colored
        Shader rimShader = new RadialGradient(0, 0, discRadius,
                new int[]{lighten(discColor, 0.15f), discColor, darken(discColor, 0.3f)},
                new float[]{0f, 0.85f, 1f}, Shader.TileMode.CLAMP);
        discPaint.setShader(rimShader);
        canvas.drawCircle(0, 0, discRadius, discPaint);
        discPaint.setShader(null);

        // Vinyl body (slightly darker than rim)
        discPaint.setColor(darken(discColor, 0.1f));
        canvas.drawCircle(0, 0, discRadius - 3, discPaint);

        // Grooves - dense concentric circles
        int grooveCount = 35;
        float grooveOuter = discRadius - 5;
        float grooveInner = albumRadius + 6;
        float grooveRange = grooveOuter - grooveInner;
        for (int i = 0; i < grooveCount; i++) {
            float t = (float) i / grooveCount;
            float r = grooveOuter - grooveRange * t;
            if (i % 4 == 0) {
                groovePaint.setColor(lighten(discColor, 0.08f));
                groovePaint.setAlpha(160);
                groovePaint.setStrokeWidth(1.2f);
            } else {
                groovePaint.setColor(darken(discColor, 0.05f));
                groovePaint.setAlpha(100);
                groovePaint.setStrokeWidth(0.6f);
            }
            canvas.drawCircle(0, 0, r, groovePaint);
        }

        // Light reflection sweep
        canvas.save();
        canvas.rotate(-rotation * 0.2f);
        int sweepColor = lighten(discColor, 0.2f);
        SweepGradientProxy lightSweep = new SweepGradientProxy(0, 0,
                new int[]{0x00FFFFFF, 0x20FFFFFF, 0x00FFFFFF, 0x10FFFFFF, 0x00FFFFFF});
        highlightPaint.setShader(lightSweep.create());
        highlightPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(0, 0, discRadius - 5, highlightPaint);
        highlightPaint.setShader(null);
        canvas.restore();

        // Album art center
        if (circleAlbum != null) {
            float albumSize = albumRadius * 2;
            float left = -albumSize / 2f;
            float top = -albumSize / 2f;
            albumPaint.reset();
            albumPaint.setAntiAlias(true);
            canvas.drawBitmap(circleAlbum, left, top, albumPaint);

            // Glossy reflection on album top half
            Shader gloss = new LinearGradient(0, -albumRadius, 0, 0,
                    new int[]{0x50FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
            albumPaint.setShader(gloss);
            albumPaint.setColor(Color.WHITE);
            canvas.drawCircle(0, 0, albumRadius, albumPaint);
            albumPaint.setShader(null);
        } else {
            // Default - accent gradient circle
            Shader shader = new RadialGradient(0, 0, albumRadius,
                    new int[]{lighten(accentColor, 0.2f), accentColor, darken(accentColor, 0.3f)},
                    null, Shader.TileMode.CLAMP);
            discPaint.setShader(shader);
            canvas.drawCircle(0, 0, albumRadius, discPaint);
            discPaint.setShader(null);
        }

        // Metallic ring around album
        ringPaint.setStrokeWidth(2.5f);
        ringPaint.setColor(lighten(baseColor, 0.3f));
        canvas.drawCircle(0, 0, albumRadius + 2, ringPaint);
        ringPaint.setStrokeWidth(1f);
        ringPaint.setColor(lighten(baseColor, 0.5f));
        canvas.drawCircle(0, 0, albumRadius + 4, ringPaint);

        // Center label
        float labelR = albumRadius * 0.12f;
        discPaint.setColor(accentColor);
        discPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(0, 0, labelR, discPaint);
        discPaint.setColor(darken(accentColor, 0.3f));
        canvas.drawCircle(0, 0, labelR * 0.5f, discPaint);

        canvas.restore();
    }

    private void drawTonearm(Canvas canvas) {
        canvas.save();
        float pivotX = centerX + discRadius * 0.70f;
        float pivotY = centerY - discRadius * 0.85f;
        canvas.rotate(needleRotation, pivotX, pivotY);

        // Base mount
        needlePaint.setStyle(Paint.Style.FILL);
        needlePaint.setColor(darken(baseColor, 0.1f));
        canvas.drawCircle(pivotX, pivotY, discRadius * 0.09f, needlePaint);
        needlePaint.setColor(lighten(baseColor, 0.2f));
        canvas.drawCircle(pivotX, pivotY, discRadius * 0.06f, needlePaint);
        needlePaint.setColor(lighten(baseColor, 0.4f));
        canvas.drawCircle(pivotX, pivotY, discRadius * 0.03f, needlePaint);

        // Arm with metallic gradient
        float armEndX = centerX - discRadius * 0.05f;
        float armEndY = centerY - discRadius * 0.2f;
        Shader armShader = new LinearGradient(pivotX, pivotY, armEndX, armEndY,
                new int[]{0xAAAAAA, 0xDDDDDDFF, 0x888888FF},
                null, Shader.TileMode.CLAMP);
        needlePaint.setShader(armShader);
        needlePaint.setStyle(Paint.Style.STROKE);
        needlePaint.setStrokeWidth(discRadius * 0.035f);
        needlePaint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(pivotX, pivotY, armEndX, armEndY, needlePaint);
        needlePaint.setShader(null);

        // Highlight line
        needlePaint.setColor(0xFFEEEEEE);
        needlePaint.setStrokeWidth(discRadius * 0.01f);
        canvas.drawLine(pivotX, pivotY - discRadius * 0.012f, armEndX, armEndY - discRadius * 0.012f, needlePaint);

        // Headshell
        needlePaint.setStyle(Paint.Style.FILL);
        needlePaint.setColor(0xFF333333);
        RectF headshell = new RectF(
                armEndX - discRadius * 0.05f, armEndY - discRadius * 0.03f,
                armEndX + discRadius * 0.05f, armEndY + discRadius * 0.02f);
        canvas.drawRoundRect(headshell, discRadius * 0.02f, discRadius * 0.02f, needlePaint);

        // Stylus
        needlePaint.setColor(0xFFCCCCCC);
        canvas.drawCircle(armEndX, armEndY + discRadius * 0.025f, discRadius * 0.012f, needlePaint);

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

    // Color utility methods
    private int lighten(int color, float amount) {
        int r = (int) (Color.red(color) + (255 - Color.red(color)) * amount);
        int g = (int) (Color.green(color) + (255 - Color.green(color)) * amount);
        int b = (int) (Color.blue(color) + (255 - Color.blue(color)) * amount);
        return Color.rgb(r, g, b);
    }

    private int darken(int color, float amount) {
        int r = (int) (Color.red(color) * (1 - amount));
        int g = (int) (Color.green(color) * (1 - amount));
        int b = (int) (Color.blue(color) * (1 - amount));
        return Color.rgb(r, g, b);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopRotation();
        if (circleAlbum != null && !circleAlbum.isRecycled() && circleAlbum != albumBitmap) {
            circleAlbum.recycle();
        }
    }

    // Helper class to create SweepGradient with proper alpha
    private static class SweepGradientProxy {
        private final float cx, cy;
        private final int[] colors;

        SweepGradientProxy(float cx, float cy, int[] colors) {
            this.cx = cx;
            this.cy = cy;
            this.colors = colors;
        }

        Shader create() {
            return new android.graphics.SweepGradient(cx, cy, colors, null);
        }
    }
}
