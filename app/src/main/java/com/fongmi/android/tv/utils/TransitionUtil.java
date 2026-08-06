package com.fongmi.android.tv.utils;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;

/**
 * Centralized transition animation utility.
 * Provides animation resources for both Activity and Fragment transitions
 * based on the user's transition animation setting.
 *
 * Modes:
 * 0 = None (returns null, no animation)
 * 1 = Fade
 * 2 = Slide
 * 3 = Zoom
 * 4 = Flip (horizontal squeeze + fade)
 * 5 = Push (vertical slide)
 * 6 = Bounce (overshoot scale)
 * 7 = Expand (scale from zero)
 */
public class TransitionUtil {

    /**
     * Returns transition animation resources for Fragment transactions.
     *
     * @param forward true for forward navigation (deeper), false for backward (returning)
     * @return int[4] = {enter, exit, popEnter, popExit}, or null if transition is disabled
     */
    @Nullable
    public static int[] getFragmentAnims(boolean forward) {
        int mode = Setting.getTransition();
        if (mode == 0) return null;
        int enter, exit, popEnter, popExit;
        if (mode == 1) { // Fade
            enter = R.anim.transition_fade_enter;
            exit = R.anim.transition_fade_exit;
            popEnter = R.anim.transition_fade_enter;
            popExit = R.anim.transition_fade_exit;
        } else if (mode == 2) { // Slide
            if (forward) {
                enter = R.anim.transition_slide_enter;
                exit = R.anim.transition_slide_exit;
                popEnter = R.anim.transition_slide_pop_enter;
                popExit = R.anim.transition_slide_pop_exit;
            } else {
                enter = R.anim.transition_slide_pop_enter;
                exit = R.anim.transition_slide_pop_exit;
                popEnter = R.anim.transition_slide_enter;
                popExit = R.anim.transition_slide_exit;
            }
        } else if (mode == 3) { // Zoom
            if (forward) {
                enter = R.anim.transition_zoom_enter;
                exit = R.anim.transition_zoom_exit;
                popEnter = R.anim.transition_zoom_pop_enter;
                popExit = R.anim.transition_zoom_pop_exit;
            } else {
                enter = R.anim.transition_zoom_pop_enter;
                exit = R.anim.transition_zoom_pop_exit;
                popEnter = R.anim.transition_zoom_enter;
                popExit = R.anim.transition_zoom_exit;
            }
        } else if (mode == 4) { // Flip
            if (forward) {
                enter = R.anim.transition_flip_enter;
                exit = R.anim.transition_flip_exit;
                popEnter = R.anim.transition_flip_pop_enter;
                popExit = R.anim.transition_flip_pop_exit;
            } else {
                enter = R.anim.transition_flip_pop_enter;
                exit = R.anim.transition_flip_pop_exit;
                popEnter = R.anim.transition_flip_enter;
                popExit = R.anim.transition_flip_exit;
            }
        } else if (mode == 5) { // Push
            if (forward) {
                enter = R.anim.transition_push_enter;
                exit = R.anim.transition_push_exit;
                popEnter = R.anim.transition_push_pop_enter;
                popExit = R.anim.transition_push_pop_exit;
            } else {
                enter = R.anim.transition_push_pop_enter;
                exit = R.anim.transition_push_pop_exit;
                popEnter = R.anim.transition_push_enter;
                popExit = R.anim.transition_push_exit;
            }
        } else if (mode == 6) { // Bounce
            if (forward) {
                enter = R.anim.transition_bounce_enter;
                exit = R.anim.transition_bounce_exit;
                popEnter = R.anim.transition_bounce_pop_enter;
                popExit = R.anim.transition_bounce_pop_exit;
            } else {
                enter = R.anim.transition_bounce_pop_enter;
                exit = R.anim.transition_bounce_pop_exit;
                popEnter = R.anim.transition_bounce_enter;
                popExit = R.anim.transition_bounce_exit;
            }
        } else { // Expand (mode == 7)
            if (forward) {
                enter = R.anim.transition_expand_enter;
                exit = R.anim.transition_expand_exit;
                popEnter = R.anim.transition_expand_pop_enter;
                popExit = R.anim.transition_expand_pop_exit;
            } else {
                enter = R.anim.transition_expand_pop_enter;
                exit = R.anim.transition_expand_pop_exit;
                popEnter = R.anim.transition_expand_enter;
                popExit = R.anim.transition_expand_exit;
            }
        }
        return new int[]{enter, exit, popEnter, popExit};
    }

    /**
     * Returns transition animation resources for Activity transitions.
     *
     * @param enter true for startActivity (entering), false for finish (exiting)
     * @return int[2] = {enterAnim, exitAnim}, or null if transition is disabled
     */
    @Nullable
    public static int[] getActivityAnims(boolean enter) {
        int mode = Setting.getTransition();
        if (mode == 0) return null;
        int enterAnim, exitAnim;
        if (mode == 1) { // Fade
            enterAnim = R.anim.transition_fade_enter;
            exitAnim = R.anim.transition_fade_exit;
        } else if (mode == 2) { // Slide
            if (enter) {
                enterAnim = R.anim.transition_slide_enter;
                exitAnim = R.anim.transition_slide_exit;
            } else {
                enterAnim = R.anim.transition_slide_pop_enter;
                exitAnim = R.anim.transition_slide_pop_exit;
            }
        } else if (mode == 3) { // Zoom
            if (enter) {
                enterAnim = R.anim.transition_zoom_enter;
                exitAnim = R.anim.transition_zoom_exit;
            } else {
                enterAnim = R.anim.transition_zoom_pop_enter;
                exitAnim = R.anim.transition_zoom_pop_exit;
            }
        } else if (mode == 4) { // Flip
            if (enter) {
                enterAnim = R.anim.transition_flip_enter;
                exitAnim = R.anim.transition_flip_exit;
            } else {
                enterAnim = R.anim.transition_flip_pop_enter;
                exitAnim = R.anim.transition_flip_pop_exit;
            }
        } else if (mode == 5) { // Push
            if (enter) {
                enterAnim = R.anim.transition_push_enter;
                exitAnim = R.anim.transition_push_exit;
            } else {
                enterAnim = R.anim.transition_push_pop_enter;
                exitAnim = R.anim.transition_push_pop_exit;
            }
        } else if (mode == 6) { // Bounce
            if (enter) {
                enterAnim = R.anim.transition_bounce_enter;
                exitAnim = R.anim.transition_bounce_exit;
            } else {
                enterAnim = R.anim.transition_bounce_pop_enter;
                exitAnim = R.anim.transition_bounce_pop_exit;
            }
        } else { // Expand (mode == 7)
            if (enter) {
                enterAnim = R.anim.transition_expand_enter;
                exitAnim = R.anim.transition_expand_exit;
            } else {
                enterAnim = R.anim.transition_expand_pop_enter;
                exitAnim = R.anim.transition_expand_pop_exit;
            }
        }
        return new int[]{enterAnim, exitAnim};
    }
}
