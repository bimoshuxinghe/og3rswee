package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.DoubanApi;
import com.fongmi.android.tv.databinding.AdapterHotPhotoBinding;
import com.fongmi.android.tv.databinding.ActivityHotDetailBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class HotDetailActivity extends BaseActivity {

    private static final Map<String, DetailCache> DETAIL_CACHE = new HashMap<>();

    private ActivityHotDetailBinding mBinding;
    private PhotoAdapter mPhotoAdapter;
    private String title;
    private String bannerUrl;

    public static void start(Activity activity, String id, String name, String pic) {
        start(activity, id, name, pic, "");
    }

    public static void start(Activity activity, String id, String name, String pic, String tag) {
        Intent intent = new Intent(activity, HotDetailActivity.class);
        intent.putExtra("id", id);
        intent.putExtra("name", name);
        intent.putExtra("pic", pic);
        intent.putExtra("tag", tag);
        activity.startActivity(intent);
        activity.overridePendingTransition(R.anim.hot_detail_enter, R.anim.hot_detail_hold);
    }

    private String getId() {
        return getIntent().getStringExtra("id");
    }

    private String getName() {
        return getIntent().getStringExtra("name");
    }

    private String getPic() {
        return getIntent().getStringExtra("pic");
    }

    private String getTag() {
        return getIntent().getStringExtra("tag");
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHotDetailBinding.inflate(getLayoutInflater());
    }

    @Override
    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowTitleEnabled(false);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        title = getName();
        setSupportActionBar(mBinding.toolbar);
        setPhotoRecycler();
        setInsets();
        setBase();
        loadDetail();
    }

    @Override
    protected void initEvent() {
        mBinding.banner.setOnClickListener(view -> previewPhoto(bannerUrl));
        mBinding.searchWatch.setOnClickListener(view -> SearchActivity.start(this, title));
    }

    private void setInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.getRoot(), (root, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            int bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            mBinding.statusBar.getLayoutParams().height = top;
            mBinding.statusBar.requestLayout();
            mBinding.toolbar.setPadding(mBinding.toolbar.getPaddingLeft(), top, mBinding.toolbar.getPaddingRight(), 0);
            mBinding.toolbar.getLayoutParams().height = ResUtil.dp2px(56) + top;
            mBinding.toolbar.requestLayout();
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) mBinding.content.getLayoutParams();
            params.bottomMargin = bottom;
            mBinding.content.setLayoutParams(params);
            return insets;
        });
    }

    private void setPhotoRecycler() {
        mBinding.photos.setHasFixedSize(false);
        mBinding.photos.setNestedScrollingEnabled(false);
        mBinding.photos.setLayoutManager(new GridLayoutManager(this, 2));
        mBinding.photos.addItemDecoration(new SpaceItemDecoration(2, 8));
        mBinding.photos.setAdapter(mPhotoAdapter = new PhotoAdapter());
    }

    private void setBase() {
        mBinding.title.setText(title);
        mBinding.airDate.setVisibility(View.GONE);
        mBinding.play.setVisibility(View.GONE);
        mBinding.search.setVisibility(View.GONE);
        setBanner(getPic());
        mBinding.progressLayout.showContent();
    }

    private void loadDetail() {
        String id = getId();
        DetailCache cache = DETAIL_CACHE.get(id);
        if (cache != null) {
            applyDetail(cache);
            return;
        }
        mBinding.progressLayout.showProgress();
        Task.executor().submit(() -> {
            try {
                boolean zhuiju = DoubanApi.isZhuiJu(id);
                JSONObject object = zhuiju ? DoubanApi.zhuijuDetail(id) : DoubanApi.detail(id);
                List<DoubanApi.Photo> photos = zhuiju ? DoubanApi.zhuijuPhotos(object) : DoubanApi.photos(getId());
                DetailCache detail = new DetailCache(zhuiju, object, photos);
                DETAIL_CACHE.put(id, detail);
                App.post(() -> applyDetail(detail));
            } catch (Exception e) {
                App.post(() -> {
                    mBinding.progressLayout.showContent();
                    Notify.show(e.getMessage());
                });
            }
        });
    }

    private void applyDetail(DetailCache detail) {
        if (detail.zhuiju) setZhuiJuDetail(detail.object);
        else setDetail(detail.object);
        setPhotos(detail.photos);
        mBinding.progressLayout.showContent();
    }

    private void setDetail(JSONObject object) {
        title = object.optString("title", title);
        String poster = DoubanApi.pic(object);
        mBinding.title.setText(title);
        setText(mBinding.score, score(object));
        setText(mBinding.other, leftInfo(object));
        setText(mBinding.director, getString(R.string.hot_director, names(object.optJSONArray("directors"))));
        setText(mBinding.actor, getString(R.string.hot_actor, names(object.optJSONArray("actors"))));
        setText(mBinding.extra, rightInfo(object));
        mBinding.intro.setText(object.optString("intro"));
        setBanner(TextUtils.isEmpty(poster) ? getPic() : poster);
    }

    private void setZhuiJuDetail(JSONObject object) {
        title = first(object.optString("name"), object.optString("title"), title);
        String backdrop = object.optString("backdrop_path");
        String poster = object.optString("poster_path");
        String image = DoubanApi.zhuijuImage(TextUtils.isEmpty(backdrop) ? poster : backdrop, TextUtils.isEmpty(backdrop) ? "w342" : "w780");
        mBinding.title.setText(title);
        setText(mBinding.score, zhuijuScore(object));
        setText(mBinding.other, zhuijuLeftInfo(object));
        setText(mBinding.director, label("播放平台", names(object.optJSONArray("networks"))));
        setText(mBinding.actor, "");
        setText(mBinding.extra, zhuijuRightInfo(object));
        setAirDate(object);
        mBinding.intro.setText(object.optString("overview"));
        setBanner(TextUtils.isEmpty(image) ? getPic() : image);
    }

    private void setPhotos(List<DoubanApi.Photo> photos) {
        if (photos == null || photos.isEmpty()) {
            mBinding.photoLayout.setVisibility(View.GONE);
            return;
        }
        for (DoubanApi.Photo photo : photos) {
            if (photo.isLand()) {
                setBanner(photo.url);
                break;
            }
        }
        mBinding.photoCount.setText("全部" + photos.size() + "张 ›");
        mPhotoAdapter.setItems(photos);
        mBinding.photoLayout.setVisibility(View.VISIBLE);
    }

    private void setBanner(String url) {
        bannerUrl = url;
        ImgUtil.load(title, url, mBinding.banner);
    }

    private void previewPhoto(String url) {
        if (TextUtils.isEmpty(url)) return;
        Dialog dialog = new Dialog(this);
        AppCompatImageView image = new AppCompatImageView(this);
        image.setBackgroundColor(Color.BLACK);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setOnClickListener(view -> dialog.dismiss());
        dialog.setContentView(image, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImgUtil.load(title, url, image, false);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    private void setText(View view, String text) {
        if (view instanceof TextView) ((TextView) view).setText(text);
        view.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
    }

    private String score(JSONObject object) {
        String rating = rating(object);
        return TextUtils.isEmpty(rating) ? "" : rating + "分";
    }

    private String leftInfo(JSONObject object) {
        List<String> lines = new ArrayList<>();
        addRaw(lines, "中文名", title);
        addRaw(lines, "年代", year(texts(object.optJSONArray("pubdate"))));
        addRaw(lines, "类型", texts(object.optJSONArray("genres")));
        addRaw(lines, "地区", texts(object.optJSONArray("countries")));
        return TextUtils.join("\n", lines);
    }

    private String rightInfo(JSONObject object) {
        List<String> lines = new ArrayList<>();
        addRaw(lines, "首播", texts(object.optJSONArray("pubdate")));
        addRaw(lines, "集数", episode(object));
        addRaw(lines, "片长", texts(object.optJSONArray("durations")));
        addRaw(lines, "又名", texts(object.optJSONArray("aka")));
        return TextUtils.join("\n", lines);
    }

    private String zhuijuLeftInfo(JSONObject object) {
        List<String> lines = new ArrayList<>();
        String firstDate = zhuijuAirDate(object);
        addRaw(lines, "中文名", title);
        addRaw(lines, "年代", year(firstDate));
        addRaw(lines, "类型", names(object.optJSONArray("genres")));
        addRaw(lines, "地区", texts(object.optJSONArray("origin_country")));
        return TextUtils.join("\n", lines);
    }

    private String zhuijuRightInfo(JSONObject object) {
        List<String> lines = new ArrayList<>();
        String firstDate = zhuijuAirDate(object);
        if (!"radar".equals(getTag())) addRaw(lines, "首播", displayAirDate(firstDate));
        addRaw(lines, "状态", zhuijuStatus(object.optString("status")));
        addRaw(lines, "集数", zhuijuEpisodes(object));
        addRaw(lines, "片长", zhuijuDuration(object));
        return TextUtils.join("\n", lines);
    }

    private String zhuijuAirDate(JSONObject object) {
        JSONObject next = object.optJSONObject("next_episode_to_air");
        return first(object.optString("first_air_date"), object.optString("release_date"), next == null ? "" : next.optString("air_date"), object.optString("last_air_date"));
    }

    private void setAirDate(JSONObject object) {
        if (!"radar".equals(getTag())) {
            mBinding.airDate.setVisibility(View.GONE);
            return;
        }
        String text = airDateText(zhuijuAirDate(object));
        mBinding.airDate.setText(text);
        mBinding.airDate.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
    }

    private String airDateText(String date) {
        try {
            if (TextUtils.isEmpty(date) || date.length() < 10) return "";
            String day = date.substring(0, 10);
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Calendar target = Calendar.getInstance();
            Calendar today = Calendar.getInstance();
            target.setTime(format.parse(day));
            clearTime(target);
            clearTime(today);
            long diff = TimeUnit.MILLISECONDS.toDays(target.getTimeInMillis() - today.getTimeInMillis());
            String state = diff < 0 ? "已上线" : diff == 0 ? "今天上线" : diff == 1 ? "明天上线" : "距上线还有 " + diff + " 天";
            return "上线 " + displayAirDate(day) + " · " + state;
        } catch (Exception e) {
            return "";
        }
    }

    private void clearTime(Calendar calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
    }

    private String displayAirDate(String date) {
        try {
            if (TextUtils.isEmpty(date) || date.length() < 10) return date;
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date.substring(0, 10)));
            String[] weeks = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
            return date.substring(0, 10) + " " + weeks[calendar.get(Calendar.DAY_OF_WEEK) - 1];
        } catch (Exception e) {
            return date;
        }
    }

    private String zhuijuScore(JSONObject object) {
        double value = object.optDouble("real_vote_average", object.optDouble("vote_average", 0));
        return value <= 0 ? "" : value + "分";
    }

    private String zhuijuEpisodes(JSONObject object) {
        int count = object.optInt("number_of_episodes");
        return count <= 0 ? "" : count + "集";
    }

    private String zhuijuDuration(JSONObject object) {
        JSONArray array = object.optJSONArray("episode_run_time");
        List<String> values = new ArrayList<>();
        if (array != null) for (int i = 0; i < array.length(); i++) {
            int minute = array.optInt(i);
            if (minute > 0) values.add(minute + "分钟");
        }
        return TextUtils.join(" / ", values);
    }

    private String zhuijuStatus(String status) {
        if ("Returning Series".equals(status)) return "连载中";
        if ("Ended".equals(status)) return "已完结";
        if ("In Production".equals(status)) return "制作中";
        if ("Canceled".equals(status) || "Cancelled".equals(status)) return "已取消";
        return status;
    }

    private String label(String name, String value) {
        return TextUtils.isEmpty(value) ? "" : name + "：" + value;
    }

    private void addRaw(List<String> lines, String name, String value) {
        if (!TextUtils.isEmpty(value)) lines.add(name + "  " + value);
    }

    private void add(List<String> items, String value) {
        if (!TextUtils.isEmpty(value)) items.add(value);
    }

    private void add(List<String> lines, int name, String value) {
        if (!TextUtils.isEmpty(value)) lines.add(getString(name, value));
    }

    private String rating(JSONObject object) {
        JSONObject rating = object.optJSONObject("rating");
        if (rating == null || rating.optDouble("value") <= 0) return "";
        return String.valueOf(rating.optDouble("value"));
    }

    private String episode(JSONObject object) {
        int count = object.optInt("episodes_count");
        return count <= 0 ? "" : getString(R.string.hot_episode_count, count);
    }

    private String year(String text) {
        if (TextUtils.isEmpty(text) || text.length() < 4) return "";
        return text.substring(0, 4);
    }

    private String names(JSONArray array) {
        if (array == null) return "";
        List<String> names = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object != null && !object.optString("name").isEmpty()) names.add(object.optString("name"));
        }
        return TextUtils.join(" / ", names);
    }

    private String texts(JSONArray array) {
        if (array == null) return "";
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String text = array.optString(i);
            if (!TextUtils.isEmpty(text)) texts.add(text);
        }
        return TextUtils.join(" / ", texts);
    }

    private String first(String... values) {
        for (String value : values) if (!TextUtils.isEmpty(value)) return value;
        return "";
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.hot_detail_hold, R.anim.hot_detail_exit);
    }

    class PhotoAdapter extends RecyclerView.Adapter<PhotoAdapter.ViewHolder> {

        private final List<PhotoColumn> items = new ArrayList<>();

        public void setItems(List<DoubanApi.Photo> newItems) {
            items.clear();
            for (int i = 0; i < newItems.size(); i++) {
                DoubanApi.Photo top = newItems.get(i);
                DoubanApi.Photo bottom = null;
                if (top.isLand() && i + 1 < newItems.size() && newItems.get(i + 1).isLand()) {
                    bottom = newItems.get(++i);
                }
                items.add(new PhotoColumn(top, bottom));
            }
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            PhotoColumn item = items.get(position);
            setImage(holder, item.top, true);
            setImage(holder, item.bottom, false);
        }

        private void setImage(ViewHolder holder, DoubanApi.Photo photo, boolean top) {
            com.google.android.material.imageview.ShapeableImageView image = top ? holder.binding.top : holder.binding.bottom;
            image.setVisibility(photo == null ? View.GONE : View.VISIBLE);
            if (photo == null) return;
            ImgUtil.load(title, photo.url, image);
            image.setOnClickListener(view -> setBanner(photo.url));
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ViewHolder(AdapterHotPhotoBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        class ViewHolder extends RecyclerView.ViewHolder {

            private final AdapterHotPhotoBinding binding;

            ViewHolder(AdapterHotPhotoBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }

    static class PhotoColumn {

        private final DoubanApi.Photo top;
        private final DoubanApi.Photo bottom;

        PhotoColumn(DoubanApi.Photo top, DoubanApi.Photo bottom) {
            this.top = top;
            this.bottom = bottom;
        }
    }

    static class DetailCache {

        private final boolean zhuiju;
        private final JSONObject object;
        private final List<DoubanApi.Photo> photos;

        DetailCache(boolean zhuiju, JSONObject object, List<DoubanApi.Photo> photos) {
            this.zhuiju = zhuiju;
            this.object = object;
            this.photos = photos;
        }
    }
}
