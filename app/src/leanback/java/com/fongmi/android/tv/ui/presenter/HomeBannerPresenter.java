package com.fongmi.android.tv.ui.presenter;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Func;
import com.fongmi.android.tv.bean.HomeBanner;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterHomeBannerBinding;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.activity.SearchActivity;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.List;

/**
 * 首页 Banner Presenter — 8-Slot Cover Flow 走马灯
 *
 *   可见卡片（5张）：   [-2 中小] [-1 中] [0 大] [+1 中] [+2 中小]
 *   缩放比例：           0.72     0.85   1.00   0.85    0.72
 *   缓冲卡片（3张）：   [-3/-4 左侧缓冲 alpha=0 scale=0.5] [+3 右侧 buffer scale=0.5] [+4 右侧外 scale=0.5]
 *
 *   切换（向左滚动）：所有 8 张卡片从 position p 平滑移动到 p-1，scale 对应改变
 *     动画结束后：
 *       刚飞出可见区的 slot（新 pos -3，alpha=0） → 重新绑定数据到 (centerIdx+4) → 瞬间重置到 pos +4 作为新的最右缓冲
 *       其余 7 个 slot 已在正确位置，无需视觉跳变
 *       更新中间详情文字 / 指示器 / 点击事件
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

        // === 1. 布局：删除右侧推荐后，走马灯占满整个 Banner 宽度 ===
        LinearLayout.LayoutParams leftParams = (LinearLayout.LayoutParams) holder.binding.leftLayout.getLayoutParams();
        LinearLayout.LayoutParams middleParams = (LinearLayout.LayoutParams) holder.binding.middleCard.getLayoutParams();

        holder.binding.leftLayout.setVisibility(View.GONE);
        leftParams.width = 0;
        leftParams.weight = 0;
        holder.binding.leftLayout.setLayoutParams(leftParams);

        holder.binding.middleCard.setVisibility(View.VISIBLE);
        middleParams.width = LinearLayout.LayoutParams.MATCH_PARENT;
        middleParams.weight = 0;
        middleParams.rightMargin = 0;
        holder.binding.middleCard.setLayoutParams(middleParams);

        // 右侧推荐区已在 XML 删除，这里不需要再操作

        // === 2. 所有推荐都进走马灯（不再切分右栏） ===
        List<Vod> carouselList = item.getRecommends();
        if (carouselList == null || carouselList.isEmpty()) {
            carouselList = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                Vod placeholder = new Vod();
                placeholder.setId("placeholder_" + i);
                placeholder.setName(ResUtil.getString(R.string.home_no_recommend));
                placeholder.setSite(Site.get("placeholder", "placeholder"));
                carouselList.add(placeholder);
            }
        }

        // Cover Flow 走马灯模式（预览功能已整体删除，不再区分直播预览分支）
        holder.binding.detailLayout.setVisibility(View.VISIBLE);
        holder.binding.livePreviewLayout.setVisibility(View.GONE);

        if (carouselList.size() < 2) {
            showStaticBanner(holder, carouselList.isEmpty() ? createNoRecommendVod() : carouselList.get(0));
            stopMarquee(holder);
        } else {
            startCoverFlowMarquee(holder, carouselList);
        }
    }

    private Vod createNoRecommendVod() {
        Vod v = new Vod();
        v.setName(ResUtil.getString(R.string.home_no_recommend));
        v.setContent(ResUtil.getString(R.string.home_no_recommend_desc));
        return v;
    }

    /** 静态单张显示（走马灯数据不足时使用）——复用 slot3 居中展示 */
    private void showStaticBanner(ViewHolder holder, Vod vod) {
        layoutCoverFlow(holder);
        int containerW = holder.containerWidth;
        int cardW = ResUtil.dp2px(CARD_WIDTH_DP);
        int centerTX = (containerW - cardW) / 2;

        // 隐藏所有 slot，只显示 slot3
        for (int i = 0; i < NUM_SLOTS; i++) {
            FrameLayout slot = holder.slots[i];
            slot.setVisibility(View.INVISIBLE);
            slot.setAlpha(1f);
            slot.setTranslationZ(0f);
            slot.setTranslationX(0f);
            slot.setScaleX(1f);
            slot.setScaleY(1f);
            slot.setOnClickListener(null);
            slot.setOnFocusChangeListener(null);
        }
        holder.slots[CENTER_SLOT].setVisibility(View.VISIBLE);
        holder.slots[CENTER_SLOT].setTranslationX(centerTX);
        holder.slots[CENTER_SLOT].setScaleX(1f);
        holder.slots[CENTER_SLOT].setScaleY(1f);
        holder.slots[CENTER_SLOT].setTranslationZ(8f);
        ImgUtil.load(vod.getName(), vod.getPic(), holder.imgs[CENTER_SLOT]);

        // 详情层（在 marqueeContainer 底部居中）宽度等于中间卡片宽度，位置也对齐 slot3
        FrameLayout.LayoutParams dlp = (FrameLayout.LayoutParams) holder.binding.centerDetailsLayout.getLayoutParams();
        dlp.width = cardW;
        holder.binding.centerDetailsLayout.setLayoutParams(dlp);

        bindCenterDetails(holder, vod);
        holder.binding.indicatorLayout.setVisibility(View.GONE);

        holder.slots[CENTER_SLOT].setOnClickListener(v -> activity.onItemClick(vod));
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

    /** 绑定中间卡片（主海报）详情文字 — 文字层 centerDetailsLayout 悬浮在中间卡片上方 */
    private void bindCenterDetails(ViewHolder holder, Vod vod) {
        mCurrentVodId = vod.getId();
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
    // Cover Flow 走马灯核心：8-Slot 循环缓冲 + 丝滑左滑动画
    // ========================================================================

    private static final int MARQUEE_INTERVAL_MS = 5000;
    private static final int MARQUEE_ANIM_MS    = 800;
    private static final int CARD_WIDTH_DP      = 220;
    private static final int CARD_GAP_DP        = 14;
    private static final int NUM_SLOTS          = 8;
    private static final int CENTER_SLOT        = 3;  // 静态时 slot3 承载中间卡片（position=0）

    /** 位置 -2..+2 (5个可见) 对应的缩放比例；|pos|>=3 buffer scale=0.5 */
    private static final float[] SCALE_BY_POS = {0.72f, 0.85f, 1.00f, 0.85f, 0.72f};
    /** 每个物理 slot 8 张卡片在初始化/复位时对应的"逻辑位置"（-3,-2,-1,0,+1,+2,+3,+4） */
    private static final int[] INITIAL_POSITION = {-3, -2, -1, 0, 1, 2, 3, 4};

    /** Material fast-out-slow-in interpolator */
    private static final PathInterpolator SMOOTH_INTERP = new PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f);

    /** 计算整个 Cover Flow 的容器尺寸、卡片间距、各逻辑位置的 translationX 基准 */
    private void layoutCoverFlow(ViewHolder holder) {
        int containerW = holder.binding.marqueeContainer.getWidth();
        if (containerW <= 0) {
            int screenW = ResUtil.getScreenWidth();
            int padding = ResUtil.dp2px(48 + 16); // 外层padding + banner内部padding
            containerW = Math.max(screenW - padding, ResUtil.dp2px(800));
        }
        holder.containerWidth = containerW;
        int cardW = ResUtil.dp2px(CARD_WIDTH_DP);
        int step   = ResUtil.dp2px(CARD_WIDTH_DP + CARD_GAP_DP); // 相邻卡片中心点间距 = 卡片宽 + 间距（保证不重叠完整显示）
        holder.cardWidth = cardW;
        holder.stepX = step;
        // 中间卡片 (position 0) 的 translationX = 容器中心 - 卡片半宽
        holder.centerTX = (containerW - cardW) / 2;

        // 详情文字层宽度 = 中间卡片宽度
        try {
            FrameLayout.LayoutParams dlp = (FrameLayout.LayoutParams) holder.binding.centerDetailsLayout.getLayoutParams();
            if (dlp != null) {
                dlp.width = cardW;
                holder.binding.centerDetailsLayout.setLayoutParams(dlp);
            }
        } catch (Exception ignored) {}
    }

    /** 给定逻辑位置 pos，返回该 slot 的 translationX / scale / alpha / z */
    private float posToTranslationX(ViewHolder holder, int pos) {
        return holder.centerTX + pos * holder.stepX;
    }

    private float posToScale(int pos) {
        if (pos >= -2 && pos <= 2) return SCALE_BY_POS[pos + 2];
        return 0.50f; // |pos| >= 3 buffer / exit
    }

    private float posToAlpha(int pos) {
        if (pos >= -2 && pos <= 2) return 1.0f;
        return 0f; // |pos| >= 3 buffer / exit
    }

    private float posToZ(int pos) {
        if (pos == 0) return 10f;
        int abs = Math.abs(pos);
        if (abs == 1) return 6f;
        if (abs == 2) return 3f;
        return 1f;
    }

    private int posToVisibility(int pos) {
        return (pos >= -2 && pos <= 2) ? View.VISIBLE : View.INVISIBLE;
    }

    private static int mod(int a, int n) {
        int r = a % n;
        return r < 0 ? r + n : r;
    }

    // ============ 初始化 & 预加载 ============

    private void startCoverFlowMarquee(ViewHolder holder, List<Vod> carouselVods) {
        stopMarquee(holder);
        holder.carouselVods = carouselVods;
        holder.carouselSize = carouselVods.size();
        holder.currentCenterIdx = 0;
        holder.isMarqueePaused = false;

        // 计算位置参数
        layoutCoverFlow(holder);
        final int cardW = holder.cardWidth;

        // 所有 slot 初始化为默认物理参数（避免之前的状态残留）
        for (int i = 0; i < NUM_SLOTS; i++) {
            FrameLayout slot = holder.slots[i];
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) slot.getLayoutParams();
            if (lp != null) {
                lp.width = cardW;
                lp.gravity = Gravity.TOP | Gravity.START;
                slot.setLayoutParams(lp);
            }
            slot.setPivotX(cardW / 2f);
            slot.setPivotY(slot.getHeight() > 0 ? slot.getHeight() / 2f : ResUtil.dp2px(150));
        }

        // 绑定初始数据：slot i 对应数据索引 = currentCenterIdx + INITIAL_POSITION[i]
        for (int i = 0; i < NUM_SLOTS; i++) {
            int pos = INITIAL_POSITION[i];
            int dataIdx = mod(holder.currentCenterIdx + pos, holder.carouselSize);
            Vod vod = carouselVods.get(dataIdx);
            holder.slotDataIndex[i] = dataIdx;
            holder.slotPosition[i] = pos;
            ImgUtil.load(vod.getName(), vod.getPic(), holder.imgs[i]);
            slotClickListenerReset(holder, i);
        }

        // 把每个 slot 摆放到初始位置 + 缩放 + 透明度 + z
        applyStaticPositions(holder, true);

        // 中间卡片详情文字
        Vod centerVod = carouselVods.get(holder.currentCenterIdx);
        bindCenterDetails(holder, centerVod);

        // 预加载整个轮播的所有海报（进 Glide 内存/磁盘缓存）
        preloadAllPosters(holder, carouselVods);

        // 指示器
        holder.binding.indicatorLayout.setVisibility(View.VISIBLE);
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

        // 中间卡片获焦 → 暂停走马灯 + 放大
        holder.binding.middleCard.setOnFocusChangeListener((v, hasFocus) -> {
            holder.isMarqueePaused = hasFocus;
            animateScale(v, hasFocus, 1.02f);
            // 失焦时重置自动轮播计时（避免马上就切）
            if (!hasFocus && holder.marqueeRunnable != null) {
                holder.binding.getRoot().removeCallbacks(holder.marqueeRunnable);
                holder.binding.getRoot().postDelayed(holder.marqueeRunnable, MARQUEE_INTERVAL_MS);
            }
        });

        // ===== 遥控器左右键 → 手动切换轮播 =====
        // 在 middleCard 聚焦状态下：
        //   按 DPAD_RIGHT → 选中"下一张"（即 advanceCoverFlow，向左整体平移，右侧卡片放大为中间）
        //   按 DPAD_LEFT  → 选中"上一张"（即 retreatCoverFlow，向右整体平移，左侧卡片放大为中间）
        //   按 DPAD_CENTER / ENTER → 跳转到当前中间那张详情页
        // （UP/DOWN 不拦截，让焦点按正常逻辑跳出到 toolbar 或下方分类）
        holder.binding.middleCard.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (holder.carouselSize <= 0) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (holder.marqueeAnimator == null || !holder.marqueeAnimator.isRunning()) {
                    advanceCoverFlow(holder);
                    // 手动切换后重置自动轮播计时
                    if (holder.marqueeRunnable != null) {
                        holder.binding.getRoot().removeCallbacks(holder.marqueeRunnable);
                        if (!holder.isMarqueePaused) {
                            holder.binding.getRoot().postDelayed(holder.marqueeRunnable, MARQUEE_INTERVAL_MS);
                        }
                    }
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (holder.marqueeAnimator == null || !holder.marqueeAnimator.isRunning()) {
                    retreatCoverFlow(holder);
                    if (holder.marqueeRunnable != null) {
                        holder.binding.getRoot().removeCallbacks(holder.marqueeRunnable);
                        if (!holder.isMarqueePaused) {
                            holder.binding.getRoot().postDelayed(holder.marqueeRunnable, MARQUEE_INTERVAL_MS);
                        }
                    }
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                // 回车/OK/Enter → 跳转当前中间海报的详情
                int dataIdx = mod(holder.currentCenterIdx, holder.carouselSize);
                if (holder.carouselVods != null && dataIdx >= 0 && dataIdx < holder.carouselVods.size()) {
                    Vod vod = holder.carouselVods.get(dataIdx);
                    if (vod != null) {
                        activity.onItemClick(vod);
                        return true;
                    }
                }
            }
            return false;
        });

        // ===== 点击中间详情层（文字/播放按钮区域）同样跳转到当前中间详情 =====
        holder.binding.centerDetailsLayout.setOnClickListener(v -> {
            int dataIdx = mod(holder.currentCenterIdx, holder.carouselSize);
            if (holder.carouselVods != null && dataIdx >= 0 && dataIdx < holder.carouselVods.size()) {
                Vod vod = holder.carouselVods.get(dataIdx);
                if (vod != null) activity.onItemClick(vod);
            }
        });
        // 中间详情层的按键也让它透传到 middleCard 的处理（虽然通常 middleCard 是父级）
        holder.binding.centerDetailsLayout.setFocusable(false);
        holder.binding.playButtonLayout.setFocusable(false);

        // 循环切换 runnable
        holder.marqueeRunnable = new Runnable() {
            @Override
            public void run() {
                if (holder.marqueeRunnable == null) return;
                if (!holder.isMarqueePaused) {
                    advanceCoverFlow(holder);
                }
                holder.binding.getRoot().postDelayed(this, MARQUEE_INTERVAL_MS);
            }
        };
        // 先等布局完成一次再启动（确保 getWidth 有效）
        holder.binding.marqueeContainer.post(() -> {
            layoutCoverFlow(holder);
            applyStaticPositions(holder, true);
            holder.binding.getRoot().postDelayed(holder.marqueeRunnable, MARQUEE_INTERVAL_MS);
        });
    }

    /** 将所有 8 个 slot 设置到 holder.slotPosition[i] 对应的静态位置 */
    private void applyStaticPositions(ViewHolder holder, boolean setVisibility) {
        for (int i = 0; i < NUM_SLOTS; i++) {
            int pos = holder.slotPosition[i];
            FrameLayout slot = holder.slots[i];
            if (setVisibility) slot.setVisibility(posToVisibility(pos));
            slot.setTranslationX(posToTranslationX(holder, pos));
            slot.setScaleX(posToScale(pos));
            slot.setScaleY(posToScale(pos));
            slot.setAlpha(posToAlpha(pos));
            slot.setTranslationZ(posToZ(pos));
        }
    }

    /** 预加载所有轮播海报到 Glide 缓存 */
    private void preloadAllPosters(ViewHolder holder, List<Vod> vods) {
        if (vods == null || vods.isEmpty()) return;
        try {
            RequestManager rm = Glide.with(holder.binding.getRoot().getContext());
            for (Vod v : vods) {
                String pic = v == null ? null : v.getPic();
                if (pic != null && !pic.isEmpty()) {
                    try { rm.load(pic).preload(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    /** 重置某个 slot 的点击事件（跳转到对应 vod） */
    private void slotClickListenerReset(ViewHolder holder, int slotIdx) {
        if (holder.carouselVods == null || holder.carouselSize <= 0) return;
        int dataIdx = holder.slotDataIndex[slotIdx];
        if (dataIdx < 0 || dataIdx >= holder.carouselVods.size()) return;
        final Vod vod = holder.carouselVods.get(dataIdx);
        holder.slots[slotIdx].setOnClickListener(v -> activity.onItemClick(vod));
    }

    // ============ 向左切换一次的动画 ============

    /**
     * 向左前进一帧：
     *   每个物理 slot 的逻辑位置从 p → p-1
     *     pos -2 → -3 (飞出可见区左侧，alpha→0，scale→0.5)
     *     pos -1 → -2，pos 0 → -1，pos +1 → 0，pos +2 → +1
     *     pos +3 → +2 (从右侧缓冲区进入可见区，alpha 0→1，scale 0.5→0.72)
     *     pos +4 → +3 (进入右侧缓冲区)
     *   动画结束后：
     *     刚飞出可见区的 slot（现在 pos = -3）绑定新数据（currentCenterIdx + 4），
     *     其逻辑位置重置为 +4（新最右缓冲），瞬间移动到 +4 位置（因为不可见所以无视觉跳变）。
     *     其他 7 个 slot 已经停在它们的新静态位置（p-1），可见的 5 张（-2..+2）修正对齐。
     *     更新 currentCenterIdx++，详情文字，指示器，点击事件。
     */
    private void advanceCoverFlow(ViewHolder holder) {
        if (holder.marqueeAnimator != null && holder.marqueeAnimator.isRunning()) return;
        layoutCoverFlow(holder); // 保险：刷新尺寸

        final int n = holder.carouselSize;
        AnimatorSet set = new AnimatorSet();
        List<Animator> anims = new java.util.ArrayList<>(NUM_SLOTS * 5);

        // 对每个 slot 创建从当前 pos → pos-1 的动画
        for (int i = 0; i < NUM_SLOTS; i++) {
            int fromPos = holder.slotPosition[i];
            int toPos   = fromPos - 1;
            FrameLayout slot = holder.slots[i];
            slot.setVisibility(View.VISIBLE); // 动画期间都保持 VISIBLE（靠 alpha 控制显隐）

            float fromTX = slot.getTranslationX();
            float toTX   = posToTranslationX(holder, toPos);
            float fromSX = slot.getScaleX();
            float toSX   = posToScale(toPos);
            float fromSY = slot.getScaleY();
            float toSY   = posToScale(toPos);
            float fromA  = slot.getAlpha();
            float toA    = posToAlpha(toPos);
            float fromZ  = slot.getTranslationZ();
            float toZ    = posToZ(toPos);

            anims.add(ObjectAnimator.ofFloat(slot, "translationX", fromTX, toTX));
            anims.add(ObjectAnimator.ofFloat(slot, "scaleX", fromSX, toSX));
            anims.add(ObjectAnimator.ofFloat(slot, "scaleY", fromSY, toSY));
            anims.add(ObjectAnimator.ofFloat(slot, "alpha", fromA, toA));
            if (fromZ != toZ) {
                anims.add(ObjectAnimator.ofFloat(slot, "translationZ", fromZ, toZ));
            }
        }
        set.playTogether(anims);
        set.setDuration(MARQUEE_ANIM_MS);
        set.setInterpolator(SMOOTH_INTERP);

        final int oldCenter = holder.currentCenterIdx;
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // === 步骤 1：更新所有 slot 逻辑位置（p → p-1） ===
                int exitSlotIdx = -1;
                for (int i = 0; i < NUM_SLOTS; i++) {
                    holder.slotPosition[i] -= 1;
                    if (holder.slotPosition[i] == -3) exitSlotIdx = i;
                }

                // === 步骤 2：把退出可见区的 slot（pos=-3）回收为新的最右缓冲（pos=+4） ===
                if (exitSlotIdx >= 0) {
                    int newCenter = mod(oldCenter + 1, n);
                    // 这个 slot 应该承载数据：newCenter + 4（对应位置 +4，作为最右侧缓冲）
                    int newDataIdx = mod(newCenter + 4, n);
                    holder.slotDataIndex[exitSlotIdx] = newDataIdx;
                    Vod newVod = holder.carouselVods.get(newDataIdx);
                    ImgUtil.load(newVod.getName(), newVod.getPic(), holder.imgs[exitSlotIdx]);
                    // 逻辑位置从 -3 改为 +4，瞬间放置到 +4 位置（不可见，alpha=0）
                    holder.slotPosition[exitSlotIdx] = 4;
                    FrameLayout s = holder.slots[exitSlotIdx];
                    s.setTranslationX(posToTranslationX(holder, 4));
                    s.setScaleX(posToScale(4));
                    s.setScaleY(posToScale(4));
                    s.setAlpha(posToAlpha(4));
                    s.setTranslationZ(posToZ(4));
                    s.setVisibility(View.INVISIBLE); // 缓冲保持 INVISIBLE，下次动画时再改为 VISIBLE
                    slotClickListenerReset(holder, exitSlotIdx);
                }

                // === 步骤 3：5 张可见 slot（-2..+2）修正 VISIBLE + 精确对齐 ===
                for (int i = 0; i < NUM_SLOTS; i++) {
                    int p = holder.slotPosition[i];
                    if (p >= -2 && p <= 2) {
                        holder.slots[i].setVisibility(View.VISIBLE);
                        // 防动画浮点误差，强制对齐到精确静态值
                        holder.slots[i].setTranslationX(posToTranslationX(holder, p));
                        holder.slots[i].setScaleX(posToScale(p));
                        holder.slots[i].setScaleY(posToScale(p));
                        holder.slots[i].setAlpha(posToAlpha(p));
                        holder.slots[i].setTranslationZ(posToZ(p));
                    }
                }

                // === 步骤 4：更新中间卡片详情 + 索引 + 指示器 ===
                int newCenterIdx = mod(oldCenter + 1, n);
                holder.currentCenterIdx = newCenterIdx;
                Vod newCenterVod = holder.carouselVods.get(newCenterIdx);
                bindCenterDetails(holder, newCenterVod);

                // 更新所有 slot 的点击事件（因为中间详情换了，所有 slot 也更新一遍保险）
                for (int i = 0; i < NUM_SLOTS; i++) slotClickListenerReset(holder, i);

                // 指示器
                for (int i = 0; i < holder.carouselSize; i++) {
                    View dot = holder.binding.indicatorLayout.getChildAt(i);
                    if (dot != null) {
                        dot.setBackgroundResource(i == newCenterIdx ? R.drawable.shape_dot_active : R.drawable.shape_dot_inactive);
                    }
                }
                holder.marqueeAnimator = null;

                // === 步骤 5：预加载后续可能出现的新海报（保险） ===
                for (int k = 3; k <= 5; k++) {
                    int idx = mod(newCenterIdx + k, n);
                    Vod v = holder.carouselVods.get(idx);
                    if (v != null && v.getPic() != null) {
                        try { Glide.with(holder.binding.getRoot().getContext()).load(v.getPic()).preload(); } catch (Exception ignored) {}
                    }
                }
            }
        });
        holder.marqueeAnimator = set;
        set.start();
    }

    // ============ 向右切换一次的动画（与 advanceCoverFlow 对称） ============

    /**
     * 向右后退一帧：
     *   每个物理 slot 的逻辑位置从 p → p+1
     *     pos +2 → +3 (飞出可见区右侧，alpha→0，scale→0.5)
     *     pos +1 → +2，pos 0 → +1，pos -1 → 0，pos -2 → -1
     *     pos -3 → -2 (从左侧缓冲区进入可见区，alpha 0→1，scale 0.5→0.72)
     *     pos -4 → -3 (进入左侧缓冲区)
     *   动画结束后：
     *     刚飞出可见区的 slot（现在 pos = +3）绑定新数据（currentCenterIdx - 4），
     *     其逻辑位置重置为 -4（新最左缓冲），瞬间移动到 -4 位置（因为不可见所以无视觉跳变）。
     *     其他 7 个 slot 已经停在它们的新静态位置（p+1），可见的 5 张（-2..+2）修正对齐。
     *     更新 currentCenterIdx--，详情文字，指示器，点击事件。
     */
    private void retreatCoverFlow(ViewHolder holder) {
        if (holder.marqueeAnimator != null && holder.marqueeAnimator.isRunning()) return;
        layoutCoverFlow(holder); // 保险：刷新尺寸

        final int n = holder.carouselSize;
        AnimatorSet set = new AnimatorSet();
        List<Animator> anims = new java.util.ArrayList<>(NUM_SLOTS * 5);

        // 对每个 slot 创建从当前 pos → pos+1 的动画
        for (int i = 0; i < NUM_SLOTS; i++) {
            int fromPos = holder.slotPosition[i];
            int toPos   = fromPos + 1;
            FrameLayout slot = holder.slots[i];
            slot.setVisibility(View.VISIBLE); // 动画期间保持 VISIBLE（靠 alpha 控制显隐）

            float fromTX = slot.getTranslationX();
            float toTX   = posToTranslationX(holder, toPos);
            float fromSX = slot.getScaleX();
            float toSX   = posToScale(toPos);
            float fromSY = slot.getScaleY();
            float toSY   = posToScale(toPos);
            float fromA  = slot.getAlpha();
            float toA    = posToAlpha(toPos);
            float fromZ  = slot.getTranslationZ();
            float toZ    = posToZ(toPos);

            anims.add(ObjectAnimator.ofFloat(slot, "translationX", fromTX, toTX));
            anims.add(ObjectAnimator.ofFloat(slot, "scaleX", fromSX, toSX));
            anims.add(ObjectAnimator.ofFloat(slot, "scaleY", fromSY, toSY));
            anims.add(ObjectAnimator.ofFloat(slot, "alpha", fromA, toA));
            if (fromZ != toZ) {
                anims.add(ObjectAnimator.ofFloat(slot, "translationZ", fromZ, toZ));
            }
        }
        set.playTogether(anims);
        set.setDuration(MARQUEE_ANIM_MS);
        set.setInterpolator(SMOOTH_INTERP);

        final int oldCenter = holder.currentCenterIdx;
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // === 步骤 1：更新所有 slot 逻辑位置（p → p+1） ===
                int exitSlotIdx = -1;
                for (int i = 0; i < NUM_SLOTS; i++) {
                    holder.slotPosition[i] += 1;
                    if (holder.slotPosition[i] == 3) exitSlotIdx = i; // 飞出可见区右侧（刚从+2出来）
                }

                // === 步骤 2：把退出可见区的 slot（pos=+3）回收为新的最左缓冲（pos=-4） ===
                if (exitSlotIdx >= 0) {
                    int newCenter = mod(oldCenter - 1, n);
                    // 这个 slot 应该承载数据：newCenter - 4（对应位置 -4，作为最左侧缓冲）
                    int newDataIdx = mod(newCenter - 4, n);
                    holder.slotDataIndex[exitSlotIdx] = newDataIdx;
                    Vod newVod = holder.carouselVods.get(newDataIdx);
                    ImgUtil.load(newVod.getName(), newVod.getPic(), holder.imgs[exitSlotIdx]);
                    // 逻辑位置从 +3 改为 -4，瞬间放置到 -4 位置（不可见，alpha=0）
                    holder.slotPosition[exitSlotIdx] = -4;
                    FrameLayout s = holder.slots[exitSlotIdx];
                    s.setTranslationX(posToTranslationX(holder, -4));
                    s.setScaleX(posToScale(-4));
                    s.setScaleY(posToScale(-4));
                    s.setAlpha(posToAlpha(-4));
                    s.setTranslationZ(posToZ(-4));
                    s.setVisibility(View.INVISIBLE); // 缓冲保持 INVISIBLE，下次动画时再改为 VISIBLE
                    slotClickListenerReset(holder, exitSlotIdx);
                }

                // === 步骤 3：5 张可见 slot（-2..+2）修正 VISIBLE + 精确对齐 ===
                for (int i = 0; i < NUM_SLOTS; i++) {
                    int p = holder.slotPosition[i];
                    if (p >= -2 && p <= 2) {
                        holder.slots[i].setVisibility(View.VISIBLE);
                        holder.slots[i].setTranslationX(posToTranslationX(holder, p));
                        holder.slots[i].setScaleX(posToScale(p));
                        holder.slots[i].setScaleY(posToScale(p));
                        holder.slots[i].setAlpha(posToAlpha(p));
                        holder.slots[i].setTranslationZ(posToZ(p));
                    }
                }

                // === 步骤 4：更新中间卡片详情 + 索引 + 指示器 ===
                int newCenterIdx = mod(oldCenter - 1, n);
                holder.currentCenterIdx = newCenterIdx;
                Vod newCenterVod = holder.carouselVods.get(newCenterIdx);
                bindCenterDetails(holder, newCenterVod);

                for (int i = 0; i < NUM_SLOTS; i++) slotClickListenerReset(holder, i);

                for (int i = 0; i < holder.carouselSize; i++) {
                    View dot = holder.binding.indicatorLayout.getChildAt(i);
                    if (dot != null) {
                        dot.setBackgroundResource(i == newCenterIdx ? R.drawable.shape_dot_active : R.drawable.shape_dot_inactive);
                    }
                }
                holder.marqueeAnimator = null;

                // === 步骤 5：反向预加载（向左缓冲） ===
                for (int k = -5; k <= -3; k++) {
                    int idx = mod(newCenterIdx + k, n);
                    Vod v = holder.carouselVods.get(idx);
                    if (v != null && v.getPic() != null) {
                        try { Glide.with(holder.binding.getRoot().getContext()).load(v.getPic()).preload(); } catch (Exception ignored) {}
                    }
                }
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
        if (holder.binding.indicatorLayout != null) {
            holder.binding.indicatorLayout.removeAllViews();
        }
    }

    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
        ViewHolder holder = (ViewHolder) viewHolder;
        stopMarquee(holder);
        for (int i = 0; i < NUM_SLOTS; i++) {
            try { Glide.with(holder.binding.getRoot().getContext()).clear(holder.imgs[i]); } catch (Exception ignored) {}
        }
        resetScale(holder.binding.btnVod);
        resetScale(holder.binding.btnLive);
        resetScale(holder.binding.btnKeep);
        resetScale(holder.binding.middleCard);
    }

    private void resetScale(View v) {
        if (v == null) return;
        v.setScaleX(1.0f);
        v.setScaleY(1.0f);
        v.setTranslationZ(0f);
        v.setTranslationX(0f);
        v.setAlpha(1f);
    }

    public static class ViewHolder extends Presenter.ViewHolder {
        public final AdapterHomeBannerBinding binding;

        public final FrameLayout[] slots = new FrameLayout[NUM_SLOTS];
        public final android.widget.ImageView[] imgs = new android.widget.ImageView[NUM_SLOTS];
        /** 每个物理 slot 当前承载的"逻辑位置"（-4..+4 之间循环） */
        public final int[] slotPosition = new int[NUM_SLOTS];
        /** 每个物理 slot 当前绑定的数据索引（carouselVods 列表内的 index） */
        public final int[] slotDataIndex = new int[NUM_SLOTS];

        public Runnable marqueeRunnable;
        public AnimatorSet marqueeAnimator;
        public List<Vod> carouselVods;
        public int carouselSize;
        public int currentCenterIdx;
        public boolean isMarqueePaused;

        // 布局参数缓存
        public int containerWidth;
        public int cardWidth;
        public int stepX;
        public int centerTX;

        public ViewHolder(@NonNull AdapterHomeBannerBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            // 通过 id 查找 8 个 slot 与对应 img（使用数组避免手写 binding 引用错误）
            int[] slotIds = {
                R.id.cardSlot0, R.id.cardSlot1, R.id.cardSlot2, R.id.cardSlot3,
                R.id.cardSlot4, R.id.cardSlot5, R.id.cardSlot6, R.id.cardSlot7
            };
            int[] imgIds = {
                R.id.imgSlot0, R.id.imgSlot1, R.id.imgSlot2, R.id.imgSlot3,
                R.id.imgSlot4, R.id.imgSlot5, R.id.imgSlot6, R.id.imgSlot7
            };
            for (int i = 0; i < NUM_SLOTS; i++) {
                slots[i] = binding.getRoot().findViewById(slotIds[i]);
                imgs[i]  = binding.getRoot().findViewById(imgIds[i]);
            }
            java.util.Arrays.fill(slotPosition, 0);
            java.util.Arrays.fill(slotDataIndex, 0);
        }
    }
}
