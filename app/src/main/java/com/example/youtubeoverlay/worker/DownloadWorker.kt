package com.example.youtubeoverlay.worker

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString("url") ?: return@withContext Result.failure()
        val typeStr = inputData.getString("type") ?: return@withContext Result.failure()
        val type = YouTubeMediaExtractor.DownloadType.valueOf(typeStr)

        try {
            // Use unique directory per job to prevent file race conditions across concurrent downloads
            val jobId = id.toString()
            val tmpDir = File(applicationContext.cacheDir, "yt_downloads_$jobId")
            if (!tmpDir.exists()) tmpDir.mkdirs()

            when (type) {
                YouTubeMediaExtractor.DownloadType.THUMBNAIL -> downloadThumbnail(url, tmpDir)
                YouTubeMediaExtractor.DownloadType.MP3 -> downloadAudio(url, tmpDir)
                YouTubeMediaExtractor.DownloadType.MP4 -> downloadVideo(url, tmpDir)
            }

            // Clean up temporary unique directory
            tmpDir.deleteRecursively()
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }

    private suspend fun downloadThumbnail(url: String, tmpDir: File) {
        val videoId = extractVideoId(url) ?: throw IllegalArgumentException("Invalid YouTube URL")
        val maxResUrl = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"
        val destFile = File(tmpDir, "thumb_$videoId.jpg")

        URL(maxResUrl).openStream().use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        moveToMediaStore(destFile, "image/jpeg", Environment.DIRECTORY_PICTURES)
    }

    private fun downloadAudio(url: String, tmpDir: File) {
        val request = YoutubeDLRequest(url)
        request.addOption("-x")
        request.addOption("--audio-format", "mp3")
        request.addOption("--audio-quality", "0")
        request.addOption("-o", "${tmpDir.absolutePath}/%(title)s.%(ext)s")

        YoutubeDL.getInstance().execute(request) { progress, etaInSeconds, line ->
            // Update progress if needed
        }

        // Find downloaded mp3 file and move it
        val downloadedFile = tmpDir.listFiles()?.find { it.extension == "mp3" }
        downloadedFile?.let {
            moveToMediaStore(it, "audio/mpeg", Environment.DIRECTORY_MUSIC)
        }
    }

    private fun downloadVideo(url: String, tmpDir: File) {
        val request = YoutubeDLRequest(url)
        request.addOption("-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best")
        request.addOption("-o", "${tmpDir.absolutePath}/%(title)s.%(ext)s")

        YoutubeDL.getInstance().execute(request) { progress, etaInSeconds, line ->
            // Update progress if needed
        }

        val downloadedFile = tmpDir.listFiles()?.find { it.extension == "mp4" }
        downloadedFile?.let {
            moveToMediaStore(it, "video/mp4", Environment.DIRECTORY_MOVIES)
        }
    }

    private fun extractVideoId(url: String): String? {
        val regex = "(?:youtube\\.com/(?:[^/]+/.+/|(?:v|e(?:mbed)?)/|.*[?&]v=)|youtu\\.be/)([^\"&?/ ]{11})".toRegex()
        val matchResult = regex.find(url)
        return matchResult?.groupValues?.get(1)
    }

    private fun moveToMediaStore(file: File, mimeType: String, directory: String) {
        val resolver = applicationContext.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collectionUri = when (directory) {
            Environment.DIRECTORY_PICTURES -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            Environment.DIRECTORY_MUSIC -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            Environment.DIRECTORY_MOVIES -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI
                } else {
                    // Fallback for API < 29, usually we'd avoid this branch entirely for MediaStore.Downloads
                    // or use a custom Downloads path, but for safety in generic logic we can just use
                    // a more general URI or the Video/Audio URI based on MIME type.
                    MediaStore.Files.getContentUri("external")
                }
            }
        }

        val uri = resolver.insert(collectionUri, contentValues)
        uri?.let {
            resolver.openOutputStream(it).use { outStream ->
                file.inputStream().use { inStream ->
                    inStream.copyTo(outStream!!)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(it, contentValues, null, null)
            }
        }
        file.delete()
    }
}
