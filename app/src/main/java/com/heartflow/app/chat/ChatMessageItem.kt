package com.heartflow.app

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.heartflow.data.*

// ─── 消息气泡（重设计 v2.2.0） ──────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMessageItem(
    message: ChatUiMessage,
    index: Int = 0,
    speakingMessageIndex: Int? = null,
    onEdit: ((Int) -> Unit)? = null,
    onResend: ((Int) -> Unit)? = null,
    onImageClick: ((MediaAttachment) -> Unit)? = null,
    onCancelTool: ((Int) -> Unit)? = null,
    onSpeak: ((Int) -> Unit)? = null,
    onStopSpeak: (() -> Unit)? = null
) {
    val isCodeContent = message.content.contains("```")
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showTimestamp by remember { mutableStateOf(false) }
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    val userAvatarLetter = "我"
    val aiAvatarLetter = "虫"

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!message.isUser) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(primaryColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(aiAvatarLetter, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = primaryColor)
            }
            Spacer(Modifier.width(6.dp))
        }

        Column(
            Modifier.widthIn(max = 300.dp),
            horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
        ) {
            if (message.emotion != null && !message.isUser) {
                Text(
                    message.emotion,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(bottom = 2.dp, end = 4.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.animateContentSize()
            ) {
                if (!message.isUser) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(36.dp)
                            .padding(top = 0.dp)
                            .background(primaryColor.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.width(4.dp))
                }

                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .shadow(
                            elevation = 2.dp,
                            shape = if (message.isUser)
                                RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
                            else
                                RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
                            ambientColor = if (message.isUser) primaryColor.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f),
                            spotColor = if (message.isUser) primaryColor.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
                        )
                        .clip(
                            if (message.isUser)
                                RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
                            else
                                RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)
                        )
                        .then(
                            if (message.isUser) Modifier.background(
                                brush = Brush.horizontalGradient(listOf(
                                    primaryColor,
                                    primaryColor.copy(red = (primaryColor.red * 0.85f).coerceIn(0f, 1f),
                                        green = (primaryColor.green * 0.85f).coerceIn(0f, 1f),
                                        blue = (primaryColor.blue * 0.85f).coerceIn(0f, 1f))
                                ))
                            ) else Modifier.background(
                                color = if (message.isError) Color(0xFFFFEBEE)
                                        else surfaceVariant
                            )
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .combinedClickable(
                            onClick = { if (message.isStreaming) return@combinedClickable; showTimestamp = !showTimestamp },
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
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onImageClick?.invoke(att) }
                            )
                            Spacer(Modifier.height(8.dp))
                        }

                        if (message.attachment != null && message.attachment.type == "file") {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = if (message.isUser) Color.White.copy(alpha = 0.2f) else Color(0xFFF5F5F5))
                            ) {
                                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AttachFile, null, tint = if (message.isUser) Color.White.copy(alpha = 0.7f) else Color.Gray)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(message.attachment.fileName, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                            color = if (message.isUser) Color.White else Color.Unspecified)
                                        Text(message.attachment.mimeType, fontSize = 10.sp,
                                            color = if (message.isUser) Color.White.copy(alpha = 0.6f) else Color.Gray)
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        if (message.toolCalls.isNotEmpty()) {
                            var expanded by remember { mutableStateOf(false) }
                            val runningCount = message.toolCalls.count { it.status == "running" }
                            val successCount = message.toolCalls.count { it.status == "success" }
                            val errorCount = message.toolCalls.count { it.status == "error" }

                            Surface(
                                onClick = { expanded = !expanded },
                                shape = RoundedCornerShape(8.dp),
                                color = if (message.isUser) Color.White.copy(alpha = 0.15f) else Color(0xFF000000).copy(alpha = 0.04f),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                            ) {
                                Column(Modifier.padding(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (runningCount > 0) "⚡ 工具执行中 ($runningCount)"
                                            else "🔧 工具调用 ($successCount✓ $errorCount✗)",
                                            fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                            color = if (message.isUser) Color.White.copy(alpha = 0.8f) else Color(0xFF555555)
                                        )
                                        Spacer(Modifier.weight(1f))
                                        Icon(
                                            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                            null, modifier = Modifier.size(16.dp),
                                            tint = if (message.isUser) Color.White.copy(alpha = 0.6f) else Color.Gray
                                        )
                                    }
                                    AnimatedVisibility(visible = expanded) {
                                        Column(Modifier.padding(top = 4.dp)) {
                                            message.toolCalls.forEachIndexed { idx, tc ->
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                                    when (tc.status) {
                                                        "running" -> {
                                                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                                                            Spacer(Modifier.width(6.dp))
                                                            Text("🔧 ${tc.name}...", fontSize = 11.sp,
                                                                color = if (message.isUser) Color.White.copy(alpha = 0.7f) else Color(0xFF666666))
                                                            Spacer(Modifier.weight(1f))
                                                            IconButton(
                                                                onClick = { onCancelTool?.invoke(idx) },
                                                                modifier = Modifier.size(16.dp)
                                                            ) { Text("✕", fontSize = 12.sp,
                                                                color = if (message.isUser) Color.White.copy(alpha = 0.5f) else Color.Gray) }
                                                        }
                                                        "success" -> {
                                                            Text("✅", fontSize = 11.sp)
                                                            Spacer(Modifier.width(6.dp))
                                                            Text("${tc.name} 完成${tc.durationMs?.let { " (${it}ms)" } ?: ""}", fontSize = 11.sp,
                                                                color = if (message.isUser) Color(0xFF81C784) else Color(0xFF4CAF50))
                                                        }
                                                        "error" -> {
                                                            Text("❌", fontSize = 11.sp)
                                                            Spacer(Modifier.width(6.dp))
                                                            Text("${tc.name}: ${tc.result ?: "失败"}", fontSize = 11.sp,
                                                                color = if (message.isUser) Color(0xFFEF9A9A) else Color(0xFFEF5350))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (message.runningTool != null && message.toolCalls.any { it.status == "running" }) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                LinearProgressIndicator(
                                    modifier = Modifier.size(60.dp, 3.dp),
                                    color = if (message.isUser) Color.White.copy(alpha = 0.7f) else Color(0xFF1976D2),
                                    trackColor = if (message.isUser) Color.White.copy(alpha = 0.2f) else Color(0xFFBBDEFB)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("⚡ ${message.runningTool}...", fontSize = 11.sp,
                                    color = if (message.isUser) Color.White.copy(alpha = 0.7f) else Color(0xFF1976D2))
                            }
                            Spacer(Modifier.height(4.dp))
                        }

                        when {
                            message.isStreaming && message.content.isEmpty() -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                                        color = if (message.isUser) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text("思考中...",
                                        color = if (message.isUser) Color.White.copy(alpha = 0.7f) else Color.Gray,
                                        fontSize = 13.sp)
                                }
                            }
                            message.isStreaming && message.content.isNotEmpty() -> {
                                MarkdownText(
                                    content = message.content,
                                    isUser = message.isUser,
                                    isStreaming = true
                                )
                            }
                            isCodeContent -> RichTextContent(message.content, message.isUser)
                            else -> MarkdownText(
                                content = message.content,
                                isUser = message.isUser,
                                isError = message.isError
                            )
                        }

                        if (message.isVoice) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                Icon(Icons.Default.Mic, null, modifier = Modifier.size(14.dp),
                                    tint = if (message.isUser) Color.White.copy(alpha = 0.6f) else Color.Gray)
                                Spacer(Modifier.width(4.dp))
                                Text("🎤 语音消息", fontSize = 10.sp,
                                    color = if (message.isUser) Color.White.copy(alpha = 0.6f) else Color.Gray)
                            }
                        }

                        AnimatedVisibility(visible = showTimestamp) {
                            Text("刚刚", fontSize = 9.sp,
                                color = if (message.isUser) Color.White.copy(alpha = 0.5f) else Color.Gray,
                                modifier = Modifier.padding(top = 2.dp))
                        }

                        // TTS 朗读按钮：仅 AI 非流式完成消息显示
                        if (!message.isUser && !message.isLoading && !message.isStreaming && message.content.isNotBlank()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                IconButton(
                                    onClick = {
                                        if (speakingMessageIndex == index) {
                                            onStopSpeak?.invoke()
                                        } else {
                                            onSpeak?.invoke(index)
                                        }
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = if (speakingMessageIndex == index) Icons.Default.Stop else Icons.Default.VolumeUp,
                                        contentDescription = if (speakingMessageIndex == index) "停止朗读" else "朗读",
                                        modifier = Modifier.size(18.dp),
                                        tint = if (speakingMessageIndex == index)
                                            MaterialTheme.colorScheme.error
                                        else
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (message.toolResults.isNotEmpty()) {
                Column(Modifier.padding(top = 4.dp)) {
                    message.toolResults.forEach { result ->
                        Card(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = if (message.isUser) Color.White.copy(alpha = 0.08f) else Color(0xFFF5F5F5))
                        ) {
                            Text(result, modifier = Modifier.padding(8.dp), fontSize = 11.sp,
                                color = if (message.isUser) Color.White.copy(alpha = 0.7f) else Color(0xFF666666))
                        }
                    }
                }
            }

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

        if (message.isUser) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(primaryColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(userAvatarLetter, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = primaryColor)
            }
        }
    }
}
