package com.example.youtubeoverlay.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.example.youtubeoverlay.util.ClipboardUtils
import com.example.youtubeoverlay.worker.YouTubeMediaExtractor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class OverlayAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_START_MACRO = "com.example.youtubeoverlay.ACTION_START_MACRO"
        const val EXTRA_DOWNLOAD_TYPE = "EXTRA_DOWNLOAD_TYPE"
    }

    private var isYouTubeActive = false
    private var isMacroRunning = false
    private var pendingDownloadType: String? = null

    @Inject
    lateinit var youTubeMediaExtractor: YouTubeMediaExtractor

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private val macroReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_START_MACRO) {
                val downloadType = intent.getStringExtra(EXTRA_DOWNLOAD_TYPE)
                if (downloadType != null) {
                    startMacro(downloadType)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter(ACTION_START_MACRO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(macroReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(macroReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(macroReceiver)
        serviceScope.cancel()
    }

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
                // Cancel macro if user switches app
                if (isMacroRunning) {
                    isMacroRunning = false
                    pendingDownloadType = null
                }
            }
        }
    }

    override fun onInterrupt() {
        // Not used
    }

    private fun startMacro(downloadType: String) {
        if (isMacroRunning) return
        isMacroRunning = true
        pendingDownloadType = downloadType

        Toast.makeText(this, "Extracting link automatically...", Toast.LENGTH_SHORT).show()

        serviceScope.launch {
            try {
                // Step 1: Find and click 'Share'
                val shareClicked = clickShareButton()
                if (!shareClicked) {
                    throw Exception("Could not find Share button.")
                }

                // Step 2: Wait a bit for the bottom sheet to animate in
                delay(1000)

                // Step 3: Find and click 'Copy link'
                val copyClicked = clickCopyLinkButton()
                if (!copyClicked) {
                     // Try closing the bottom sheet to recover state if possible
                     performGlobalAction(GLOBAL_ACTION_BACK)
                     throw Exception("Could not find Copy Link button.")
                }

                // Step 4: Fallback to reading the accessibility nodes directly to grab the URL if possible
                // since Android 10+ restricts clipboard access from background services unless they are default IME.
                // However, Accessibility Services *can* sometimes read the clipboard or node text.
                // Let's try capturing the text from the node itself after "Copy link" is clicked.

                // Wait for the OS to hopefully copy it.
                delay(500)

                val url = ClipboardUtils.getClipboardText(this@OverlayAccessibilityService)

                if (ClipboardUtils.isYouTubeUrl(url)) {
                    Toast.makeText(this@OverlayAccessibilityService, "URL Extracted. Starting Download!", Toast.LENGTH_SHORT).show()
                    dispatchDownloadTask(url!!, downloadType)
                } else {
                    Toast.makeText(this@OverlayAccessibilityService, "Could not extract automatically. Please share video manually.", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                Toast.makeText(this@OverlayAccessibilityService, "Macro failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isMacroRunning = false
                pendingDownloadType = null
            }
        }
    }

    private fun dispatchDownloadTask(url: String, typeString: String) {
        val downloadType = when (typeString) {
            "Thumbnail" -> YouTubeMediaExtractor.DownloadType.THUMBNAIL
            "MP3" -> YouTubeMediaExtractor.DownloadType.MP3
            "MP4" -> YouTubeMediaExtractor.DownloadType.MP4
            else -> return
        }
        youTubeMediaExtractor.dispatchDownload(url, downloadType)
    }

    private fun clickShareButton(): Boolean {
        val root = rootInActiveWindow ?: return false

        // Search by content description first (often more reliable for icon buttons)
        val shareNodesByDesc = root.findAccessibilityNodeInfosByText("Share")
        for (node in shareNodesByDesc) {
             if (node.isClickable) {
                 node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                 return true
             } else if (node.parent?.isClickable == true) {
                 node.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                 return true
             }
        }

        // Search for 'Paylaş' (Turkish support)
        val shareNodesTR = root.findAccessibilityNodeInfosByText("Paylaş")
        for (node in shareNodesTR) {
             if (node.isClickable) {
                 node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                 return true
             } else if (node.parent?.isClickable == true) {
                 node.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                 return true
             }
        }

        return false
    }

    private fun clickCopyLinkButton(): Boolean {
        val root = rootInActiveWindow ?: return false

        val possibleTexts = listOf("Copy link", "Bağlantıyı kopyala", "Copy")

        for (text in possibleTexts) {
             val nodes = root.findAccessibilityNodeInfosByText(text)
             for (node in nodes) {
                 if (node.isClickable) {
                     node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                     return true
                 } else if (node.parent?.isClickable == true) {
                     node.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                     return true
                 }
             }
        }

        return false
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
