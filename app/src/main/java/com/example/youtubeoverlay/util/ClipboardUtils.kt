package com.example.youtubeoverlay.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

object ClipboardUtils {
    fun getClipboardText(context: Context): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboard.hasPrimaryClip()) {
            val item: ClipData.Item? = clipboard.primaryClip?.getItemAt(0)
            return item?.text?.toString()
        }
        return null
    }

    fun isYouTubeUrl(text: String?): Boolean {
        if (text == null) return false
        return text.contains("youtube.com") || text.contains("youtu.be")
    }
}
