package com.heartflow.app

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.heartflow.data.*

// ─── 现代化消息气泡 ────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMessageItem(
    message: ChatUiMessage,
    index: Int = 0,
    speakingMessageIndex: Int? = null,
    ttsPlaybackState: String = "idle",
    onEdit: ((Int) -> Unit)? = null,
    onResend: ((Int) -> Unit)? = null,
    onImageClick: ((MediaAttachment) -> Unit)? = null,
    onCancelTool: ((Int) -> Unit)? = null,
    onSpeak: ((Int) -> Unit)? = null,
    onStopSpeak: (() -> Unit)? = null,
    onPauseSpeak: (() -> Unit)? = null,
    onResumeSpeak: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showTimestamp by remember { mutableStateOf(false) }
    val primaryColor = MaterialTheme.colorScheme.primary
    val scheme = LocalThemeScheme.current

    val userBubbleShape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
    val aiBubbleShape = RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // AI 头像（左侧）
        if (!message.isUser) {
            Surface(
                modifier = Modifier.size(30.dp),
                shape = CircleShape,
                color = primaryColor.copy(alpha = 0.12f),
                tonalElevation = 0.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("虫", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = primaryColor)
                }
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier.widthIn(max = 300.dp),
            horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
        ) {
            // 情绪标签（AI 消息）
            if (message.emotion != null && !message.isUser) {
                Text(
                    message.emotion,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    modifier = Modifier.padding(bottom = 3.dp, end = 4.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.animateContentSize(animationSpec = tween(200))
            ) {
                // AI 左侧色条
                if (!message.isUser) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(40.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(primaryColor.copy(alpha = 0.7f), primaryColor.copy(alpha = 0.2f))
                                )
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                }

                // ── 气泡主体 ──
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(if (message.isUser) userBubbleShape else aiBubbleShape)
                        .then(
                            if (message.isUser)
                                Modifier.background(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(
                                            primaryColor,
                                            primaryColor.copy(
                                                red = (primaryColor.red * 0.82f).coerceIn(0f, 1f),
                                                green = (primaryColor.green * 0.82f).coerceIn(0f, 1f),
                                                blue = (primaryColor.blue * 0.82f).coerceIn(0f, 1f)
                                            )
                                        )
                                    )
                                )
                            else Modifier.background(
                                color = if (message.isError) Color(0xFFFFEBEE)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
                            )
                        )
                        .padding(horizontal = 14.dp, vertical = 11.dp)
                        .combinedClickable(
                            onClick = {
                                if (!message.isStreaming) showTimestamp = !showTimestamp
                            },
                            onLongClick = {
                                if (message.content.isNotBlank()) {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("message", message.content)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                    showMenu = true
                                }
                            }
                        )
                ) {
                    Column {
                        // ── 图片附件 ──
                        if (message.attachment != null && message.attachment.type == "image") {
                            val att = message.attachment
                            val imageUrl = if (att.base64Data != null)
                                "data:${att.mimeType};base64,${att.base64Data}"
                            else att.uri
                            AsyncImage(
                                model = imageUrl,
                                contentDescription = att.fileName,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onImageClick?.invoke(att) }
                            )
                            Spacer(Modifier.height(10.dp))
                        }

                        // ── 文件附件 ──
                        if (message.attachment != null && message.attachment.type == "file") {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = if (message.isUser) Color.White.copy(alpha = 0.15f)
                                        else scheme.surfaceVariant.copy(alpha = 0.6f),
                                tonalElevation = 0.dp
                            ) {
                                Row(
                                    Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.AttachFile, null,
                                        tint = if (message.isUser) Color.White.copy(alpha = 0.7f) else Color.Gray,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            message.attachment.fileName,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (message.isUser) Color.White else Color.Unspecified
                                        )
                                        Text(
                                            message.attachment.mimeType,
                                            fontSize = 10.sp,
                                            color = if (message.isUser) Color.White.copy(alpha = 0.55f) else Color.Gray
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }

                        // ── 工具调用折叠区 ──
                        if (message.toolCalls.isNotEmpty()) {
                            var expanded by remember { mutableStateOf(false) }
                            val runningCount = message.toolCalls.count { it.status == "running" }
                            val successCount = message.toolCalls.count { it.status == "success" }
                            val errorCount = message.toolCalls.count { it.status == "error" }

                            Surface(
                                onClick = { expanded = !expanded },
                                shape = RoundedCornerShape(10.dp),
                                color = if (message.isUser) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.04f),
                                tonalElevation = 0.dp,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (runningCount > 0) "⚡ 工具执行中 ($runningCount)"
                                            else "🔧 工具调用 ($successCount✓ $errorCount✗)",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (message.isUser) Color.White.copy(alpha = 0.8f) else Color(0xFF555555)
                                        )
                                        Spacer(Modifier.weight(1f))
                                        Icon(
                                            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                            null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (message.isUser) Color.White.copy(alpha = 0.6f) else Color.Gray
                                        )
                                    }
                                    AnimatedVisibility(visible = expanded) {
                                        Column(Modifier.padding(top = 6.dp)) {
                                            message.toolCalls.forEachIndexed { idx, tc ->
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(vertical = 3.dp)
                                                ) {
                                                    when (tc.status) {
                                                        "running" -> {
                                                            CircularProgressIndicator(
                                                                modifier = Modifier.size(12.dp),
                                                                strokeWidth = 2.dp
                                                            )
                                                            Spacer(Modifier.width(6.dp))
                                                            Text(
                                                                "🔧 ${tc.name}...",
                                                                fontSize = 11.sp,
                                                                color = if (message.isUser) Color.White.copy(alpha = 0.7f) else Color(0xFF666666)
                                                            )
                                                            Spacer(Modifier.weight(1f))
                                                            IconButton(
                                                                onClick = { onCancelTool?.invoke(idx) },
                                                                modifier = Modifier.size(16.dp)
                                                            ) {
                                                                Text(
                                                                    "✕", fontSize = 12.sp,
                                                                    color = if (message.isUser) Color.White.copy(alpha = 0.5f) else Color.Gray
                                                                )
                                                            }
                                                        }
                                                        "success" -> {
                                                            Box(
                                                                modifier = Modifier
                                                                    .size(14.dp)
                                                                    .clip(CircleShape)
                                                                    .background(Color(0xFF4CAF50)),
                                                                contentAlignment = Alignment.Center
                                                            ) { Text("✓", fontSize = 9.sp, color = Color.White) }
                                                            Spacer(Modifier.width(6.dp))
                                                            Text(
                                                                "${tc.name} 完成${tc.durationMs?.let { " (${it}ms)" } ?: ""}",
                                                                fontSize = 11.sp,
                                                                color = if (message.isUser) Color(0xFF81C784) else Color(0xFF4CAF50)
                                                            )
                                                        }
                                                        "error" -> {
                                                            Text("❌", fontSize = 11.sp)
                                                            Spacer(Modifier.width(6.dp))
                                                            Text(
                                                                "${tc.name}: ${tc.result ?: "失败"}",
                                                                fontSize = 11.sp,
                                                                color = if (message.isUser) Color(0xFFEF9A9A) else Color(0xFFEF5350)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // ── 运行中指示器 ──
                        if (message.runningTool != null && message.toolCalls.any { it.status == "running" }) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 3.dp)
                            ) {
                                LinearProgressIndicator(
                                    modifier = Modifier.size(60.dp, 3.dp),
                                    color = if (message.isUser) Color.White.copy(alpha = 0.7f) else primaryColor,
                                    trackColor = if (message.isUser) Color.White.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "⚡ ${message.runningTool}...",
                                    fontSize = 11.sp,
                                    color = if (message.isUser) Color.White.copy(alpha = 0.7f) else primaryColor
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                        }

                        // ── 消息内容 ──
                        when {
                            message.isStreaming && message.content.isEmpty() -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = if (message.isUser) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "思考中...",
                                        color = if (message.isUser) Color.White.copy(alpha = 0.7f) else Color.Gray,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                            message.isStreaming && message.content.isNotEmpty() -> {
                                MarkdownText(
                                    content = message.content,
                                    isUser = message.isUser,
                                    isStreaming = true
                                )
                            }
                            message.content.contains("```") -> RichTextContent(message.content, message.isUser)
                            else -> MarkdownText(
                                content = message.content,
                                isUser = message.isUser,
                                isError = message.isError
                            )
                        }

                        // ── 语音标记 ──
                        if (message.isVoice) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Mic, null,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (message.isUser) Color.White.copy(alpha = 0.6f) else Color.Gray
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "🎤 语音消息",
                                    fontSize = 10.sp,
                                    color = if (message.isUser) Color.White.copy(alpha = 0.6f) else Color.Gray
                                )
                            }
                        }

                        // ── 时间戳 ──
                        AnimatedVisibility(visible = showTimestamp) {
                            Text(
                                "刚刚",
                                fontSize = 9.sp,
                                color = if (message.isUser) Color.White.copy(alpha = 0.45f) else Color.Gray,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }

                        // ── TTS 朗读按钮 ──
                        if (!message.isUser && !message.isLoading && !message.isStreaming && message.content.isNotBlank()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                val isSpeakingThis = speakingMessageIndex == index

                                // 暂停/恢复按钮（仅当前朗读的消息显示）
                                if (isSpeakingThis) {
                                    val isPaused = ttsPlaybackState == "paused"
                                    Surface(
                                        onClick = {
                                            if (isPaused) onResumeSpeak?.invoke()
                                            else onPauseSpeak?.invoke()
                                        },
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                        tonalElevation = 0.dp,
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                                contentDescription = if (isPaused) "恢复朗读" else "暂停朗读",
                                                modifier = Modifier.size(16.dp),
                                                tint = primaryColor.copy(alpha = 0.6f)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(6.dp))
                                }

                                // 停止/朗读按钮
                                Surface(
                                    onClick = {
                                        if (isSpeakingThis) onStopSpeak?.invoke()
                                        else onSpeak?.invoke(index)
                                    },
                                    shape = CircleShape,
                                    color = if (isSpeakingThis)
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                                    else
                                        primaryColor.copy(alpha = 0.08f),
                                    tonalElevation = 0.dp,
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (isSpeakingThis) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                                            contentDescription = if (isSpeakingThis) "停止朗读" else "朗读",
                                            modifier = Modifier.size(16.dp),
                                            tint = if (isSpeakingThis)
                                                MaterialTheme.colorScheme.error
                                            else
                                                primaryColor.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── 工具结果 ──
            if (message.toolResults.isNotEmpty()) {
                Column(Modifier.padding(top = 6.dp)) {
                    message.toolResults.forEach { result ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = if (message.isUser) Color.White.copy(alpha = 0.08f) else Color(0xFFF5F5F5),
                            tonalElevation = 0.dp
                        ) {
                            Text(
                                result,
                                modifier = Modifier.padding(10.dp),
                                fontSize = 11.sp,
                                color = if (message.isUser) Color.White.copy(alpha = 0.7f) else Color(0xFF666666)
                            )
                        }
                    }
                }
            }

            // ── 右键菜单 ──
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                if (message.isUser) {
                    DropdownMenuItem(
                        text = { Text("编辑消息") },
                        onClick = { showMenu = false; onEdit?.invoke(0) },
                        leadingIcon = { Icon(Icons.Default.Edit, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("重新发送") },
                        onClick = { showMenu = false; onResend?.invoke(0) },
                        leadingIcon = { Icon(Icons.Default.Refresh, null) }
                    )
                } else if (!message.isStreaming && message.content.isNotBlank()) {
                    DropdownMenuItem(
                        text = { Text("重新生成") },
                        onClick = { showMenu = false; onResend?.invoke(0) },
                        leadingIcon = { Icon(Icons.Default.Refresh, null) }
                    )
                }
            }
        }

        // 用户头像（右侧）
        if (message.isUser) {
            Spacer(Modifier.width(8.dp))
            Surface(
                modifier = Modifier.size(30.dp),
                shape = CircleShape,
                color = primaryColor.copy(alpha = 0.12f),
                tonalElevation = 0.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("我", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = primaryColor)
                }
            }
        }
    }
}
