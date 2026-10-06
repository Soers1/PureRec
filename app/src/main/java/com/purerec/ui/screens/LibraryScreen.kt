package com.purerec.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.purerec.ui.theme.IndigoBright
import com.purerec.ui.theme.Mint
import com.purerec.ui.theme.RecordRed
import com.purerec.ui.theme.SurfaceDark
import com.purerec.ui.theme.SurfaceElevated
import com.purerec.ui.theme.SurfaceStroke
import com.purerec.ui.theme.TextPrimary
import com.purerec.ui.theme.TextSecondary
import com.purerec.ui.theme.TextTertiary
import com.purerec.util.VideoItem
import com.purerec.util.VideoLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun LibraryScreen(
    refreshKey: Int,
    onPlay: (File) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    var localRefresh by remember { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf<VideoItem?>(null) }
    var deleting by remember { mutableStateOf<VideoItem?>(null) }
    var pendingExport by remember { mutableStateOf<VideoItem?>(null) }

    val items by produceState<List<VideoItem>>(emptyList(), refreshKey, localRefresh) {
        value = withContext(Dispatchers.IO) { VideoLibrary.list(context) }
    }

    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val item = pendingExport
        pendingExport = null
        if (item == null) return@rememberLauncherForActivityResult
        if (!granted) {
            onMessage("没有存储权限，无法导出到相册")
            return@rememberLauncherForActivityResult
        }
        export(context, item, onMessage)
    }

    fun requestExport(item: VideoItem) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingExport = item
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            export(context, item, onMessage)
        }
    }

    val totalSize = items.sumOf { it.sizeBytes }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(8.dp))
        Column(Modifier.padding(horizontal = 18.dp)) {
            Text("视频库", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (items.isEmpty()) "还没有录制文件" else
                    "${items.size} 个视频 · 共 ${VideoLibrary.formatSize(totalSize)}",
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary
            )
        }
        Spacer(Modifier.height(14.dp))

        if (items.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 18.dp, end = 18.dp, bottom = 28.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items, key = { it.file.absolutePath }) { item ->
                    VideoRow(
                        item = item,
                        onPlay = { onPlay(item.file) },
                        onRename = { renaming = item },
                        onDelete = { deleting = item },
                        onShare = {
                            try {
                                context.startActivity(
                                    Intent.createChooser(
                                        VideoLibrary.shareIntent(context, item.file),
                                        "分享视频"
                                    )
                                )
                            } catch (t: Throwable) {
                                onMessage("分享失败：${t.message ?: "未知错误"}")
                            }
                        },
                        onExport = { requestExport(item) }
                    )
                }
            }
        }
    }

    renaming?.let { item ->
        RenameDialog(
            item = item,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                val renamed = VideoLibrary.rename(item.file, name)
                if (renamed == null) {
                    onMessage("重命名失败，可能已存在同名文件")
                } else {
                    onMessage("已重命名为 ${renamed.name}")
                    localRefresh++
                }
            }
        )
    }

    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除视频") },
            text = { Text("确定删除「${item.name}」吗？删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    if (VideoLibrary.delete(item.file)) {
                        onMessage("已删除")
                    } else {
                        onMessage("删除失败")
                    }
                    localRefresh++
                }) {
                    Text("删除", color = RecordRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            }
        )
    }
}

private fun export(context: android.content.Context, item: VideoItem, onMessage: (String) -> Unit) {
    val ok = VideoLibrary.exportToGallery(context, item.file)
    onMessage(if (ok) "已导出到相册 Movies/PureRec" else "导出失败")
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(80.dp))
        Box(
            modifier = Modifier
                .size(74.dp)
                .clip(CircleShape)
                .background(SurfaceDark),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.VideoLibrary,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text("空空如也", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
        Spacer(Modifier.height(6.dp))
        Text(
            "去首页点一下录制按钮，录完的视频会出现在这里。",
            style = MaterialTheme.typography.bodyMedium,
            color = TextTertiary
        )
    }
}

@Composable
private fun VideoRow(
    item: VideoItem,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = SurfaceDark,
        border = BorderStroke(1.dp, SurfaceStroke)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 104.dp, height = 62.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(SurfaceElevated)
                    .clickable { onPlay() },
                contentAlignment = Alignment.Center
            ) {
                Thumbnail(item.file)
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(SurfaceDark.copy(alpha = 0.72f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${VideoLibrary.formatDuration(item.durationMs)} · " +
                        "${VideoLibrary.formatSize(item.sizeBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${item.resolutionLabel} · ${VideoLibrary.formatDate(item.lastModified)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextTertiary
                )
            }

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "更多",
                        tint = TextTertiary
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = { menuOpen = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text("分享") },
                        leadingIcon = { Icon(Icons.Rounded.Share, null) },
                        onClick = { menuOpen = false; onShare() }
                    )
                    DropdownMenuItem(
                        text = { Text("导出到相册") },
                        leadingIcon = { Icon(Icons.Rounded.Download, null) },
                        onClick = { menuOpen = false; onExport() }
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = RecordRed) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = RecordRed) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(file: File) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            VideoLibrary.thumbnail(file)?.asImageBitmap()
        }
    }
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Icon(
            Icons.Rounded.Movie,
            contentDescription = null,
            tint = TextTertiary,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun RenameDialog(
    item: VideoItem,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember {
        mutableStateOf(item.name.removeSuffix(".mp4"))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("文件名") },
                suffix = { Text(".mp4", color = TextTertiary) }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text("保存", color = IndigoBright, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextTertiary)
            }
        }
    )
}
