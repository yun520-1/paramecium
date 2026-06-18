package com.heartflow.app

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.heartflow.app.LocalThemeScheme
import com.heartflow.data.MediaAttachment

/**
 * 全屏图片查看器
 *
 * 显示大图预览，支持：
 * - fadeIn/fadeOut 进入退出动画
 * - 点击背景或关闭按钮关闭
 * - 点击图片区域不关闭
 * - 底部信息栏显示文件名和类型
 */
@Composable
fun ImageViewer(attachment: MediaAttachment, onDismiss: () -> Unit) {
    val scheme = LocalThemeScheme.current
    var show by remember { mutableStateOf(true) }

    // 退出动画完成后调用 onDismiss
    LaunchedEffect(show) {
        if (!show) {
            kotlinx.coroutines.delay(200) // 匹配 exit fadeOut 时长
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = show,
        enter = fadeIn(animationSpec = tween(300)),
        exit = fadeOut(animationSpec = tween(200)),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scheme.scrim)
                .clickable(onClick = { show = false }),
            contentAlignment = Alignment.Center
        ) {
            // 主图片 — 点击时不关闭，消费事件防止传递到背景
            AsyncImage(
                model = if (attachment.base64Data != null)
                    "data:${attachment.mimeType};base64,${attachment.base64Data}"
                else attachment.uri,
                contentDescription = attachment.fileName,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { /* 消费点击事件，阻止关闭 */ }
                    )
            )

            // 关闭按钮 — Surface(CircleShape) 替代 raw Box.background()
            Surface(
                onClick = { show = false },
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.5f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = Color.White,
                    modifier = Modifier.padding(8.dp)
                )
            }

            // 底部信息栏 — 文件名 + MIME 类型
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.85f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { /* 消费点击事件，阻止关闭 */ }
                    )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = attachment.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
