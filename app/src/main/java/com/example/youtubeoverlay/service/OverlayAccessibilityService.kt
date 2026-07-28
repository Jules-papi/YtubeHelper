package com.example.youtubeoverlay.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class OverlayAccessibilityService : AccessibilityService() {

    private var isYouTubeActive = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            val wasYouTubeActive = isYouTubeActive

            isYouTubeActive = packageName == "com.google.android.youtube"

            if (isYouTubeActive && !wasYouTubeActive) {
                startOverlayService()
            } else if (!isYouTubeActive && wasYouTubeActive) {
                stopOverlayService()
            }
        }
    }

    override fun onInterrupt() {
        // Not used
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayWindowService::class.java)
        intent.action = OverlayWindowService.ACTION_SHOW_OVERLAY
        startForegroundService(intent)
    }

    private fun stopOverlayService() {
        val intent = Intent(this, OverlayWindowService::class.java)
        intent.action = OverlayWindowService.ACTION_HIDE_OVERLAY
        startForegroundService(intent)
    }
}
