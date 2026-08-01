package com.fongmi.android.tv.ui.custom;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.databinding.ViewReaderVodBinding;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.PicAdapter;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Prefers;

import org.json.JSONObject;

import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class VodReader {

    public interface Listener {
        void onSingleTap();
        void onDoubleTap();
        void onPrevious();
        void onNext();
        void onDirectory();
        void onPageChanged(int current, int total);
    }

    private static final String KEY_DIRECTION = "vod_reader_vertical";
    private static final int LONG_PRESS_DELAY = 3000;
    private static final float LONG_PRESS_SLOP = 24f;
    private final Activity activity;
    private final ViewReaderVodBinding binding;
    private final Listener listener;
    private final GestureDetector gestures;
    private final Handler longPressHandler = new Handler(Looper.getMainLooper());
    private final Runnable longPressRunnable = this::showDirectionDialog;
    private final List<String> pages = new ArrayList<>();
    private boolean active;
    private boolean novel;
    private boolean vertical;
    private String title = "";
    private String novelKey = "";
    private float novelDownY;
    private float downX;
    private float downY;
    private float longPressX;
    private float longPressY;

    public VodReader(Activity activity, ViewReaderVodBinding binding, Listener listener) {
        this.activity = activity;
        this.binding = binding;
        this.listener = listener;
        this.vertical = Prefers.getBoolean(KEY_DIRECTION, false);
        this.gestures = new GestureDetector(activity, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                if (novel) {
                    boolean show = binding.info.getVisibility() != View.VISIBLE;
                    binding.info.setVisibility(show ? View.VISIBLE : View.GONE);
                }
                listener.onSingleTap();
                return true;
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                listener.onDoubleTap();
                return true;
            }

            @Override
            public void onLongPress(@NonNull MotionEvent e) {
                // Disabled - using custom long press with 3 second delay
            }
        });
        RecyclerView recycler = (RecyclerView) binding.pager.getChildAt(0);
        recycler.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent event) {
                gestures.onTouchEvent(event);
                handleEdgeSwipe(event);
                handleCustomLongPress(event);
                return false;
            }
        });
        binding.pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updatePage(position);
            }
        });
        binding.novelScroll.setOnTouchListener((view, event) -> {
            gestures.onTouchEvent(event);
            handleNovelEdge(event);
            handleCustomLongPress(event);
            return false;
        });
        binding.novelScroll.setOnScrollChangeListener((androidx.core.widget.NestedScrollView.OnScrollChangeListener) (v, x, y, oldX, oldY) -> {
            if (novel && !novelKey.isEmpty()) Prefers.put(novelKey, y);
        });
    }

    public boolean set(Result result, String title) {
        this.title = title == null ? "" : title;
        binding.title.setText(this.title);
        binding.info.setVisibility(View.VISIBLE);
        String value = result == null ? "" : result.getUrl().v();
        if (value.startsWith("pics://")) {
            novel = false;
            boolean background = Setting.getPictureReaderMode() == 1;
            binding.title.setVisibility(background ? View.GONE : View.VISIBLE);
            binding.page.setVisibility(background ? View.GONE : View.VISIBLE);
            binding.info.setVisibility(background ? View.GONE : View.VISIBLE);
            binding.info.setBackgroundResource(R.drawable.bg_reader_info);
            binding.pager.setVisibility(View.VISIBLE);
            binding.novelScroll.setVisibility(View.GONE);
            List<String> images = parseImages(value.substring(7), result.getHeader());
            if (images.isEmpty()) return false;
            PicAdapter adapter = new PicAdapter(v -> {});
            adapter.setItems(images);
            binding.pager.setAdapter(adapter);
            pages.clear();
            pages.addAll(images);
        } else if (value.startsWith("novel://")) {
            novel = true;
            binding.pager.setVisibility(View.GONE);
            binding.novelScroll.setVisibility(View.VISIBLE);
            binding.title.setVisibility(View.VISIBLE);
            binding.page.setVisibility(View.GONE);
            binding.info.setVisibility(View.VISIBLE);
            binding.info.setBackgroundResource(R.drawable.bg_reader_info);
            String content = parseNovel(value.substring(8));
            if (TextUtils.isEmpty(content)) return false;
            pages.clear();
            binding.novelText.setText(content);
            novelKey = "novel_scroll_" + Integer.toHexString((this.title + content.length() + content.substring(0, Math.min(64, content.length()))).hashCode());
            binding.novelScroll.post(() -> binding.novelScroll.scrollTo(0, Prefers.getInt(novelKey, 0)));
        } else return false;
        active = true;
        binding.getRoot().setVisibility(View.VISIBLE);
        if (!novel) {
            binding.pager.setOffscreenPageLimit(1);
            setDirection(vertical);
            binding.pager.setCurrentItem(0, false);
            updatePage(0);
        }
        return true;
    }

    public void clear() {
        active = false;
        longPressHandler.removeCallbacks(longPressRunnable);
        if (novel && !novelKey.isEmpty()) Prefers.put(novelKey, binding.novelScroll.getScrollY());
        pages.clear();
        binding.novelText.setText("");
        binding.pager.setAdapter(null);
        binding.getRoot().setVisibility(View.GONE);
    }

    public boolean isActive() {
        return active;
    }

    public boolean isPicture() {
        return active && !novel;
    }

    public boolean isNovel() {
        return active && novel;
    }

    private List<String> parseImages(String value, Map<String, String> headers) {
        LinkedHashSet<String> images = new LinkedHashSet<>();
        String suffix = headers == null || headers.isEmpty() ? "" : "@Headers=" + App.gson().toJson(headers);
        for (String item : value.split("&&")) {
            String url = item.trim();
            if (TextUtils.isEmpty(url)) continue;
            if (!suffix.isEmpty() && !url.contains("@Headers=")) url += suffix;
            images.add(url);
        }
        return new ArrayList<>(images);
    }

    private String parseNovel(String value) {
        try {
            JSONObject object = new JSONObject(value);
            return clean(object.optString("content", ""));
        } catch (Exception e) {
            return clean(value);
        }
    }

    private String clean(String content) {
        return String.valueOf(content == null ? "" : content).replace("<br />", "\n").replace("<br/>", "\n").replace("<br>", "\n").replace("</p>", "\n\n").replaceAll("<[^>]+>", "").replace("&nbsp;", " ").trim();
    }

    private void handleNovelEdge(MotionEvent event) {
        if (!active || !novel) return;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            novelDownY = event.getY();
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            float delta = event.getY() - novelDownY;
            float threshold = activity.getResources().getDisplayMetrics().density * 88f;
            boolean top = binding.novelScroll.getScrollY() <= 0;
            boolean bottom = !binding.novelScroll.canScrollVertically(1);
            if (bottom && delta < -threshold) listener.onNext();
            else if (top && delta > threshold) listener.onPrevious();
        }
    }

    private void handleEdgeSwipe(MotionEvent event) {
        if (!active || pages.isEmpty()) return;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            float delta = vertical ? event.getY() - downY : event.getX() - downX;
            float threshold = activity.getResources().getDisplayMetrics().density * 72f;
            int position = binding.pager.getCurrentItem();
            if (delta < -threshold && position == pages.size() - 1) listener.onNext();
            else if (delta > threshold && position == 0) listener.onPrevious();
        }
    }

    private void handleCustomLongPress(MotionEvent event) {
        if (!active) return;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                longPressX = event.getX();
                longPressY = event.getY();
                longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_DELAY);
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(event.getX() - longPressX);
                float dy = Math.abs(event.getY() - longPressY);
                if (dx > LONG_PRESS_SLOP || dy > LONG_PRESS_SLOP) {
                    longPressHandler.removeCallbacks(longPressRunnable);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_UP:
                longPressHandler.removeCallbacks(longPressRunnable);
                break;
        }
    }

    private void showDirectionDialog() {
        if (!active) return;
        List<String> items = new ArrayList<>();
        if (!novel) {
            items.add(activity.getString(R.string.reader_horizontal));
            items.add(activity.getString(R.string.reader_vertical));
        }
        items.add(activity.getString(R.string.reader_previous));
        items.add(activity.getString(R.string.reader_directory));
        items.add(activity.getString(R.string.reader_next));
        if (!novel) items.add(activity.getString(R.string.reader_wallpaper));
        new AlertDialog.Builder(activity).setTitle(R.string.reader_menu).setItems(items.toArray(new String[0]), (dialog, which) -> {
            int offset = novel ? 0 : 2;
            if (!novel && which == 0) setDirection(false);
            else if (!novel && which == 1) setDirection(true);
            else if (which == offset) listener.onPrevious();
            else if (which == offset + 1) listener.onDirectory();
            else if (which == offset + 2) listener.onNext();
            else if (!novel && which == offset + 3) setWallpaper();
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void setWallpaper() {
        if (novel || pages.isEmpty()) return;
        String image = pages.get(Math.min(binding.pager.getCurrentItem(), pages.size() - 1));
        Notify.show(activity.getString(R.string.reader_wallpaper_loading));
        Task.execute(() -> {
            try {
                android.graphics.Bitmap bitmap = Glide.with(App.get()).asBitmap().load(ImgUtil.getUrl(image)).skipMemoryCache(true).diskCacheStrategy(DiskCacheStrategy.NONE).submit().get();
                try (FileOutputStream output = new FileOutputStream(FileUtil.getWallCache())) {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, output);
                } finally {
                    bitmap.recycle();
                }
                Setting.putWall(0);
                Setting.putWallType(0);
                App.post(() -> {
                    ConfigEvent.wall();
                    Notify.show(R.string.reader_wallpaper_done);
                });
            } catch (Throwable e) {
                App.post(() -> Notify.show(R.string.reader_wallpaper_failed));
            }
        });
    }

    private void setDirection(boolean vertical) {
        int position = binding.pager.getCurrentItem();
        this.vertical = vertical;
        Prefers.put(KEY_DIRECTION, vertical);
        binding.pager.setOrientation(vertical ? ViewPager2.ORIENTATION_VERTICAL : ViewPager2.ORIENTATION_HORIZONTAL);
        binding.pager.setCurrentItem(position, false);
    }

    private void updatePage(int position) {
        int total = pages.size();
        if (total > 0) {
            binding.page.setText("(" + (position + 1) + "/" + total + ")");
            listener.onPageChanged(position + 1, total);
        }
    }
}
