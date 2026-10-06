package com.purerec.ui.screens

import android.util.Size
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.purerec.core.AutoStopOption
import com.purerec.core.BitrateLevel
import com.purerec.core.CountdownOption
import com.purerec.core.DisplayHelper
import com.purerec.core.FrameRate
import com.purerec.core.RecPrefs
import com.purerec.core.RecordingConfig
import com.purerec.core.RecordingService
import com.purerec.core.RecordingState
import com.purerec.core.ResolutionPreset
import com.purerec.core.ShakeLevel
import com.purerec.core.VideoUtils
import com.purerec.ui.components.Divider
import com.purerec.ui.components.HintText
import com.purerec.ui.components.OptionChips
import com.purerec.ui.components.SectionCard
import com.purerec.ui.components.SettingSwitch
import com.purerec.ui.theme.IndigoBright
import com.purerec.ui.theme.Mint
import com.purerec.ui.theme.RecordRed
import com.purerec.ui.theme.SurfaceDark
import com.purerec.ui.theme.SurfaceElevated
import com.purerec.ui.theme.SurfaceStroke
import com.purerec.ui.theme.TextPrimary
import com.purerec.ui.theme.TextSecondary
import com.purerec.ui.theme.TextTertiary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    config: RecordingConfig,
    onConfigChange: (RecordingConfig) -> Unit,
    onStart: () -> Unit,
    onOpenLibrary: () -> Unit,
    starting: Boolean,
) {
    val context = LocalContext.current
    val nativeSize = remember { DisplayHelper.realSize(context) }

    val actualSize by produceState<Size?>(initialValue = null, config.preset) {
        value = withContext(Dispatchers.Default) {
            VideoUtils.targetSize(config.preset, nativeSize.first, nativeSize.second)
        }
    }

    val recording = RecordingState.isRecording
    val paused = RecordingState.isPaused

    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recording, paused) {
        while (recording) {
            elapsed = RecordingState.elapsedMillis()
            delay(500)
        }
        if (!recording) elapsed = 0L
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Header()
        Spacer(Modifier.height(18.dp))

        if (recording) {
            RecordingBanner(
                elapsed = elapsed,
                path = RecordingState.currentFile,
                paused = paused,
                onStop = { RecordingService.stop(context) },
                onTogglePause = { RecordingService.togglePause(context) }
            )
            Spacer(Modifier.height(18.dp))
        }

        SectionCard(title = "分辨率", icon = Icons.Rounded.HighQuality) {
            OptionChips(
                options = ResolutionPreset.entries.map { it.label },
                selectedIndex = ResolutionPreset.entries.indexOf(config.preset),
                onSelect = { onConfigChange(config.copy(preset = ResolutionPreset.entries[it])) }
            )
            Spacer(Modifier.height(11.dp))
            Text(
                text = buildString {
                    append("实际输出 ")
                    append(
                        actualSize?.let { "${it.width}×${it.height}" }
                            ?: "${nativeSize.first}×${nativeSize.second}"
                    )
                    append("  ·  屏幕 ")
                    append("${nativeSize.first}×${nativeSize.second}")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary
            )
        }
        Spacer(Modifier.height(18.dp))

        SectionCard(title = "帧率与画质", icon = Icons.Rounded.Speed) {
            Text("帧率", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            Spacer(Modifier.height(9.dp))
            OptionChips(
                options = FrameRate.entries.map { "${it.label} fps" },
                selectedIndex = FrameRate.entries.indexOf(FrameRate.fromValue(config.fps)),
                onSelect = { onConfigChange(config.copy(fps = FrameRate.entries[it].value)) }
            )
            Spacer(Modifier.height(15.dp))
            Divider()
            Spacer(Modifier.height(15.dp))
            Text("视频码率", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            Spacer(Modifier.height(9.dp))
            OptionChips(
                options = listOf("自动", "省空间", "高画质"),
                selectedIndex = BitrateLevel.entries.indexOf(config.bitrateLevel),
                onSelect = {
                    onConfigChange(config.copy(bitrateLevel = BitrateLevel.entries[it]))
                }
            )
        }
        Spacer(Modifier.height(18.dp))

        SectionCard(title = "声音", icon = Icons.Rounded.GraphicEq) {
            SettingSwitch(
                title = "麦克风",
                subtitle = "录制你的解说、环境音",
                checked = config.recordMic,
                onCheckedChange = { onConfigChange(config.copy(recordMic = it)) }
            )
            Spacer(Modifier.height(4.dp))
            SettingSwitch(
                title = "系统内部声音",
                subtitle = if (config.internalAudioSupported) {
                    "录制游戏、视频、音乐等应用发出的声音"
                } else {
                    "需要 Android 10 及以上系统"
                },
                checked = config.recordInternal,
                enabled = config.internalAudioSupported,
                onCheckedChange = { onConfigChange(config.copy(recordInternal = it)) }
            )
            Spacer(Modifier.height(4.dp))
            SettingSwitch(
                title = "麦克风增强",
                subtitle = "自动增益 + 降噪，人声更清楚、底噪更低",
                checked = config.micEnhance,
                enabled = config.recordMic,
                onCheckedChange = { onConfigChange(config.copy(micEnhance = it)) }
            )
            if (config.recordMic && config.recordInternal) {
                Spacer(Modifier.height(10.dp))
                HintText("两条音轨会自动混合成一条，不会出现两条声音打架。")
            }
        }
        Spacer(Modifier.height(18.dp))

        SectionCard(title = "省心录制", icon = Icons.Rounded.Timer) {
            Text("开录前倒计时", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            Spacer(Modifier.height(9.dp))
            OptionChips(
                options = CountdownOption.entries.map { it.label },
                selectedIndex = CountdownOption.entries.indexOf(
                    CountdownOption.fromSeconds(config.countdown)
                ),
                onSelect = {
                    onConfigChange(config.copy(countdown = CountdownOption.entries[it].seconds))
                }
            )
            Spacer(Modifier.height(15.dp))
            Divider()
            Spacer(Modifier.height(15.dp))
            Text("到点自动停", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            Spacer(Modifier.height(9.dp))
            OptionChips(
                options = AutoStopOption.entries.map { it.label },
                selectedIndex = AutoStopOption.entries.indexOf(
                    AutoStopOption.fromMinutes(config.autoStopMinutes)
                ),
                onSelect = {
                    onConfigChange(
                        config.copy(autoStopMinutes = AutoStopOption.entries[it].minutes)
                    )
                }
            )
            Spacer(Modifier.height(15.dp))
            Divider()
            Spacer(Modifier.height(11.dp))
            SettingSwitch(
                title = "摇一摇停止",
                subtitle = "录的时候不想切回 App，晃一下手机就收工",
                checked = config.shakeToStop,
                onCheckedChange = { onConfigChange(config.copy(shakeToStop = it)) }
            )
            if (config.shakeToStop) {
                Spacer(Modifier.height(4.dp))
                Text("灵敏度", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                Spacer(Modifier.height(9.dp))
                OptionChips(
                    options = ShakeLevel.entries.map { it.label },
                    selectedIndex = ShakeLevel.entries.indexOf(
                        ShakeLevel.fromId(config.shakeLevel)
                    ),
                    onSelect = { onConfigChange(config.copy(shakeLevel = ShakeLevel.entries[it].id)) }
                )
            }
            Spacer(Modifier.height(11.dp))
            SettingSwitch(
                title = "低电量自动停止",
                subtitle = "电量掉到 ${config.lowBatteryThreshold}% 就自动保存收工，充电时不触发",
                checked = config.lowBatteryStop,
                onCheckedChange = { onConfigChange(config.copy(lowBatteryStop = it)) }
            )
            Spacer(Modifier.height(4.dp))
            SettingSwitch(
                title = "录制中保持屏幕常亮",
                subtitle = "免得录到一半屏幕自己黑了",
                checked = config.keepScreenOn,
                onCheckedChange = { onConfigChange(config.copy(keepScreenOn = it)) }
            )
        }
        Spacer(Modifier.height(18.dp))

        SectionCard(title = "画面叠加", icon = Icons.Rounded.Videocam) {
            SettingSwitch(
                title = "前置摄像头画中画",
                subtitle = "录制时在右下角叠一个小窗，可选择前后摄像头",
                checked = config.useCamera,
                onCheckedChange = { onConfigChange(config.copy(useCamera = it)) }
            )
            if (config.useCamera) {
                Spacer(Modifier.height(10.dp))
                OptionChips(
                    options = listOf("前置", "后置"),
                    selectedIndex = if (config.cameraFront) 0 else 1,
                    onSelect = { onConfigChange(config.copy(cameraFront = it == 0)) }
                )
            }
        }
        Spacer(Modifier.height(18.dp))

        SectionCard(title = "保存与命名", icon = Icons.Rounded.Folder) {
            var prefixDraft by remember { androidx.compose.runtime.mutableStateOf(config.fileNamePrefix) }
            LaunchedEffect(config.fileNamePrefix) {
                if (prefixDraft != config.fileNamePrefix) prefixDraft = config.fileNamePrefix
            }

            Text("文件名前缀", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            Spacer(Modifier.height(9.dp))
            OutlinedTextField(
                value = prefixDraft,
                onValueChange = { raw ->
                    val clean = raw.take(24)
                    prefixDraft = clean
                    onConfigChange(config.copy(fileNamePrefix = RecPrefs.sanitizePrefix(clean)))
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = IndigoBright,
                    unfocusedBorderColor = SurfaceStroke,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = IndigoBright,
                    focusedContainerColor = SurfaceElevated,
                    unfocusedContainerColor = SurfaceElevated
                ),
                trailingIcon = {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(17.dp)
                    )
                }
            )
            Spacer(Modifier.height(7.dp))
            Text(
                text = "成品形如：${RecPrefs.sanitizePrefix(config.fileNamePrefix)}_20260101_120000.mp4",
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary
            )

            Spacer(Modifier.height(15.dp))
            Divider()
            Spacer(Modifier.height(11.dp))

            SettingSwitch(
                title = "录完自动存进相册",
                subtitle = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    "视频会同时出现在系统相册的 PureRec 相册里"
                } else {
                    "需要 Android 10 及以上系统"
                },
                checked = config.autoExportToGallery,
                enabled = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q,
                onCheckedChange = { onConfigChange(config.copy(autoExportToGallery = it)) }
            )

            Spacer(Modifier.height(11.dp))
            Divider()
            Spacer(Modifier.height(15.dp))

            Text(
                text = "Android/data/com.purerec/files/Movies/PureRec",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(Modifier.height(6.dp))
            HintText("录好的视频可以在「视频库」里播放、重命名、分享，或手动导出到系统相册。卸载应用会一起清掉，重要素材记得先导出。")
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceElevated)
                    .clickable { onOpenLibrary() }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "打开视频库",
                    style = MaterialTheme.typography.labelLarge,
                    color = IndigoBright
                )
            }
        }
        Spacer(Modifier.height(18.dp))

        SectionCard(title = "随手开录", icon = Icons.Rounded.Tune) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Mint,
                    modifier = Modifier.size(19.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "下拉快捷磁贴",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "把 PureRec 加到下拉控制中心，随手一点就开录；正在录的时候点它直接停。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Pause,
                    contentDescription = null,
                    tint = IndigoBright,
                    modifier = Modifier.size(19.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "录制中随时暂停",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "暂停后画面停住、声音静音，恢复后接着录，出来还是一个文件，不会断成两段。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.LightMode,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(19.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "全部功能都在本机跑，不联网、不上传。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextTertiary
                )
            }
        }

        Spacer(Modifier.height(30.dp))
        RecordButton(
            recording = recording,
            starting = starting,
            onClick = {
                if (recording) RecordingService.stop(context) else onStart()
            }
        )
        Spacer(Modifier.height(34.dp))
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(
                    Brush.linearGradient(listOf(IndigoBright, Mint))
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Videocam,
                contentDescription = null,
                tint = SurfaceDark,
                modifier = Modifier.size(25.dp)
            )
        }
        Spacer(Modifier.width(13.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "PureRec",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Mint.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, Mint.copy(alpha = 0.4f))
                ) {
                    Text(
                        "开源免费",
                        style = MaterialTheme.typography.labelMedium,
                        color = Mint,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                "本地录屏 · 无广告 · 不联网 · 不上传",
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary
            )
        }
    }
}

@Composable
private fun RecordingBanner(
    elapsed: Long,
    path: String?,
    paused: Boolean,
    onStop: () -> Unit,
    onTogglePause: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val accent = if (paused) IndigoBright else RecordRed

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = accent.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .alpha(if (paused) 1f else pulse)
                        .clip(RoundedCornerShape(6.dp))
                        .background(accent)
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    if (paused) "已暂停" else "正在录制",
                    style = MaterialTheme.typography.titleMedium,
                    color = accent
                )
                Spacer(Modifier.weight(1f))
                Text(
                    RecordingService.formatDuration(elapsed),
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (paused) {
                    "画面和声音都停住了，点「继续」接着录——出来还是同一个文件。"
                } else {
                    "从状态栏通知里也能暂停或停止。录制期间可以正常切应用、锁屏。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            if (path != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = path.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextTertiary
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(SurfaceElevated)
                        .clickable { onTogglePause() }
                        .padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        contentDescription = null,
                        tint = IndigoBright,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        if (paused) "继续" else "暂停",
                        style = MaterialTheme.typography.titleMedium,
                        color = IndigoBright
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(RecordRed)
                        .clickable { onStop() }
                        .padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Stop,
                        contentDescription = null,
                        tint = TextPrimary,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "停止并保存",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordButton(recording: Boolean, starting: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "ring")
    val ringScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringScale"
    )

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .alpha(if (recording) ringScale * 0.28f else 0f)
                .clip(RoundedCornerShape(56.dp))
                .background(RecordRed)
        )
        Box(
            modifier = Modifier
                .size(92.dp)
                .clip(RoundedCornerShape(46.dp))
                .background(
                    Brush.linearGradient(
                        if (recording) {
                            listOf(RecordRed, RecordRed)
                        } else {
                            listOf(IndigoBright, Mint)
                        }
                    )
                )
                .clickable(enabled = !starting) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            when {
                starting -> CircularProgressIndicator(
                    modifier = Modifier.size(30.dp),
                    color = SurfaceDark,
                    strokeWidth = 3.dp
                )

                recording -> Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(TextPrimary)
                )

                else -> Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .background(SurfaceDark)
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = when {
                starting -> "正在启动…"
                recording -> "点击停止录制"
                else -> "点击开始录制"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TextTertiary
        )
    }
}
