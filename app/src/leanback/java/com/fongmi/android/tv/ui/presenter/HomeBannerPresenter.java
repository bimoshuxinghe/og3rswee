package com.fongmi.android.tv.ui.presenter;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

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

import java.util.List;

/**
 * 首页 Banner Presenter：
 *  - 横向 Cover Flow 走马灯（左小-中大-右小，竖向海报卡片）
 *  - 切换：向左平移 + 左侧渐隐退出 / 中间缩放到左 / 右侧放大到中间，依次接替
 *  - 获焦暂停，点击跳转，详情异步补全
 */
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

        // 1. 隐藏快捷入口栏，让中间走马灯 + 右侧推荐卡片占满空间
        int screenWidth = ResUtil.getScreenWidth();
        int parentWidth = screenWidth - ResUtil.dp2px(48);
        int totalContentWidth = parentWidth - ResUtil.dp2px(16);
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

        holder.binding.recommendLayout.setVisibility(View.VISIBLE);

        // 2. 切分推荐：右栏 2 张，其余进走马灯
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

        bindRecommend(holder.binding.card1, rightList.size() > 0 ? rightList.get(0) : null, holder.binding.recommendImage1, holder.binding.recommendText1, holder);
        bindRecommend(holder.binding.card2, rightList.size() > 1 ? rightList.get(1) : null, holder.binding.recommendImage2, holder.binding.recommendText2, holder);
        bindRecommend(holder.binding.card3, null, holder.binding.recommendImage3, holder.binding.recommendText3, holder);

        if (item.isLivePreview()) {
            // 直播预览模式
            holder.binding.detailLayout.setVisibility(View.GONE);
            holder.binding.livePreviewLayout.setVisibility(View.VISIBLE);
            stopMarquee(holder);
            holder.binding.middleCard.setFocusable(true);
            holder.binding.middleCard.setOnClickListener(view -> {
                activity.stopPreview();
                LiveActivity.start(activity);
            });
            holder.binding.middleCard.setOnFocusChangeListener((v, hasFocus) -> animateScale(v, hasFocus, 1.05f));
            activity.attachPreviewPlayer(holder.binding.previewExo);
        } else {
            // 推荐 + 走马灯模式
            holder.binding.detailLayout.setVisibility(View.VISIBLE);
            holder.binding.livePreviewLayout.setVisibility(View.GONE);

            if (carouselList.isEmpty() || carouselList.size() < 2) {
                // 不足2张时不启动走马灯，只显示一张主海报
                if (!carouselList.isEmpty()) {
                    showStaticBanner(holder, carouselList.get(0));
                } else {
                    showStaticBanner(holder, createNoRecommendVod());
                }
                stopMarquee(holder);
            } else {
                startCoverFlowMarquee(holder, carouselList);
            }
        }
    }

    private Vod createNoRecommendVod() {
        Vod v = new Vod();
        v.setName(ResUtil.getString(R.string.home_no_recommend));
        v.setContent(ResUtil.getString(R.string.home_no_recommend_desc));
        return v;
    }

    /** 静态单张显示（走马灯数据不足时使用） */
    private void showStaticBanner(ViewHolder holder, Vod vod) {
        holder.binding.cardLeft.setVisibility(View.GONE);
        holder.binding.cardRight.setVisibility(View.GONE);
        // 居中卡片充满走马灯区域
        FrameLayout.LayoutParams cp = (FrameLayout.LayoutParams) holder.binding.cardCenter.getLayoutParams();
        cp.width = FrameLayout.LayoutParams.MATCH_PARENT;
        cp.gravity = Gravity.CENTER;
        holder.binding.cardCenter.setLayoutParams(cp);
        holder.binding.cardCenter.setScaleX(1f);
        holder.binding.cardCenter.setScaleY(1f);
        holder.binding.cardCenter.setTranslationX(0f);
        holder.binding.cardCenter.setTranslationZ(8f);
        holder.binding.indicatorLayout.setVisibility(View.GONE);

        bindCenterDetails(holder, vod);
        holder.binding.cardCenter.setOnClickListener(v -> activity.onItemClick(vod));
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
            if (hasFocus) mCurrentVodId = vod.getId();
            animateScale(v, hasFocus, 1.1f);
        });
    }

    /** 绑定中间卡片（主海报）详情文字与图片 */
    private void bindCenterDetails(ViewHolder holder, Vod vod) {
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
        ImgUtil.load(vod.getName(), vod.getPic(), holder.binding.imgCenter);
        loadDetails(vod, holder);
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
                    Vod d = result.getList().get(0);
                    vod.setDirector(d.getDirector());
                    vod.setActor(d.getActor());
                    vod.setContent(d.getContent());
                    if (id.equals(mCurrentVodId)) {
                        activity.runOnUiThread(() -> {
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
                        });
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private String getNonNullString(String val) {
        return val == null ? "" : val.trim();
    }

    private boolean isExternalRecommend(Vod vod) {
        return "iqiyi".equals(vod.getSiteKey()) || "tencent".equals(vod.getSiteKey());
    }

    // ========================================================================
    // Cover Flow 走马灯核心逻辑
    // 布局：[左小 cardLeft(scale 0.82)] - [中大 cardCenter(scale 1.0)] - [右小 cardRight(scale 0.82)]
    // 切换一次向左：
    //   1) 左卡片向左淡出消失（alpha 1->0 + translateX 负方向）
    //   2) 中卡片向左移动并缩小（scale 1.0 -> 0.82，位置变到左卡片）
    //   3) 右卡片向左移动并放大（scale 0.82 -> 1.0，位置变到中卡片，始终最高Z）
    //   4) 原左卡片回收 + 在最右侧补入新的 cardRight 对应下一张数据（从无到有 alpha 0->1）
    // ========================================================================

    private static final int MARQUEE_INTERVAL_MS = 5000;   // 切换间隔
    private static final int MARQUEE_ANIM_MS = 650;         // 动画时长
    private static final float SCALE_SIDE = 0.82f;          // 两侧卡片缩放
    private static final float SCALE_CENTER = 1.0f;         // 中间卡片缩放

    /** 计算左/中/右 三张卡片的横向基准位置（相对于marqueeContainer的left=0），以容器宽度为准 */
    private void layoutCoverFlowCards(ViewHolder holder) {
        int containerW = holder.binding.marqueeContainer.getWidth();
        if (containerW <= 0) {
            int screenW = ResUtil.getScreenWidth();
            int padding = ResUtil.dp2px(64);
            int available = screenW - padding;
            float rightRatio = 0.32f;
            int rightW = Math.min(ResUtil.dp2px(560),
                                   Math.max(ResUtil.dp2px(360),
                                            (int) (available * rightRatio)));
            containerW = available - rightW;
        }
        int centerCardW = ResUtil.dp2px(240);
        int sideCardW = ResUtil.dp2px(180);
        int gap = ResUtil.dp2px(16);

        // 三张水平排列，总宽度 = center + side*2 + gap*2
        int totalCoverW = centerCardW + sideCardW * 2 + gap * 2;
        int startX = (containerW - totalCoverW) / 2;   // 整体水平居中

        // 左侧卡片基准X
        int leftX = startX;
        // 中间卡片基准X
        int centerX = leftX + sideCardW + gap;
        // 右侧卡片基准X
        int rightX = centerX + centerCardW + gap;

        holder.baseLeftX = leftX;
        holder.baseCenterX = centerX;
        holder.baseRightX = rightX;
        holder.centerCardWidth = centerCardW;
        holder.sideCardWidth = sideCardW;
    }

    /** 初始摆放三张卡片（左-中-右），设置好scale、translationX、Z */
    private void applyStaticCardLayouts(ViewHolder holder) {
        // 左
        holder.binding.cardLeft.setVisibility(View.VISIBLE);
        holder.binding.cardLeft.setTranslationX(holder.baseLeftX);
        holder.binding.cardLeft.setScaleX(SCALE_SIDE);
        holder.binding.cardLeft.setScaleY(SCALE_SIDE);
        holder.binding.cardLeft.setAlpha(1.0f);
        holder.binding.cardLeft.setTranslationZ(2f);
        // 中
        FrameLayout.LayoutParams cp = (FrameLayout.LayoutParams) holder.binding.cardCenter.getLayoutParams();
        cp.width = ResUtil.dp2px(240);
        cp.gravity = Gravity.START | Gravity.TOP;
        holder.binding.cardCenter.setLayoutParams(cp);
        holder.binding.cardCenter.setVisibility(View.VISIBLE);
        holder.binding.cardCenter.setTranslationX(holder.baseCenterX);
        holder.binding.cardCenter.setScaleX(SCALE_CENTER);
        holder.binding.cardCenter.setScaleY(SCALE_CENTER);
        holder.binding.cardCenter.setAlpha(1.0f);
        holder.binding.cardCenter.setTranslationZ(8f);  // 中间最高
        // 右
        holder.binding.cardRight.setVisibility(View.VISIBLE);
        holder.binding.cardRight.setTranslationX(holder.baseRightX);
        holder.binding.cardRight.setScaleX(SCALE_SIDE);
        holder.binding.cardRight.setScaleY(SCALE_SIDE);
        holder.binding.cardRight.setAlpha(1.0f);
        holder.binding.cardRight.setTranslationZ(2f);
    }

    private void startCoverFlowMarquee(ViewHolder holder, List<Vod> carouselVods) {
        stopMarquee(holder);

        holder.carouselVods = carouselVods;
        holder.carouselSize = carouselVods.size();
        holder.currentIndex = 0;
        holder.isMarqueePaused = false;
        holder.indicatorLayout = holder.binding.indicatorLayout;
        holder.binding.indicatorLayout.setVisibility(View.VISIBLE);

        // 计算初始卡片位置（等view布局完成）
        layoutCoverFlowCards(holder);
        applyStaticCardLayouts(holder);

        // 初始化指示器
        holder.binding.indicatorLayout.removeAllViews();
        int dotSize = ResUtil.dp2px(6);
        int dotMargin = ResUtil.dp2px(4);
        for (int i = 0; i < holder.carouselSize; i++) {
            View dot = new View(holder.binding.getRoot().getContext());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dotSize, dotSize);
            if (i > 0) params.leftMargin = dotMargin;
            dot.setLayoutParams(params);
            dot.setBackgroundResource(i == 0 ? R.drawable.shape_dot_active : R.drawable.shape_dot_inactive);
            holder.binding.indicatorLayout.addView(dot);
        }

        // 初始三张数据
        Vod leftVod   = carouselVods.get(mod(-1, holder.carouselSize));
        Vod centerVod = carouselVods.get(0);
        Vod rightVod  = carouselVods.get(mod(1, holder.carouselSize));
        ImgUtil.load(leftVod.getName(),   leftVod.getPic(),   holder.binding.imgLeft);
        ImgUtil.load(rightVod.getName(),  rightVod.getPic(),  holder.binding.imgRight);
        bindCenterDetails(holder, centerVod);

        // 点击跳转：三张卡片均可点击
        holder.binding.cardLeft.setOnClickListener(v -> activity.onItemClick(leftVod));
        holder.binding.cardCenter.setOnClickListener(v -> activity.onItemClick(centerVod));
        holder.binding.cardRight.setOnClickListener(v -> activity.onItemClick(rightVod));

        // 中间卡片获焦 -> 暂停走马灯 + 放大
        holder.binding.middleCard.setOnFocusChangeListener((v, hasFocus) -> {
            holder.isMarqueePaused = hasFocus;
            animateScale(v, hasFocus, 1.03f);
        });

        // 循环切换
        holder.marqueeRunnable = new Runnable() {
            @Override
            public void run() {
                if (holder.marqueeRunnable == null) return;
                if (!holder.isMarqueePaused) {
                    advanceCoverFlow(holder, carouselVods);
                }
                holder.binding.getRoot().postDelayed(this, MARQUEE_INTERVAL_MS);
            }
        };
        holder.binding.getRoot().postDelayed(holder.marqueeRunnable, MARQUEE_INTERVAL_MS);
    }

    private static int mod(int a, int n) {
        int r = a % n;
        return r < 0 ? r + n : r;
    }

    /**
     * 执行一次向左切换的 Cover Flow 动画：
     *   左  -> 飘出屏幕左侧（scale进一步缩小+alpha淡出）
     *   中  -> 平移到原左位置，同时scale 1.0→0.82（变成新的左）
     *   右  -> 平移到原中位置，同时scale 0.82→1.0（变成新的中）
     *   动画结束后：
     *     用"原右"作为新的"现中"显示详情文字
     *     把"原中"的图片/点击事件复制给"现左"卡片
     *     把"下一张"（currentIndex+2）图片加载给"现右"卡片
     *     把三卡片translationX/scale瞬间重置为静态位置（alpha恢复为1），无缝衔接
     */
    private void advanceCoverFlow(ViewHolder holder, List<Vod> vods) {
        if (holder.marqueeAnimator != null && holder.marqueeAnimator.isRunning()) return;
        layoutCoverFlowCards(holder);  // 重新确认基准坐标（防止尺寸变化）

        int n = holder.carouselSize;
        int cur = holder.currentIndex;
        Vod newCenterVod = vods.get(mod(cur + 1, n));   // 原右 = 新的中
        Vod newRightVod  = vods.get(mod(cur + 2, n));   // 将在右侧补入

        // === 各卡片动画目标 ===
        // 左：从 baseLeftX -> baseLeftX - sideCardW（飘出左边界），scale 0.82 -> 0.7，alpha 1->0
        float leftTargetX = holder.baseLeftX - (holder.sideCardWidth + ResUtil.dp2px(20));
        ObjectAnimator leftTX = ObjectAnimator.ofFloat(holder.binding.cardLeft, "translationX",
            holder.binding.cardLeft.getTranslationX(), leftTargetX);
        ObjectAnimator leftSX = ObjectAnimator.ofFloat(holder.binding.cardLeft, "scaleX", SCALE_SIDE, 0.7f);
        ObjectAnimator leftSY = ObjectAnimator.ofFloat(holder.binding.cardLeft, "scaleY", SCALE_SIDE, 0.7f);
        ObjectAnimator leftAlpha = ObjectAnimator.ofFloat(holder.binding.cardLeft, "alpha", 1f, 0f);

        // 中：从 baseCenterX -> baseLeftX，scale 1.0 -> 0.82
        ObjectAnimator centerTX = ObjectAnimator.ofFloat(holder.binding.cardCenter, "translationX",
            holder.binding.cardCenter.getTranslationX(), holder.baseLeftX);
        ObjectAnimator centerSX = ObjectAnimator.ofFloat(holder.binding.cardCenter, "scaleX", SCALE_CENTER, SCALE_SIDE);
        ObjectAnimator centerSY = ObjectAnimator.ofFloat(holder.binding.cardCenter, "scaleY", SCALE_CENTER, SCALE_SIDE);
        ObjectAnimator centerTZ = ObjectAnimator.ofFloat(holder.binding.cardCenter, "translationZ", 8f, 2f);

        // 右：从 baseRightX -> baseCenterX，scale 0.82 -> 1.0，Z提到最高
        ObjectAnimator rightTX = ObjectAnimator.ofFloat(holder.binding.cardRight, "translationX",
            holder.binding.cardRight.getTranslationX(), holder.baseCenterX);
        ObjectAnimator rightSX = ObjectAnimator.ofFloat(holder.binding.cardRight, "scaleX", SCALE_SIDE, SCALE_CENTER);
        ObjectAnimator rightSY = ObjectAnimator.ofFloat(holder.binding.cardRight, "scaleY", SCALE_SIDE, SCALE_CENTER);
        ObjectAnimator rightTZ = ObjectAnimator.ofFloat(holder.binding.cardRight, "translationZ", 2f, 8f);

        AnimatorSet set = new AnimatorSet();
        set.playTogether(leftTX, leftSX, leftSY, leftAlpha,
                         centerTX, centerSX, centerSY, centerTZ,
                         rightTX, rightSX, rightSY, rightTZ);
        set.setDuration(MARQUEE_ANIM_MS);
        set.setInterpolator(new DecelerateInterpolator(1.4f));
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // === 动画结束，循环交接：内容交换 + 瞬间复位位置 ===
                // 1. 把"原中"的图片 / 内容 / 点击事件 交给"现左"（即 holder.binding.cardLeft）
                //    原右 已经是新的中（显示在中间位置），现在把它的图片 / 点击事件 交给 cardCenter
                //    然后把新的下一张 交给 cardRight
                // 为简化，我们交换图片数据引用而非真的交换View引用：
                //   - imgLeft  <-- 原 cardCenter 那张图 (即 vods[cur])
                //   - imgCenter <-- 原 cardRight 那张图 (即 vods[cur+1])，并bind详情文字
                //   - imgRight <-- vods[cur+2]
                Vod vLeft  = vods.get(mod(cur + 0, n));  // 原中 -> 新左
                Vod vCtr   = vods.get(mod(cur + 1, n));  // 原右 -> 新中
                Vod vRight = vods.get(mod(cur + 2, n));  // 新数据 -> 新右

                // 给左卡片（新左）加载原中图片（原已经在cardCenter显示过，glide命中缓存）
                ImgUtil.load(vLeft.getName(), vLeft.getPic(), holder.binding.imgLeft);
                // 给右卡片（新右）加载下一张
                ImgUtil.load(vRight.getName(), vRight.getPic(), holder.binding.imgRight);

                // 中间卡片详情更新（主海报文字等都在cardCenter里）
                bindCenterDetails(holder, vCtr);

                // 更新点击事件
                holder.binding.cardLeft.setOnClickListener(v -> activity.onItemClick(vLeft));
                holder.binding.cardCenter.setOnClickListener(v -> activity.onItemClick(vCtr));
                holder.binding.cardRight.setOnClickListener(v -> activity.onItemClick(vRight));

                // 2. 三张卡片位置/缩放/alpha 瞬间复位到基准状态（视觉无缝：新的左和中已经和动画结束位置一致，只有右卡片需要从左侧飘入 -> 直接设为 baseRightX + alpha 1）
                holder.binding.cardLeft.setTranslationX(holder.baseLeftX);
                holder.binding.cardLeft.setScaleX(SCALE_SIDE);
                holder.binding.cardLeft.setScaleY(SCALE_SIDE);
                holder.binding.cardLeft.setAlpha(1f);
                holder.binding.cardLeft.setTranslationZ(2f);

                holder.binding.cardCenter.setTranslationX(holder.baseCenterX);
                holder.binding.cardCenter.setScaleX(SCALE_CENTER);
                holder.binding.cardCenter.setScaleY(SCALE_CENTER);
                holder.binding.cardCenter.setAlpha(1f);
                holder.binding.cardCenter.setTranslationZ(8f);

                holder.binding.cardRight.setTranslationX(holder.baseRightX);
                holder.binding.cardRight.setScaleX(SCALE_SIDE);
                holder.binding.cardRight.setScaleY(SCALE_SIDE);
                holder.binding.cardRight.setAlpha(1f);
                holder.binding.cardRight.setTranslationZ(2f);

                // 3. 更新索引与指示器
                holder.currentIndex = mod(cur + 1, n);
                for (int i = 0; i < holder.carouselSize; i++) {
                    View dot = holder.binding.indicatorLayout.getChildAt(i);
                    if (dot != null) {
                        dot.setBackgroundResource(i == holder.currentIndex ? R.drawable.shape_dot_active : R.drawable.shape_dot_inactive);
                    }
                }
                holder.marqueeAnimator = null;
            }
        });
        holder.marqueeAnimator = set;
        set.start();
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
        if (holder.indicatorLayout != null) {
            holder.indicatorLayout.removeAllViews();
        }
    }

    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
        ViewHolder holder = (ViewHolder) viewHolder;
        stopMarquee(holder);
        Glide.with(holder.binding.imgCenter).clear(holder.binding.imgCenter);
        Glide.with(holder.binding.imgLeft).clear(holder.binding.imgLeft);
        Glide.with(holder.binding.imgRight).clear(holder.binding.imgRight);
        Glide.with(holder.binding.previewExo).clear(holder.binding.previewExo);
        activity.detachPreviewPlayer();
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
        v.setTranslationX(0f);
        v.setAlpha(1f);
    }

    public static class ViewHolder extends Presenter.ViewHolder {
        public final AdapterHomeBannerBinding binding;
        public Runnable marqueeRunnable;
        public AnimatorSet marqueeAnimator;
        public LinearLayout indicatorLayout;
        public List<Vod> carouselVods;
        public int carouselSize;
        public int currentIndex;
        public boolean isMarqueePaused;
        // 基准X坐标（三张卡片在CoverFlow中的起始位置）
        public int baseLeftX, baseCenterX, baseRightX;
        public int centerCardWidth, sideCardWidth;

        public ViewHolder(@NonNull AdapterHomeBannerBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
