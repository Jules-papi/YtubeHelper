package com.example.youtubeoverlay

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import com.example.youtubeoverlay.repository.VideoUrlRepository
import com.example.youtubeoverlay.service.OverlayAccessibilityService
import com.example.youtubeoverlay.util.AccessibilityUtils
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val videoUrlRepository: VideoUrlRepository
) : ViewModel() {
    fun setUrl(url: String) {
        videoUrlRepository.updateUrl(url)
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Handle post-permission logic if needed
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissions()
        handleIntent(intent)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var isOverlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(this)) }
                    var isAccessibilityGranted by remember {
                        mutableStateOf(AccessibilityUtils.isAccessibilityServiceEnabled(this, OverlayAccessibilityService::class.java))
                    }

                    MainScreen(
                        isOverlayGranted = isOverlayGranted,
                        isAccessibilityGranted = isAccessibilityGranted,
                        onCheckOverlayPermission = { requestOverlayPermission() },
                        onCheckAccessibilityPermission = { openAccessibilitySettings() }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (sharedText != null && isYouTubeUrl(sharedText)) {
                val url = extractUrl(sharedText)
                viewModel.setUrl(url)
                Toast.makeText(this, "YouTube URL captured!", Toast.LENGTH_SHORT).show()
                // Do not keep the UI open, just process it and maybe finish if we want it to act completely transparently.
                // For now, let's keep it simple.
            }
        }
    }

    private fun isYouTubeUrl(text: String): Boolean {
        return text.contains("youtube.com") || text.contains("youtu.be")
    }

    private fun extractUrl(text: String): String {
        val urlRegex = "https?://[a-zA-Z0-9./?=_-]+".toRegex()
        val matchResult = urlRegex.find(text)
        return matchResult?.value ?: text
    }

    override fun onResume() {
        super.onResume()
        // Force recomposition to refresh permissions state after returning from settings
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var isOverlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(this)) }
                    var isAccessibilityGranted by remember {
                        mutableStateOf(AccessibilityUtils.isAccessibilityServiceEnabled(this, OverlayAccessibilityService::class.java))
                    }

                    MainScreen(
                        isOverlayGranted = isOverlayGranted,
                        isAccessibilityGranted = isAccessibilityGranted,
                        onCheckOverlayPermission = { requestOverlayPermission() },
                        onCheckAccessibilityPermission = { openAccessibilitySettings() }
                    )
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }
}

@Composable
fun MainScreen(
    isOverlayGranted: Boolean,
    isAccessibilityGranted: Boolean,
    onCheckOverlayPermission: () -> Unit,
    onCheckAccessibilityPermission: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "YouTube Overlay Setup", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))

        PermissionStatusItem(
            name = "Draw Over Apps Permission",
            isGranted = isOverlayGranted,
            onRequest = onCheckOverlayPermission
        )

        Spacer(modifier = Modifier.height(16.dp))

        PermissionStatusItem(
            name = "Accessibility Permission",
            isGranted = isAccessibilityGranted,
            onRequest = onCheckAccessibilityPermission
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(text = "Share a YouTube video to this app to set the active URL.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
    }
}

@Composable
fun PermissionStatusItem(
    name: String,
    isGranted: Boolean,
    onRequest: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = name, style = MaterialTheme.typography.bodyLarge)
        if (isGranted) {
            Text(text = "Granted", color = Color.Green, style = MaterialTheme.typography.bodyMedium)
        } else {
            Button(onClick = onRequest) {
                Text("Grant")
            }
        }
    }
}
