package com.example.youtubeoverlay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.youtubeoverlay.repository.VideoUrlRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class OverlayWindowService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        const val ACTION_SHOW_OVERLAY = "ACTION_SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "ACTION_HIDE_OVERLAY"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "OverlayServiceChannel"
    }

    @Inject
    lateinit var videoUrlRepository: VideoUrlRepository

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private var isOverlayShowing = false

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var currentUrl: String? = null

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        serviceScope.launch {
            videoUrlRepository.lastSharedUrl.collectLatest { url ->
                currentUrl = url
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> showOverlay()
            ACTION_HIDE_OVERLAY -> hideOverlay()
        }

        return START_STICKY
    }

    private fun showOverlay() {
        if (isOverlayShowing) return

        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayWindowService)
            setViewTreeSavedStateRegistryOwner(this@OverlayWindowService)
            setContent {
                OverlayContent(
                    onThumbnailClick = { handleAction("Thumbnail") },
                    onMp3Click = { handleAction("MP3") },
                    onMp4Click = { handleAction("MP4") }
                )
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            x = 0
            y = 100
        }

        setupDraggable(composeView!!, params)
        windowManager?.addView(composeView, params)
        isOverlayShowing = true
    }

    private fun hideOverlay() {
        if (!isOverlayShowing) return
        if (composeView != null) {
            windowManager?.removeView(composeView)
            composeView = null
        }
        isOverlayShowing = false
    }

    @Inject
    lateinit var youTubeMediaExtractor: com.example.youtubeoverlay.worker.YouTubeMediaExtractor

    private fun handleAction(action: String) {
        val downloadType = when (action) {
            "Thumbnail" -> com.example.youtubeoverlay.worker.YouTubeMediaExtractor.DownloadType.THUMBNAIL
            "MP3" -> com.example.youtubeoverlay.worker.YouTubeMediaExtractor.DownloadType.MP3
            "MP4" -> com.example.youtubeoverlay.worker.YouTubeMediaExtractor.DownloadType.MP4
            else -> return
        }

        if (currentUrl != null) {
            // We have a URL already saved via system share sheet
            Toast.makeText(this, "Starting $action download for cached URL", Toast.LENGTH_SHORT).show()
            youTubeMediaExtractor.dispatchDownload(currentUrl!!, downloadType)
        } else {
            // Trigger the macro directly to fetch the active video
            val intent = Intent(OverlayAccessibilityService.ACTION_START_MACRO)
            intent.setPackage(packageName) // Explicit broadcast for API 34+ restrictions
            intent.putExtra(OverlayAccessibilityService.EXTRA_DOWNLOAD_TYPE, action)
            sendBroadcast(intent)
        }
    }

    private fun setupDraggable(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX - (event.rawX - initialTouchX).toInt() // Assuming gravity END
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        serviceJob.cancel()
        hideOverlay()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Overlay Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("YouTube Overlay Active")
            .setContentText("Waiting for YouTube to be in the foreground")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }
}

@Composable
fun OverlayContent(
    onThumbnailClick: () -> Unit,
    onMp3Click: () -> Unit,
    onMp4Click: () -> Unit
) {
    Column(
        modifier = Modifier
            .background(Color(0xAA000000), shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OverlayButton(text = "IMG", color = Color(0xFFE91E63), onClick = onThumbnailClick)
        OverlayButton(text = "MP3", color = Color(0xFF2196F3), onClick = onMp3Click)
        OverlayButton(text = "MP4", color = Color(0xFF4CAF50), onClick = onMp4Click)
    }
}

@Composable
fun OverlayButton(text: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        modifier = Modifier.size(48.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(text = text, fontSize = 12.sp, color = Color.White)
    }
}
