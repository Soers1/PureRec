package com.purerec.ui.screens

import android.content.Intent
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.purerec.ui.theme.IndigoBright
import com.purerec.ui.theme.SurfaceDark
import com.purerec.ui.theme.SurfaceElevated
import com.purerec.ui.theme.SurfaceStroke
import com.purerec.ui.theme.TextPrimary
import com.purerec.ui.theme.TextSecondary
import com.purerec.ui.theme.TextTertiary
import com.purerec.util.VideoLibrary
import java.io.File

@Composable
fun PlayerScreen(
    file: File,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val item = remember(file.absolutePath) { VideoLibrary.readMeta(file) }

    val videoView = remember {
        val view = VideoView(context)
        view.setVideoPath(file.absolutePath)
        val controller = MediaController(context)
        controller.setAnchorView(view)
        view.setMediaController(controller)
        view.setOnPreparedListener {
            it.isLooping = false
            view.start()
        }
        view.setOnErrorListener { _, _, _ ->
            onMessage("这个视频无法播放，可能文件已损坏")
            true
        }
        view
    }

    DisposableEffect(file.absolutePath) {
        onDispose {
            try {
                videoView.stopPlayback()
            } catch (_: Throwable) {
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = TextPrimary)
            }
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { videoView },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(
                        if (item.width > 0 && item.height > 0) {
                            item.width.toFloat() / item.height.toFloat()
                        } else {
                            16f / 9f
                        }
                    )
            )
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            shape = RoundedCornerShape(18.dp),
            color = SurfaceDark,
            border = BorderStroke(1.dp, SurfaceStroke)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    text = "${VideoLibrary.formatDuration(item.durationMs)} · " +
                        "${item.resolutionLabel} · ${VideoLibrary.formatSize(item.sizeBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = file.absolutePath,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionPill(
                        label = "分享",
                        icon = Icons.Rounded.Share,
                        onClick = {
                            try {
                                context.startActivity(
                                    Intent.createChooser(
                                        VideoLibrary.shareIntent(context, file),
                                        "分享视频"
                                    )
                                )
                            } catch (t: Throwable) {
                                onMessage("分享失败")
                            }
                        }
                    )
                    ActionPill(
                        label = "导出到相册",
                        icon = Icons.Rounded.Download,
                        onClick = {
                            onMessage(
                                if (VideoLibrary.exportToGallery(context, file)) {
                                    "已导出到相册 Movies/PureRec"
                                } else {
                                    "导出失败"
                                }
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceElevated)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = IndigoBright, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = TextPrimary)
    }
}
