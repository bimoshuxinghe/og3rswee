package com.fongmi.android.tv.ui.presenter;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Func;
import com.fongmi.android.tv.bean.HomeBanner;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterHomeBannerBinding;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.activity.LiveActivity;
import com.fongmi.android.tv.ui.activity.SearchActivity;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;
import android.widget.LinearLayout;

import java.util.List;

public class HomeBannerPresenter extends Presenter {

    private final HomeActivity activity;
    private String mCurrentVodId;

    public HomeBannerPresenter(HomeActivity activity) {
        this.activity = activity;
    }

    @NonNull
    @Override
    public Presenter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        return new ViewHolder(AdapterHomeBannerBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Presenter.ViewHolder viewHolder, Object object) {
        HomeBanner item = (HomeBanner) object;
        ViewHolder holder = (ViewHolder) viewHolder;

        // 1. 计算布局宽度：隐藏快捷入口栏，让中间海报卡片+右侧推荐卡片占满空间
        int screenWidth = ResUtil.getScreenWidth();
        int parentWidth = screenWidth - ResUtil.dp2px(48); // VerticalGridView左右各24dp padding
        int totalContentWidth = parentWidth - ResUtil.dp2px(16); // adapter_home_banner.xml 根布局8dp padding
        int rightWidth = Math.min(ResUtil.dp2px(560), Math.max(ResUtil.dp2px(360), (int) (totalContentWidth * 0.32f)));

        LinearLayout.LayoutParams leftParams = (LinearLayout.LayoutParams) holder.binding.leftLayout.getLayoutParams();
        LinearLayout.LayoutParams rightParams = (LinearLayout.LayoutParams) holder.binding.rightLayout.getLayoutParams();
        LinearLayout.LayoutParams middleParams = (LinearLayout.LayoutParams) holder.binding.middleCard.getLayoutParams();

        holder.binding.leftLayout.setVisibility(View.GONE);
        leftParams.width = 0;
        leftParams.weight = 0;
        holder.binding.leftLayout.setLayoutParams(leftParams);

        holder.binding.middleCard.setVisibility(View.VISIBLE);
        middleParams.width = 0;
        middleParams.weight = 1;
        holder.binding.middleCard.setLayoutParams(middleParams);

        rightParams.width = rightWidth;
        rightParams.weight = 0;
        holder.binding.rightLayout.setLayoutParams(rightParams);

        // 右栏始终显示推荐布局
        holder.binding.recommendLayout.setVisibility(View.VISIBLE);

        List<Vod> recommends = item.getRecommends();
        List<Vod> rightList = new java.util.ArrayList<>();
        List<Vod> carouselList = new java.util.ArrayList<>();

        if (recommends.isEmpty()) {
            for (int i = 0; i < 2; i++) {
                Vod placeholder = new Vod();
                placeholder.setId("placeholder_" + i);
                placeholder.setName(ResUtil.getString(R.string.home_no_recommend));
                placeholder.setSite(Site.get("placeholder", "placeholder"));
                rightList.add(placeholder);
            }
        } else {
            if (recommends.size() > 2) {
                rightList = recommends.subList(recommends.size() - 2, recommends.size());
                carouselList = recommends.subList(0, recommends.size() - 2);
            } else {
                rightList = recommends;
                carouselList = recommends;
            }
        }

        // 绑定右侧推荐卡片
        bindRecommend(holder.binding.card1, rightList.size() > 0 ? rightList.get(0) : null, holder.binding.recommendImage1, holder.binding.recommendText1, holder);
        bindRecommend(holder.binding.card2, rightList.size() > 1 ? rightList.get(1) : null, holder.binding.recommendImage2, holder.binding.recommendText2, holder);
        bindRecommend(holder.binding.card3, null, holder.binding.recommendImage3, holder.binding.recommendText3, holder);

        if (item.isLivePreview()) {
            // 直播预览模式：在中间卡片显示播放器，隐藏详情布局
            holder.binding.detailLayout.setVisibility(View.GONE);
            holder.binding.livePreviewLayout.setVisibility(View.VISIBLE);

            stopMarquee(holder);

            holder.binding.middleCard.setFocusable(true);
            holder.binding.middleCard.setOnClickListener(view -> {
                activity.stopPreview();
                LiveActivity.start(activity);
            });
            holder.binding.middleCard.setOnFocusChangeListener((v, hasFocus) -> {
                animateScale(v, hasFocus, 1.05f);
            });

            activity.attachPreviewPlayer(holder.binding.previewExo);
        } else {
            // 推荐模式：中间卡片显示详情/跑马灯，隐藏播放器
            holder.binding.detailLayout.setVisibility(View.VISIBLE);
            holder.binding.livePreviewLayout.setVisibility(View.GONE);

            if (recommends.isEmpty()) {
                // 无推荐：显示占位内容
                bindPage(holder, 0, createNoRecommendVod(), true);
                stopMarquee(holder);
            } else {
                // 竖版跑马灯模式：上下滚动切换海报
                startMarquee(holder, carouselList);
            }
        }
    }

    private Vod createNoRecommendVod() {
        Vod v = new Vod();
        v.setName(ResUtil.getString(R.string.home_no_recommend));
        v.setContent(ResUtil.getString(R.string.home_no_recommend_desc));
        return v;
    }

    private void animateScale(View v, boolean hasFocus, float scale) {
        float z = hasFocus ? 8f : 0f;
        v.animate().scaleX(hasFocus ? scale : 1.0f).scaleY(hasFocus ? scale : 1.0f).translationZ(z).setDuration(150).start();
    }

    private void setupFocus(View v, float scale) {
        v.setOnFocusChangeListener((view, hasFocus) -> animateScale(view, hasFocus, scale));
    }

    private int getBriefResId(int resId) {
        if (resId == R.string.home_vod) return R.string.home_vod_brief;
        if (resId == R.string.home_live) return R.string.home_live_brief;
        if (resId == R.string.home_keep) return R.string.home_keep_brief;
        return 0;
    }

    private void bindFunc(androidx.cardview.widget.CardView view, android.widget.ImageView img, android.widget.TextView txt, android.widget.TextView brief, Func func) {
        if (func == null) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setVisibility(View.VISIBLE);
        view.setOnClickListener(v -> activity.onItemClick(func));
        img.setImageResource(func.getDrawable());
        txt.setText(func.getText());
        int briefResId = getBriefResId(func.getResId());
        if (briefResId != 0) {
            brief.setText(briefResId);
            brief.setVisibility(View.VISIBLE);
        } else {
            brief.setVisibility(View.GONE);
        }
        setupFocus(view, 1.1f);
    }

    private void bindRecommend(View card, Vod vod, android.widget.ImageView bg, android.widget.TextView text, ViewHolder holder) {
        if (vod == null) {
            card.setVisibility(View.GONE);
            return;
        }
        card.setVisibility(View.VISIBLE);

        text.setText(vod.getName());
        ImgUtil.load(vod.getName(), vod.getPic(), bg);

        card.setOnClickListener(v -> {
            if ("placeholder".equals(vod.getSiteKey())) {
                SearchActivity.start(activity);
            } else {
                activity.onItemClick(vod);
            }
        });

        card.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                mCurrentVodId = vod.getId();
            }
            animateScale(v, hasFocus, 1.1f);
        });
    }

    /**
     * 绑定指定页(0=前面/1=后面)的海报详情与图片
     */
    private void bindPage(ViewHolder holder, int page, Vod vod, boolean setClickListener) {
        androidx.appcompat.widget.AppCompatImageView imageView = (page == 0) ? holder.binding.middleImage : holder.binding.middleImage2;
        com.google.android.material.textview.MaterialTextView nameView = (page == 0) ? holder.binding.middleName : holder.binding.middleName2;
        com.google.android.material.textview.MaterialTextView directorView = (page == 0) ? holder.binding.middleDirector : holder.binding.middleDirector2;
        com.google.android.material.textview.MaterialTextView actorView = (page == 0) ? holder.binding.middleActor : holder.binding.middleActor2;
        com.google.android.material.textview.MaterialTextView contentView = (page == 0) ? holder.binding.middleContent : holder.binding.middleContent2;

        nameView.setText(vod.getName());
        if (isExternalRecommend(vod)) {
            directorView.setVisibility(View.GONE);
            actorView.setVisibility(View.GONE);
            contentView.setText(vod.getRemarks());
        } else {
            directorView.setVisibility(View.VISIBLE);
            actorView.setVisibility(View.VISIBLE);
            directorView.setText("导演: " + getNonNullString(vod.getDirector()));
            actorView.setText("演员: " + getNonNullString(vod.getActor()));
            contentView.setText(getNonNullString(vod.getContent()));
        }
        ImgUtil.load(vod.getName(), vod.getPic(), imageView);

        if (setClickListener && page == 0) {
            holder.binding.marqueePage0.setOnClickListener(v -> activity.onItemClick(vod));
        } else if (setClickListener) {
            holder.binding.marqueePage1.setOnClickListener(v -> activity.onItemClick(vod));
        }
    }

    private void loadDetails(Vod vod, ViewHolder holder, int page) {
        if (isExternalRecommend(vod)) return;
        String key = android.text.TextUtils.isEmpty(vod.getSiteKey()) ? com.fongmi.android.tv.api.config.VodConfig.get().getHome().getKey() : vod.getSiteKey();
        String id = vod.getId();

        if (!android.text.TextUtils.isEmpty(vod.getDirector()) ||
            !android.text.TextUtils.isEmpty(vod.getActor()) ||
            !android.text.TextUtils.isEmpty(vod.getContent())) {
            return;
        }

        com.fongmi.android.tv.utils.Task.executor().submit(() -> {
            try {
                com.fongmi.android.tv.bean.Result result = com.fongmi.android.tv.api.SiteApi.detailContent(key, id);
                if (result != null && result.getList() != null && !result.getList().isEmpty()) {
                    Vod detailVod = result.getList().get(0);
                    vod.setDirector(detailVod.getDirector());
                    vod.setActor(detailVod.getActor());
                    vod.setContent(detailVod.getContent());

                    // 仅当该页仍然是当前页时才更新UI（避免滚动后错位）
                    if (page == holder.marqueeDisplayPage || id.equals(mCurrentVodId)) {
                        activity.runOnUiThread(() -> {
                            if (page == 0) updatePage0Details(vod, holder);
                            else updatePage1Details(vod, holder);
                        });
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void updatePage0Details(Vod vod, ViewHolder holder) {
        if (isExternalRecommend(vod)) {
            holder.binding.middleDirector.setVisibility(View.GONE);
            holder.binding.middleActor.setVisibility(View.GONE);
            holder.binding.middleContent.setText(vod.getRemarks());
        } else {
            holder.binding.middleDirector.setVisibility(View.VISIBLE);
            holder.binding.middleActor.setVisibility(View.VISIBLE);
            holder.binding.middleDirector.setText("导演: " + getNonNullString(vod.getDirector()));
            holder.binding.middleActor.setText("演员: " + getNonNullString(vod.getActor()));
            holder.binding.middleContent.setText(getNonNullString(vod.getContent()));
        }
    }

    private void updatePage1Details(Vod vod, ViewHolder holder) {
        if (isExternalRecommend(vod)) {
            holder.binding.middleDirector2.setVisibility(View.GONE);
            holder.binding.middleActor2.setVisibility(View.GONE);
            holder.binding.middleContent2.setText(vod.getRemarks());
        } else {
            holder.binding.middleDirector2.setVisibility(View.VISIBLE);
            holder.binding.middleActor2.setVisibility(View.VISIBLE);
            holder.binding.middleDirector2.setText("导演: " + getNonNullString(vod.getDirector()));
            holder.binding.middleActor2.setText("演员: " + getNonNullString(vod.getActor()));
            holder.binding.middleContent2.setText(getNonNullString(vod.getContent()));
        }
    }

    private String getNonNullString(String val) {
        return val == null ? "" : val.trim();
    }

    private boolean isExternalRecommend(Vod vod) {
        return "iqiyi".equals(vod.getSiteKey()) || "tencent".equals(vod.getSiteKey());
    }

    // ========================================================================
    // 竖版跑马灯核心逻辑：双页容器 + translationY 向上滚动 + 循环数据
    // ========================================================================

    private static final int MARQUEE_INTERVAL_MS = 5000;     // 滚动间隔
    private static final int MARQUEE_ANIM_DURATION_MS = 700;  // 滚动动画时长

    private void startMarquee(ViewHolder holder, List<Vod> carouselVods) {
        stopMarquee(holder);
        if (carouselVods.isEmpty()) return;

        holder.carouselVods = carouselVods;
        holder.carouselIndex = 0;
        holder.marqueeDisplayPage = 0;
        holder.isMarqueePaused = false;
        holder.marqueeHeight = ResUtil.dp2px(304); // 与XML中每页高度一致

        // 初始化指示器圆点
        holder.binding.indicatorLayout.removeAllViews();
        int dotSize = ResUtil.dp2px(6);
        int dotMargin = ResUtil.dp2px(4);
        int size = carouselVods.size();
        for (int i = 0; i < size; i++) {
            View dot = new View(holder.binding.getRoot().getContext());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dotSize, dotSize);
            if (i > 0) params.leftMargin = dotMargin;
            dot.setLayoutParams(params);
            dot.setBackgroundResource(R.drawable.shape_dot_inactive);
            holder.binding.indicatorLayout.addView(dot);
        }

        // 重置容器位置
        holder.binding.marqueeContainer.setTranslationY(0);

        // 初始显示第一页(index=0)在page0，第二页(index=1)在page1
        Vod current = carouselVods.get(0 % size);
        Vod next = carouselVods.get(1 % size);
        bindPage(holder, 0, current, true);
        bindPage(holder, 1, next, true);
        loadDetails(current, holder, 0);
        loadDetails(next, holder, 1);
        updateIndicator(holder, size, 0);

        // 中间卡片获焦时暂停跑马灯
        holder.binding.middleCard.setOnFocusChangeListener((v, hasFocus) -> {
            holder.isMarqueePaused = hasFocus;
            animateScale(v, hasFocus, 1.05f);
        });

        // 点击当前可见的page也跳转（整个卡片可点）
        holder.binding.middleCard.setOnClickListener(v -> {
            int realIdx = holder.carouselIndex % size;
            if (realIdx < 0) realIdx += size;
            if (!carouselVods.isEmpty()) {
                activity.onItemClick(carouselVods.get(realIdx));
            }
        });

        // 递归调度下一次滚动
        holder.marqueeRunnable = new Runnable() {
            @Override
            public void run() {
                if (holder.marqueeRunnable == null) return; // 已停止
                if (carouselVods.isEmpty()) return;

                if (!holder.isMarqueePaused) {
                    performMarqueeScroll(holder, carouselVods);
                }
                holder.binding.getRoot().postDelayed(this, MARQUEE_INTERVAL_MS);
            }
        };

        holder.binding.getRoot().postDelayed(holder.marqueeRunnable, MARQUEE_INTERVAL_MS);
    }

    /**
     * 执行一次向上滚动：marqueeContainer translationY 从 0 → -pageHeight
     * 动画结束后：重置为0，更新page0内容为新index，更新指示器
     */
    private void performMarqueeScroll(ViewHolder holder, List<Vod> carouselVods) {
        int size = carouselVods.size();
        if (holder.marqueeAnimator != null && holder.marqueeAnimator.isRunning()) return;

        int target = -holder.marqueeHeight;
        ObjectAnimator anim = ObjectAnimator.ofFloat(holder.binding.marqueeContainer, "translationY", 0f, (float) target);
        anim.setDuration(MARQUEE_ANIM_DURATION_MS);
        anim.setInterpolator(new DecelerateInterpolator(1.5f));
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // 动画完成：
                // 1. 把 page0 数据替换为原来 page1 的下一个（index+2）
                // 2. 把 translationY 瞬间恢复为 0（视觉上无缝衔接）
                // 3. 指示器更新为 index+1
                holder.carouselIndex = (holder.carouselIndex + 1) % size;
                int nextNextIdx = (holder.carouselIndex + 1) % size;
                Vod nextNextVod = carouselVods.get(nextNextIdx);

                // 先把page0的内容设为即将显示的下一个（page1当前是新的carouselIndex）
                bindPage(holder, 0, nextNextVod, true);
                loadDetails(nextNextVod, holder, 0);

                // 瞬间复位，无缝衔接
                holder.binding.marqueeContainer.setTranslationY(0);
                holder.marqueeDisplayPage = 1 - holder.marqueeDisplayPage; // 逻辑页翻转

                // 指示器更新为当前carouselIndex
                updateIndicator(holder, size, holder.carouselIndex);
            }
        });
        holder.marqueeAnimator = anim;
        anim.start();

        // 滚动过程中，当前显示的已经是page1了，把点击事件也交给page1
        Vod currentVod = carouselVods.get((holder.carouselIndex + 1) % size);
        holder.binding.marqueePage1.setOnClickListener(v -> activity.onItemClick(currentVod));
    }

    private void updateIndicator(ViewHolder holder, int size, int currentIndex) {
        for (int i = 0; i < size; i++) {
            View dot = holder.binding.indicatorLayout.getChildAt(i);
            if (dot != null) {
                dot.setBackgroundResource(i == currentIndex ? R.drawable.shape_dot_active : R.drawable.shape_dot_inactive);
            }
        }
    }

    private void stopMarquee(ViewHolder holder) {
        if (holder.marqueeRunnable != null) {
            holder.binding.getRoot().removeCallbacks(holder.marqueeRunnable);
            holder.marqueeRunnable = null;
        }
        if (holder.marqueeAnimator != null && holder.marqueeAnimator.isRunning()) {
            holder.marqueeAnimator.cancel();
        }
        holder.marqueeAnimator = null;
        holder.binding.indicatorLayout.removeAllViews();
        holder.binding.marqueeContainer.setTranslationY(0);
    }

    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
        ViewHolder holder = (ViewHolder) viewHolder;
        stopMarquee(holder);
        Glide.with(holder.binding.middleImage).clear(holder.binding.middleImage);
        Glide.with(holder.binding.middleImage2).clear(holder.binding.middleImage2);
        Glide.with(holder.binding.previewExo).clear(holder.binding.previewExo);
        activity.detachPreviewPlayer();

        // 重置缩放和translationZ
        resetScale(holder.binding.btnVod);
        resetScale(holder.binding.btnLive);
        resetScale(holder.binding.btnKeep);
        resetScale(holder.binding.middleCard);
        resetScale(holder.binding.card1);
        resetScale(holder.binding.card2);
        resetScale(holder.binding.card3);
    }

    private void resetScale(View v) {
        v.setScaleX(1.0f);
        v.setScaleY(1.0f);
        v.setTranslationZ(0f);
    }

    public static class ViewHolder extends Presenter.ViewHolder {
        public final AdapterHomeBannerBinding binding;
        public Runnable marqueeRunnable;           // 跑马灯循环任务
        public ObjectAnimator marqueeAnimator;     // 滚动动画
        public List<Vod> carouselVods;             // 轮播数据源
        public int carouselIndex = 0;              // 当前数据源索引
        public int marqueeDisplayPage = 0;         // 逻辑上当前显示的是page0还是page1
        public boolean isMarqueePaused = false;    // 获焦时暂停
        public int marqueeHeight = 0;              // 每页高度(px)

        public ViewHolder(@NonNull AdapterHomeBannerBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
