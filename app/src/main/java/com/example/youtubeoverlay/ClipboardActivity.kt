package com.example.youtubeoverlay

import androidx.activity.ComponentActivity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.example.youtubeoverlay.util.ClipboardUtils
import com.example.youtubeoverlay.worker.YouTubeMediaExtractor
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ClipboardActivity : ComponentActivity() {

    companion object {
        const val EXTRA_DOWNLOAD_TYPE = "EXTRA_DOWNLOAD_TYPE"
    }

    @Inject
    lateinit var youTubeMediaExtractor: YouTubeMediaExtractor

    private var clipboardReadAttempted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nothing here, wait for Window Focus
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus && !clipboardReadAttempted) {
            clipboardReadAttempted = true

            val downloadTypeStr = intent.getStringExtra(EXTRA_DOWNLOAD_TYPE) ?: return finish()

            // Activity now has window focus, reading clipboard is allowed on Android 10+
            val url = ClipboardUtils.getClipboardText(this)

            if (ClipboardUtils.isYouTubeUrl(url)) {
                val finalUrl = extractUrl(url!!)
                val downloadType = when (downloadTypeStr) {
                    "Thumbnail" -> YouTubeMediaExtractor.DownloadType.THUMBNAIL
                    "MP3" -> YouTubeMediaExtractor.DownloadType.MP3
                    "MP4" -> YouTubeMediaExtractor.DownloadType.MP4
                    else -> null
                }
                if (downloadType != null) {
                    Toast.makeText(this, "URL Extracted. Starting Download!", Toast.LENGTH_SHORT).show()
                    youTubeMediaExtractor.dispatchDownload(finalUrl, downloadType)
                }
            } else {
                Toast.makeText(this, "Could not read YouTube URL from clipboard.", Toast.LENGTH_SHORT).show()
            }

            // Close instantly to drop back to YouTube
            finish()
        }
    }

    private fun extractUrl(text: String): String {
        val urlRegex = "https?://[a-zA-Z0-9./?=_-]+".toRegex()
        val matchResult = urlRegex.find(text)
        return matchResult?.value ?: text
    }
}
