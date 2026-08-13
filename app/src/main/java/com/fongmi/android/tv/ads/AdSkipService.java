package com.fongmi.android.tv.ads;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 无障碍服务 - 用于自动点击跳过广告按钮
 */
public class AdSkipService extends AccessibilityService {

    private static final String TAG = "AdSkipService";
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            searchForSkipButton();
        }
    }
    
    @Override
    public void onInterrupt() {
        Log.e(TAG, "Accessibility service interrupted");
    }
    
    private void searchForSkipButton() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        
        findAndClickSkipButton(root);
    }
    
    private void findAndClickSkipButton(AccessibilityNodeInfo node) {
        if (node == null) return;
        
        if ("跳过广告".equals(node.getText()) || 
            "Skip Ad".equals(node.getText()) ||
            "跳过".equals(node.getText())) {
            Log.d(TAG, "Found skip button!");
            if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d(TAG, "Clicked skip button");
            }
            return;
        }
        
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findAndClickSkipButton(child);
                child.recycle();
            }
        }
    }
}
