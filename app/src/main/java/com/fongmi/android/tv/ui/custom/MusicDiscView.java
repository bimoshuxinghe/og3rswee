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
 * Rotating circular album art view - like a CD spinning.
 * Simple, clean, modern style with ring glow effect.
 */
public class MusicDiscView extends View {

    private final Paint albumPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private ValueAnimator rotateAnimator;
    private Bitmap albumBitmap;
    private Bitmap circleAlbum;

    private float rotation = 0f;
    private boolean isPlaying = false;

    private int centerX;
    private int centerY;
    private int discRadius;

    private int accentColor = Color.parseColor("#FF5722");
    private int ringColor = Color.parseColor("#40FFFFFF");

    private static final int ROTATE_DURATION = 20000;

    public MusicDiscView(Context context) {
        this(context, null);
    }

    public MusicDiscView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MusicDiscView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setAccentColor(int color) {
        this.accentColor = color;
        this.ringColor = lighten(color, 0.3f) | 0x60000000;
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
    }

    public void pause() {
        if (!isPlaying) return;
        isPlaying = false;
        stopRotation();
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

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int w = getMeasuredWidth();
        int h = getMeasuredHeight();
        int size = Math.min(w, h);
        if (size <= 0) size = 300;
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Always use the smaller dimension to ensure perfect circle
        int size = Math.min(w, h);
        discRadius = size / 2;
        centerX = w / 2;
        centerY = h / 2;
        if (albumBitmap != null) {
            circleAlbum = createCircleBitmap(albumBitmap);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Safety: recalculate dimensions if they seem stale
        int w = getWidth();
        int h = getHeight();
        if (w > 0 && h > 0 && (centerX != w / 2 || centerY != h / 2 || discRadius != Math.min(w, h) / 2)) {
            int size = Math.min(w, h);
            discRadius = size / 2;
            centerX = w / 2;
            centerY = h / 2;
        }
        if (discRadius <= 0) return;

        // Outer glow ring (subtle accent color)
        glowPaint.setAntiAlias(true);
        Shader glowShader = new RadialGradient(centerX, centerY, discRadius,
                new int[]{0x00000000, ringColor & 0x30FFFFFF, 0x00000000},
                new float[]{0.85f, 0.95f, 1f}, Shader.TileMode.CLAMP);
        glowPaint.setShader(glowShader);
        canvas.drawCircle(centerX, centerY, discRadius, glowPaint);
        glowPaint.setShader(null);

        // Outer decorative ring
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(3f);
        ringPaint.setColor(ringColor);
        canvas.drawCircle(centerX, centerY, discRadius - 4, ringPaint);

        // Inner decorative ring (thinner)
        ringPaint.setStrokeWidth(1f);
        ringPaint.setColor(0x20FFFFFF);
        canvas.drawCircle(centerX, centerY, discRadius - 10, ringPaint);

        // Rotate and draw album art
        canvas.save();
        canvas.translate(centerX, centerY);
        canvas.rotate(rotation);

        if (circleAlbum != null) {
            float albumSize = (discRadius - 14) * 2;
            float left = -albumSize / 2f;
            float top = -albumSize / 2f;
            albumPaint.reset();
            albumPaint.setAntiAlias(true);
            canvas.drawBitmap(circleAlbum, left, top, albumPaint);
        } else {
            // Default - accent gradient
            Shader shader = new RadialGradient(0, 0, discRadius - 14,
                    new int[]{lighten(accentColor, 0.2f), accentColor, darken(accentColor, 0.3f)},
                    null, Shader.TileMode.CLAMP);
            albumPaint.setShader(shader);
            albumPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(0, 0, discRadius - 14, albumPaint);
            albumPaint.setShader(null);
        }

        canvas.restore();
    }

    private Bitmap createCircleBitmap(Bitmap src) {
        if (src == null) return null;
        int targetSize = (discRadius - 14) * 2;
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
}
