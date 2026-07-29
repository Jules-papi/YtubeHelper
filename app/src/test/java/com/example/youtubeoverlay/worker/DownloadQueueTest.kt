package com.example.youtubeoverlay.worker

import android.content.Context
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadQueueTest {

    @MockK
    lateinit var context: Context

    @MockK
    lateinit var workerParams: WorkerParameters

    private lateinit var worker: DownloadWorker

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        every { workerParams.taskExecutor } returns mockk(relaxed = true)
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns mockk<android.app.NotificationManager>(relaxed = true)
        worker = spyk(DownloadWorker(context, workerParams))
        coEvery { worker.setForeground(any()) } returns Unit
        coEvery { worker.getForegroundInfo() } returns mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `doWork returns failure when url is missing`() = runTest {
        // Arrange
        val data = Data.Builder().build()
        every { worker.inputData } returns data

        // Act
        val result = worker.doWork()

        // Assert
        assertEquals(Result.failure(), result)
    }

    @Test
    fun `doWork returns failure when type is missing`() = runTest {
        // Arrange
        val data = Data.Builder().putString("url", "http://example.com").build()
        every { worker.inputData } returns data

        // Act
        val result = worker.doWork()

        // Assert
        assertEquals(Result.failure(), result)
    }
}
