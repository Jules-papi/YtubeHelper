package com.example.youtubeoverlay.worker

import android.content.Context
import androidx.work.*
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeMediaExtractor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun dispatchDownload(url: String, type: DownloadType) {
        val workManager = WorkManager.getInstance(context)
        val data = Data.Builder()
            .putString("url", url)
            .putString("type", type.name)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(data)
            .build()

        workManager.enqueue(workRequest)
    }

    enum class DownloadType {
        THUMBNAIL, MP3, MP4
    }
}
