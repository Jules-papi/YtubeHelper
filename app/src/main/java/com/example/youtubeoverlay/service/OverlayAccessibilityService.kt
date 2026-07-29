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

                // Step 2: Wait longer for the heavy OEM/YouTube Share Sheet (Bottom Sheet) to fully animate and populate
                delay(2000)

                // Step 3: Find and click 'Copy link' inside the dynamic share sheet
                val copyClicked = clickCopyLinkButton()
                if (!copyClicked) {
                     // Try closing the bottom sheet to recover state if possible
                     performGlobalAction(GLOBAL_ACTION_BACK)
                     throw Exception("Could not find Copy Link button in the Share Sheet.")
                }

                // Step 4: Fallback to reading the accessibility nodes directly to grab the URL if possible
                // since Android 10+ restricts clipboard access from background services unless they are default IME.
                // However, Accessibility Services *can* sometimes read the clipboard or node text.
                // Let's try capturing the text from the node itself after "Copy link" is clicked.

                // Wait for the OS to hopefully copy it.
                delay(500)

                var url = ClipboardUtils.getClipboardText(this@OverlayAccessibilityService)

                // Fallback: Try reading the node directly if Clipboard is blocked (Android 10+)
                if (url == null) {
                    val root = rootInActiveWindow
                    if (root != null) {
                        val allNodes = mutableListOf<AccessibilityNodeInfo>()
                        getAllNodes(root, allNodes)
                        for (node in allNodes) {
                            val text = node.text?.toString()
                            if (ClipboardUtils.isYouTubeUrl(text)) {
                                url = text
                                break
                            }
                        }
                    }
                }

                if (ClipboardUtils.isYouTubeUrl(url)) {
                    val finalUrl = extractUrl(url!!)
                    Toast.makeText(this@OverlayAccessibilityService, "URL Extracted. Starting Download!", Toast.LENGTH_SHORT).show()
                    dispatchDownloadTask(finalUrl, downloadType)
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

    private fun extractUrl(text: String): String {
        val urlRegex = "https?://[a-zA-Z0-9./?=_-]+".toRegex()
        val matchResult = urlRegex.find(text)
        return matchResult?.value ?: text
    }

    private fun getAllNodes(root: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        list.add(root)
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child != null) {
                getAllNodes(child, list)
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

        // Scroll horizontally or vertically a bit if the share button isn't immediately visible
        // We will perform a swipe gesture programmatically in case it's offscreen
        // Only on modern APIs for quick scrolling tests, or we can just ignore since the deep
        // traversal usually gets it. We'll stick to DOM parsing to avoid unexpected skips.
        // Removed unimplemented GLOBAL_ACTION_SCROLL_FORWARD for API compat

        // 1. Try View ID matching for YouTube Share Buttons (Shorts or Standard Video)
        val possibleIds = listOf(
            "com.google.android.youtube:id/share_button",
            "com.google.android.youtube:id/button_icon" // Often used in horizontal scroll lists
        )

        for (id in possibleIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                // If it's a generic button_icon, verify its content description
                if (id.contains("button_icon") && !isShareKeyword(node.contentDescription?.toString())) {
                    continue
                }
                if (performClickOnNode(node)) return true
            }
        }

        // 2. Deep recursive search for contentDescription / Text
        return findAndClickByKeywords(root, listOf("Share", "Paylaş", "Compartir", "Partager", "Teilen"))
    }

    private fun clickCopyLinkButton(): Boolean {
        val root = rootInActiveWindow ?: return false

        // 1. Try View ID matching for known Youtube or Android Chooser IDs
        val possibleIds = listOf(
            "com.google.android.youtube:id/copy_button", // Older YouTube direct share
            "android:id/chooser_row_text_option",        // Standard Android chooser row
            "android:id/text1"                           // Generic Android list item
        )

        for (id in possibleIds) {
            val copyNodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in copyNodes) {
                // If it's a generic ID, verify text contains copy keywords before clicking
                if (id == "android:id/chooser_row_text_option" || id == "android:id/text1") {
                    val text = node.text?.toString()
                    val desc = node.contentDescription?.toString()
                    val isCopy = isCopyKeyword(text) || isCopyKeyword(desc)
                    if (isCopy && performClickOnNode(node)) return true
                } else {
                    if (performClickOnNode(node)) return true
                }
            }
        }

        // 2. Broad and Deep recursive search for Text (across all OEM share sheets)
        // These words cover primary intents seen in Samsung, Xiaomi, Pixel Android implementations.
        val possibleTexts = listOf(
            "Copy link", "Bağlantıyı kopyala", "Copy", "Kopyala", "Kopyalayın", "Copiar enlace",
            "Copier le lien", "Link kopieren", "Panoya kopyala", "Copy to clipboard"
        )
        return findAndClickByKeywords(root, possibleTexts)
    }

    private fun isCopyKeyword(text: String?): Boolean {
        if (text == null) return false
        val keywords = listOf("Copy", "Kopyala", "Copiar", "Copier", "kopieren")
        return keywords.any { text.contains(it, ignoreCase = true) }
    }

    private fun isShareKeyword(text: String?): Boolean {
        if (text == null) return false
        val keywords = listOf("Share", "Paylaş", "Compartir", "Partager", "Teilen")
        return keywords.any { text.contains(it, ignoreCase = true) }
    }

    private fun findAndClickByKeywords(root: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        // Broad search using built-in text search
        for (keyword in keywords) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            for (node in nodes) {
                if (performClickOnNode(node)) return true
            }
        }

        // Manual recursive traversal as a final fallback
        return traverseAndClick(root, keywords)
    }

    private fun traverseAndClick(node: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()

        if (keywords.any { text?.contains(it, ignoreCase = true) == true || desc?.contains(it, ignoreCase = true) == true }) {
            if (performClickOnNode(node)) return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (traverseAndClick(child, keywords)) return true
            }
        }
        return false
    }

    private fun performClickOnNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        if (node.parent?.isClickable == true) {
            node.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
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
