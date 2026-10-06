package com.purerec

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.purerec.core.RecPrefs
import com.purerec.core.RecordingConfig
import com.purerec.core.RecordingService
import com.purerec.core.RecordingState
import com.purerec.ui.screens.AboutScreen
import com.purerec.ui.screens.HomeScreen
import com.purerec.ui.screens.LibraryScreen
import com.purerec.ui.screens.PlayerScreen
import com.purerec.ui.theme.DeepNight
import com.purerec.ui.theme.IndigoBright
import com.purerec.ui.theme.Mint
import com.purerec.ui.theme.PureRecTheme
import com.purerec.ui.theme.SurfaceDark
import com.purerec.ui.theme.TextPrimary
import com.purerec.ui.theme.TextTertiary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        /** 下拉栏快捷磁贴点进来时带这个 action，落地页直接开始录制流程。 */
        const val ACTION_QUICK_START = "com.purerec.action.QUICK_START"
    }

    private var pendingConfig: RecordingConfig? = null

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val config = pendingConfig
        pendingConfig = null
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null && config != null) {
            try {
                RecordingService.start(this, result.resultCode, data, config)
            } catch (t: Throwable) {
                RecordingState.onError("启动录制服务失败：${t.message ?: "未知错误"}")
            }
        } else {
            RecordingState.onError("已取消录屏授权")
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val config = pendingConfig
        if (config == null) return@registerForActivityResult
        var updated = config
        if (config.recordMic && granted[Manifest.permission.RECORD_AUDIO] == false) {
            updated = updated.copy(recordMic = false)
        }
        if (config.useCamera && granted[Manifest.permission.CAMERA] == false) {
            updated = updated.copy(useCamera = false)
        }
        pendingConfig = updated
        launchProjection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val quickStart = intent?.action == ACTION_QUICK_START
        setContent {
            PureRecTheme {
                AppRoot(
                    onStartRecording = ::startRecordingFlow,
                    quickStart = quickStart,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 从快捷磁贴再次点进来时，直接重新走一遍录制流程
        if (intent.action == ACTION_QUICK_START) {
            pendingConfig = null
            startRecordingFlow(RecPrefs.load(this))
        }
    }

    private fun startRecordingFlow(config: RecordingConfig) {
        pendingConfig = config

        val needed = mutableListOf<String>()
        if (config.recordMic) needed += Manifest.permission.RECORD_AUDIO
        if (config.useCamera) needed += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            launchProjection()
        }
    }

    private fun launchProjection() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        } catch (t: Throwable) {
            RecordingState.onError("无法拉起录屏授权：${t.message ?: "未知错误"}")
        }
    }
}

@Composable
private fun AppRoot(
    onStartRecording: (RecordingConfig) -> Unit,
    quickStart: Boolean = false,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var config by remember { mutableStateOf(RecPrefs.load(context)) }
    var tab by remember { mutableIntStateOf(0) }
    var playerFile by remember { mutableStateOf<File?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var starting by remember { mutableStateOf(false) }

    /** 倒计时剩余秒数，> 0 时显示全屏计数板。 */
    var countdown by remember { mutableIntStateOf(0) }
    /** 倒计时结束后要用的配置。 */
    var pendingStart by remember { mutableStateOf<RecordingConfig?>(null) }
    /** 磁贴进来的自动开录只做一次。 */
    var quickStartHandled by remember { mutableStateOf(false) }

    LaunchedEffect(config) {
        RecPrefs.save(context, config)
    }

    // 录制中保持屏幕常亮
    DisposableEffect(RecordingState.isRecording, config.keepScreenOn) {
        view.keepScreenOn = RecordingState.isRecording && config.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(RecordingState.isRecording) {
        if (RecordingState.isRecording) starting = false
    }

    LaunchedEffect(RecordingState.lastSaved) {
        val saved = RecordingState.lastSaved
        if (saved != null) {
            starting = false
            snackbarHostState.showSnackbar("已保存：${saved.substringAfterLast('/')}")
            RecordingState.consumeSaved()
            refreshKey++
            tab = 1
        }
    }

    LaunchedEffect(RecordingState.lastError) {
        val error = RecordingState.lastError
        if (error != null) {
            starting = false
            snackbarHostState.showSnackbar(error)
            RecordingState.consumeError()
        }
    }

    LaunchedEffect(RecordingState.lastAutoStopReason) {
        val reason = RecordingState.lastAutoStopReason
        if (reason != null) {
            snackbarHostState.showSnackbar(reason)
            RecordingState.consumeAutoStopReason()
        }
    }

    LaunchedEffect(starting) {
        if (starting) {
            delay(20_000)
            starting = false
        }
    }

    // 倒计时滴答
    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown -= 1
        } else {
            val ready = pendingStart
            if (ready != null && countdown == 0) {
                pendingStart = null
                starting = true
                onStartRecording(ready)
            }
        }
    }

    // 从快捷磁贴点进来：倒计时 → 授权 → 开录
    LaunchedEffect(quickStart, config) {
        if (quickStart && !quickStartHandled && !RecordingState.isRecording) {
            quickStartHandled = true
            if (config.countdown > 0) {
                pendingStart = config
                countdown = config.countdown
            } else {
                starting = true
                onStartRecording(config)
            }
        }
    }

    val showMessage: (String) -> Unit = { message ->
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(DeepNight)
    ) {
        val playingFile = playerFile
        Scaffold(
            containerColor = DeepNight,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (playingFile == null) {
                    NavigationBar(containerColor = SurfaceDark) {
                        NavigationBarItem(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                            label = { Text("录制") },
                            colors = navigationColors()
                        )
                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            icon = { Icon(Icons.Rounded.VideoLibrary, contentDescription = null) },
                            label = { Text("视频库") },
                            colors = navigationColors()
                        )
                        NavigationBarItem(
                            selected = tab == 2,
                            onClick = { tab = 2 },
                            icon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                            label = { Text("关于") },
                            colors = navigationColors()
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when {
                    playingFile != null -> PlayerScreen(
                        file = playingFile,
                        onBack = { playerFile = null },
                        onMessage = showMessage
                    )

                    tab == 0 -> HomeScreen(
                        config = config,
                        onConfigChange = { config = it },
                        onStart = {
                            if (config.countdown > 0) {
                                pendingStart = config
                                countdown = config.countdown
                            } else {
                                starting = true
                                onStartRecording(config)
                            }
                        },
                        onOpenLibrary = { tab = 1 },
                        starting = starting
                    )

                    tab == 1 -> LibraryScreen(
                        refreshKey = refreshKey,
                        onPlay = { playerFile = it },
                        onMessage = showMessage
                    )

                    else -> AboutScreen(onMessage = showMessage)
                }
            }
        }

        if (countdown > 0) {
            CountdownOverlay(
                seconds = countdown,
                onCancel = {
                    pendingStart = null
                    countdown = 0
                    starting = false
                }
            )
        }
    }
}

/** 录制前的全屏倒数板：点一下就能取消。 */
@Composable
private fun CountdownOverlay(seconds: Int, onCancel: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.93f))
            .clickable { onCancel() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "$seconds",
                style = MaterialTheme.typography.displayLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 108.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "马上开始录制",
                style = MaterialTheme.typography.titleMedium,
                color = Mint
            )
            Spacer(Modifier.height(26.dp))
            Text(
                text = "点一下屏幕取消",
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun navigationColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = IndigoBright,
    selectedTextColor = IndigoBright,
    indicatorColor = IndigoBright.copy(alpha = 0.14f),
    unselectedIconColor = TextTertiary,
    unselectedTextColor = TextTertiary
)
