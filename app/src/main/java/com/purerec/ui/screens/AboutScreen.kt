package com.purerec.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.purerec.BuildConfig
import com.purerec.ui.theme.Amber
import com.purerec.ui.theme.DeepNight
import com.purerec.ui.theme.IndigoBright
import com.purerec.ui.theme.Mint
import com.purerec.ui.theme.SurfaceDark
import com.purerec.ui.theme.SurfaceStroke
import com.purerec.ui.theme.TextPrimary
import com.purerec.ui.theme.TextSecondary
import com.purerec.ui.theme.TextTertiary

/**
 * 关于作者页。
 *
 * 内容全部是纯静态文本，不联网、不读取任何设备信息。
 */
@Composable
fun AboutScreen(onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scroll = rememberScrollState()

    val copyToClipboard: (String) -> Unit = { value ->
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.setPrimaryClip(ClipData.newPlainText("PureRec", value))
        onMessage("已复制：$value")
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(DeepNight)
            .verticalScroll(scroll)
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(22.dp))

        AuthorHeader()

        Spacer(Modifier.height(18.dp))

        SectionCard(title = "关于我") {
            Text(
                text = AUTHOR_BIO,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                lineHeight = MaterialTheme.typography.bodyMedium.fontSize * 1.75f
            )
        }

        Spacer(Modifier.height(14.dp))

        SectionCard(title = "联系方式") {
            ContactRow(
                label = "微信",
                value = "16653653008",
                note = "暂时无法添加",
                enabled = false,
                onCopy = copyToClipboard
            )
            Spacer(Modifier.height(10.dp))
            ContactRow(
                label = "QQ",
                value = "2803790685",
                note = null,
                enabled = true,
                onCopy = copyToClipboard
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "其他联系方式以后再说。",
                style = MaterialTheme.typography.labelMedium,
                color = TextTertiary
            )
        }

        Spacer(Modifier.height(14.dp))

        SectionCard(title = "关于 PureRec") {
            InfoLine("版本", BuildConfig.VERSION_NAME)
            Spacer(Modifier.height(7.dp))
            InfoLine("许可证", "MIT License · 开源免费")
            Spacer(Modifier.height(7.dp))
            InfoLine("隐私", "不联网 · 不上传 · 不留存数据")
            Spacer(Modifier.height(12.dp))
            Text(
                text = "写这个软件没别的目的，就是不想再被广告和会员墙烦。代码全在仓库里，"
                    + "随便看、随便改、随便传。",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                lineHeight = MaterialTheme.typography.bodySmall.fontSize * 1.7f
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = "谢谢每一个愿意用它的人。",
            style = MaterialTheme.typography.labelLarge,
            color = TextTertiary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun AuthorHeader() {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = SurfaceDark,
        border = BorderStroke(1.dp, SurfaceStroke),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(18.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(IndigoBright, Mint))),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "辛",
                    style = MaterialTheme.typography.headlineMedium,
                    color = DeepNight,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(15.dp))
            Column {
                Text(
                    text = "辛同学 · 高三学生",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(5.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Pill(text = "独立开发", color = IndigoBright)
                    Pill(text = "业余写的", color = Mint)
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.38f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = SurfaceDark,
        border = BorderStroke(1.dp, SurfaceStroke),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            modifier = Modifier.width(64.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun ContactRow(
    label: String,
    value: String,
    note: String?,
    enabled: Boolean,
    onCopy: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (enabled) IndigoBright.copy(alpha = 0.07f) else SurfaceDark,
        border = BorderStroke(
            1.dp,
            if (enabled) IndigoBright.copy(alpha = 0.22f) else SurfaceStroke
        ),
        onClick = { if (enabled) onCopy(value) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextTertiary
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (enabled) TextPrimary else TextSecondary,
                    fontWeight = FontWeight.Medium
                )
                if (note != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelSmall,
                        color = Amber
                    )
                }
            }
            if (enabled) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = "复制",
                    tint = IndigoBright,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

private const val AUTHOR_BIO = """我姓辛，高三。

说不清具体是从哪天开始的。大概是某次晚自习结束，回家瘫在床上刷手机，忽然发现自己连点开一个 App 的力气都没有。

高三这一年，日子过得又快又黏——试卷、排名、倒计时，还有医院给的那张单子，上面印着"抑郁症"三个字。我倒没打算拿它当借口，课照上，题照做，日子照过。只是总有那么些时候，书翻不进去，觉也睡不着，手边总得有点别的事做。

PureRec 就是从那种"闲"里长出来的。闲得发慌，又不想让自己一直空着，就找点正经事干——干脆自己写一个。

想录点东西，商店里翻了一圈：要么广告弹到你手抖，要么录三分钟就提示你"升级会员解锁时长"。烦了，那就自己写。从啃文档到第一个能出画面的版本，磨了两个多月，全是挤出来的零碎时间。

代码谈不上漂亮，但每一行我都知道它在干什么——不联网、不上传、不留存。因为我自己也讨厌那套东西。

如果它能帮你留下一点想留下的东西，那我这两个月就值了。

明天还得早起，先写到这儿吧。"""
