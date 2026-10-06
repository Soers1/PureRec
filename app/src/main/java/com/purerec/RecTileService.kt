package com.purerec

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import com.purerec.core.RecordingService
import com.purerec.core.RecordingState

/**
 * 下拉快捷设置里的 PureRec 磁贴。
 *
 * 没在录 → 点一下拉起录制流程（申请授权后开录）。
 * 正在录 → 点一下直接停。
 */
@RequiresApi(Build.VERSION_CODES.N)
class RecTileService : TileService() {

    companion object {
        private const val TAG = "PureRec-Tile"
    }

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        try {
            val recording = RecordingState.isRecording
            tile.state = if (recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = if (recording) "停止录制" else "PureRec"
            tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_rec)
            tile.updateTile()
        } catch (t: Throwable) {
            Log.w(TAG, "刷新磁贴失败", t)
        }
    }

    override fun onClick() {
        super.onClick()

        if (RecordingState.isRecording) {
            RecordingService.stop(this)
            return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_QUICK_START
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = PendingIntent.getActivity(
                    this,
                    0x51,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "磁贴启动失败", t)
        }
    }
}
