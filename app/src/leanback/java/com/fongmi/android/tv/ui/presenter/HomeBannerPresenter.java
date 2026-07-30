package com.fongmi.android.tv.ui.presenter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

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

        // 1. Hide moved quick entries and let poster/live preview + recommendations fill the space.
        int screenWidth = ResUtil.getScreenWidth();
        int parentWidth = screenWidth - ResUtil.dp2px(48); // VerticalGridView has 24dp padding on each side
        int totalContentWidth = parentWidth - ResUtil.dp2px(16); // padding="8dp" on both sides of adapter_home_banner.xml
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

        // Right column always shows recommendation layout
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

        // Bind right cards
        bindRecommend(holder.binding.card1, rightList.size() > 0 ? rightList.get(0) : null, holder.binding.recommendImage1, holder.binding.recommendText1, holder);
        bindRecommend(holder.binding.card2, rightList.size() > 1 ? rightList.get(1) : null, holder.binding.recommendImage2, holder.binding.recommendText2, holder);
        bindRecommend(holder.binding.card3, null, holder.binding.recommendImage3, holder.binding.recommendText3, holder);

        if (item.isLivePreview()) {
            // Live Preview Mode: show live player in the middle card, hide details layout
            holder.binding.detailLayout.setVisibility(View.GONE);
            holder.binding.livePreviewLayout.setVisibility(View.VISIBLE);

            stopCarousel(holder);

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
            // Recommended Mode: show details layout in the middle card, hide live player
            holder.binding.detailLayout.setVisibility(View.VISIBLE);
            holder.binding.livePreviewLayout.setVisibility(View.GONE);

            if (recommends.isEmpty()) {
                holder.binding.middleName.setText(R.string.home_no_recommend);
                holder.binding.middleDirector.setVisibility(View.GONE);
                holder.binding.middleActor.setVisibility(View.GONE);
                holder.binding.middleContent.setText(R.string.home_no_recommend_desc);
                holder.binding.middleImage.setImageResource(R.drawable.artwork);
                stopCarousel(holder);
            } else {
                startCarousel(holder, carouselList);
            }
        }
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

    private void loadDetails(Vod vod, ViewHolder holder) {
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

                    if (id.equals(mCurrentVodId)) {
                        activity.runOnUiThread(() -> updateDetails(vod, holder));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void updateDetails(Vod vod, ViewHolder holder) {
        holder.binding.middleName.setText(vod.getName());
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
        ImgUtil.load(vod.getName(), vod.getPic(), holder.binding.middleImage);
    }

    private String getNonNullString(String val) {
        return val == null ? "" : val.trim();
    }

    private boolean isExternalRecommend(Vod vod) {
        return "iqiyi".equals(vod.getSiteKey()) || "tencent".equals(vod.getSiteKey());
    }

    private void startCarousel(ViewHolder holder, List<Vod> carouselVods) {
        stopCarousel(holder);
        if (carouselVods.isEmpty()) return;

        holder.carouselIndex = 0;
        holder.isCarouselPaused = false;

        // Initialize indicator dots
        holder.binding.indicatorLayout.removeAllViews();
        int dotSize = ResUtil.dp2px(6);
        int dotMargin = ResUtil.dp2px(4);
        for (int i = 0; i < carouselVods.size(); i++) {
            View dot = new View(holder.binding.getRoot().getContext());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dotSize, dotSize);
            if (i > 0) {
                params.leftMargin = dotMargin;
            }
            dot.setLayoutParams(params);
            dot.setBackgroundResource(R.drawable.shape_dot_inactive);
            holder.binding.indicatorLayout.addView(dot);
        }

        holder.binding.middleCard.setOnFocusChangeListener((v, hasFocus) -> {
            holder.isCarouselPaused = hasFocus;
            animateScale(v, hasFocus, 1.05f);
        });

        holder.carouselRunnable = new Runnable() {
            @Override
            public void run() {
                if (carouselVods.isEmpty()) return;

                if (!holder.isCarouselPaused) {
                    Vod vod = carouselVods.get(holder.carouselIndex);
                    updateDetails(vod, holder);
                    loadDetails(vod, holder);
                    holder.binding.middleCard.setOnClickListener(v -> activity.onItemClick(vod));
                    updateIndicator(holder, carouselVods.size(), holder.carouselIndex);
                    holder.carouselIndex = (holder.carouselIndex + 1) % carouselVods.size();
                }

                holder.binding.getRoot().postDelayed(this, 5000);
            }
        };

        // Run immediately first time
        holder.carouselRunnable.run();
    }

    private void updateIndicator(ViewHolder holder, int size, int currentIndex) {
        for (int i = 0; i < size; i++) {
            View dot = holder.binding.indicatorLayout.getChildAt(i);
            if (dot != null) {
                dot.setBackgroundResource(i == currentIndex ? R.drawable.shape_dot_active : R.drawable.shape_dot_inactive);
            }
        }
    }

    private void stopCarousel(ViewHolder holder) {
        if (holder.carouselRunnable != null) {
            holder.binding.getRoot().removeCallbacks(holder.carouselRunnable);
            holder.carouselRunnable = null;
        }
        holder.binding.indicatorLayout.removeAllViews();
    }

    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
        ViewHolder holder = (ViewHolder) viewHolder;
        stopCarousel(holder);
        Glide.with(holder.binding.middleImage).clear(holder.binding.middleImage);
        Glide.with(holder.binding.previewExo).clear(holder.binding.previewExo);
        activity.detachPreviewPlayer();

        // Reset scales and translations for recycled views
        holder.binding.btnVod.setScaleX(1.0f);
        holder.binding.btnVod.setScaleY(1.0f);
        holder.binding.btnVod.setTranslationZ(0f);
        holder.binding.btnLive.setScaleX(1.0f);
        holder.binding.btnLive.setScaleY(1.0f);
        holder.binding.btnLive.setTranslationZ(0f);
        holder.binding.btnKeep.setScaleX(1.0f);
        holder.binding.btnKeep.setScaleY(1.0f);
        holder.binding.btnKeep.setTranslationZ(0f);

        holder.binding.middleCard.setScaleX(1.0f);
        holder.binding.middleCard.setScaleY(1.0f);
        holder.binding.middleCard.setTranslationZ(0f);

        holder.binding.card1.setScaleX(1.0f);
        holder.binding.card1.setScaleY(1.0f);
        holder.binding.card1.setTranslationZ(0f);
        holder.binding.card2.setScaleX(1.0f);
        holder.binding.card2.setScaleY(1.0f);
        holder.binding.card2.setTranslationZ(0f);
        holder.binding.card3.setScaleX(1.0f);
        holder.binding.card3.setScaleY(1.0f);
        holder.binding.card3.setTranslationZ(0f);
    }

    public static class ViewHolder extends Presenter.ViewHolder {
        public final AdapterHomeBannerBinding binding;
        public Runnable carouselRunnable;
        public int carouselIndex = 0;
        public boolean isCarouselPaused = false;

        public ViewHolder(@NonNull AdapterHomeBannerBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
