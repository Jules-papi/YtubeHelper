package com.example.youtubeoverlay.worker

import android.content.Context
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import io.mockk.*
import io.mockk.impl.annotations.MockK
import org.junit.After
import org.junit.Before
import org.junit.Test

class YouTubeMediaExtractorTest {

    @MockK
    lateinit var context: Context

    @MockK
    lateinit var workManager: WorkManager

    private lateinit var extractor: YouTubeMediaExtractor

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(context) } returns workManager
        extractor = YouTubeMediaExtractor(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `dispatchDownload should enqueue WorkManager request`() {
        // Arrange
        val testUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val testType = YouTubeMediaExtractor.DownloadType.MP3
        every { workManager.enqueue(any<OneTimeWorkRequest>()) } returns mockk()

        // Act
        extractor.dispatchDownload(testUrl, testType)

        // Assert
        verify { workManager.enqueue(any<OneTimeWorkRequest>()) }
    }
}
