package com.example.youtubeoverlay.util

import android.view.accessibility.AccessibilityNodeInfo

object AccessibilityActions {
    fun forceScrollToReveal(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val scrollableNodes = root.findAccessibilityNodeInfosByText("Share") // We just need a starting point to test
        return true
    }
}
