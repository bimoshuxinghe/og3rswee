package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LrcView extends View {

    public interface Callback {
        long getPosition();
    }

    private static final Pattern LRC_LINE = Pattern.compile("\\[(\\d+):(\\d+)(?:\\.(\\d+))?\\](.*)");
    private static final Pattern ENHANCED_LINE = Pattern.compile("\\[(\\d+),(\\d+)\\](.*)");
    private static final Pattern WORD_TAG = Pattern.compile("<(\\d+),(\\d+),\\d+>([^<>]*)");

    private final Paint currentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint normalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable updater = new Runnable() {
        @Override
        public void run() {
            invalidate();
            handler.postDelayed(this, 50);
        }
    };

    private List<LrcEntry> entries = new ArrayList<>();
    private Callback callback;
    private int currentIndex = -1;
    private float textSize = 42f;
    private float lineSpacing = 56f;
    private int currentColor = Color.parseColor("#FFD700");
    private int normalColor = Color.parseColor("#BDBDBD");
    private int padding = 24;

    public LrcView(Context context) {
        this(context, null);
    }

    public LrcView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LrcView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initPaint();
    }

    private void initPaint() {
        currentPaint.setTextSize(textSize);
        currentPaint.setColor(currentColor);
        currentPaint.setTextAlign(Paint.Align.CENTER);
        currentPaint.setTypeface(Typeface.DEFAULT_BOLD);

        normalPaint.setTextSize(textSize);
        normalPaint.setColor(normalColor);
        normalPaint.setTextAlign(Paint.Align.CENTER);

        outlinePaint.setTextSize(textSize);
        outlinePaint.setColor(Color.BLACK);
        outlinePaint.setTextAlign(Paint.Align.CENTER);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(4f);
        outlinePaint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void setData(String data) {
        stop();
        entries = parseLrc(data);
        currentIndex = -1;
        invalidate();
        if (!entries.isEmpty()) {
            start();
        }
    }

    public void start() {
        handler.removeCallbacks(updater);
        handler.post(updater);
    }

    public void stop() {
        handler.removeCallbacks(updater);
    }

    public void clear() {
        stop();
        entries.clear();
        currentIndex = -1;
        invalidate();
    }

    public boolean hasLrc() {
        return !entries.isEmpty();
    }

    private List<LrcEntry> parseLrc(String data) {
        List<LrcEntry> result = new ArrayList<>();
        if (TextUtils.isEmpty(data)) return result;
        String[] lines = data.replace("\r\n", "\n").split("\n");
        for (String line : lines) {
            Matcher enhanced = ENHANCED_LINE.matcher(line);
            if (enhanced.find()) {
                long start = Long.parseLong(enhanced.group(1));
                long duration = Long.parseLong(enhanced.group(2));
                String content = enhanced.group(3);
                List<WordEntry> words = new ArrayList<>();
                Matcher wordMatcher = WORD_TAG.matcher(content);
                StringBuilder sb = new StringBuilder();
                while (wordMatcher.find()) {
                    long wordStart = Long.parseLong(wordMatcher.group(1));
                    long wordDuration = Long.parseLong(wordMatcher.group(2));
                    String word = wordMatcher.group(3);
                    words.add(new WordEntry(word, wordStart, wordDuration));
                    sb.append(word);
                }
                result.add(new LrcEntry(start, duration, sb.toString(), words));
                continue;
            }
            Matcher standard = LRC_LINE.matcher(line);
            if (standard.find()) {
                long min = Long.parseLong(standard.group(1)) * 60000;
                long sec = Long.parseLong(standard.group(2)) * 1000;
                String msStr = standard.group(3);
                long ms = msStr != null ? Long.parseLong(msStr.padEnd(3, '0').substring(0, 3)) : 0;
                long start = min + sec + ms;
                String text = standard.group(4);
                if (!TextUtils.isEmpty(text)) {
                    result.add(new LrcEntry(start, 0, text, null));
                }
            }
        }
        Collections.sort(result, (a, b) -> Long.compare(a.start, b.start));
        for (int i = 0; i < result.size(); i++) {
            if (result.get(i).duration == 0 && i < result.size() - 1) {
                result.get(i).duration = result.get(i + 1).start - result.get(i).start;
            }
        }
        return result;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (entries.isEmpty()) return;
        long position = callback != null ? callback.getPosition() : 0;
        int newIndex = findCurrentIndex(position);
        if (newIndex != currentIndex) {
            currentIndex = newIndex;
        }
        if (currentIndex < 0) return;
        int width = getWidth();
        int height = getHeight();
        float centerY = height / 2f;
        float x = width / 2f;

        for (int i = 0; i < entries.size(); i++) {
            LrcEntry entry = entries.get(i);
            float y = centerY + (i - currentIndex) * lineSpacing;
            if (y < -lineSpacing || y > height + lineSpacing) continue;
            boolean isCurrent = (i == currentIndex);
            Paint textPaint = isCurrent ? currentPaint : normalPaint;
            Paint outline = isCurrent ? outlinePaint : null;
            String text = entry.text;
            if (isCurrent && entry.words != null && !entry.words.isEmpty()) {
                drawWordByWord(canvas, entry, position, x, y);
            } else {
                if (outline != null) canvas.drawText(text, x, y, outline);
                canvas.drawText(text, x, y, textPaint);
            }
        }
    }

    private void drawWordByWord(Canvas canvas, LrcEntry entry, long position, float x, float y) {
        float textWidth = currentPaint.measureText(entry.text);
        float startX = x - textWidth / 2f;
        float drawnWidth = 0f;
        for (WordEntry word : entry.words) {
            float wordWidth = currentPaint.measureText(word.text);
            long wordEnd = entry.start + word.start + word.duration;
            long wordStart = entry.start + word.start;
            float progress = 0f;
            if (position >= wordEnd) {
                progress = 1f;
            } else if (position > wordStart) {
                progress = (position - wordStart) / (float) word.duration;
            }
            float drawX = startX + drawnWidth;
            normalPaint.setTextAlign(Paint.Align.LEFT);
            currentPaint.setTextAlign(Paint.Align.LEFT);
            outlinePaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(word.text, drawX, y, normalPaint);
            if (progress > 0) {
                canvas.save();
                canvas.clipRect(drawX, y - textSize, drawX + wordWidth * progress, y + textSize);
                canvas.drawText(word.text, drawX, y, outlinePaint);
                canvas.drawText(word.text, drawX, y, currentPaint);
                canvas.restore();
            }
            drawnWidth += wordWidth;
        }
        normalPaint.setTextAlign(Paint.Align.CENTER);
        currentPaint.setTextAlign(Paint.Align.CENTER);
        outlinePaint.setTextAlign(Paint.Align.CENTER);
    }

    private int findCurrentIndex(long position) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (position >= entries.get(i).start) return i;
        }
        return 0;
    }

    private static class LrcEntry {
        long start;
        long duration;
        String text;
        List<WordEntry> words;

        LrcEntry(long start, long duration, String text, List<WordEntry> words) {
            this.start = start;
            this.duration = duration;
            this.text = text;
            this.words = words;
        }
    }

    private static class WordEntry {
        String text;
        long start;
        long duration;

        WordEntry(String text, long start, long duration) {
            this.text = text;
            this.start = start;
            this.duration = duration;
        }
    }
}
