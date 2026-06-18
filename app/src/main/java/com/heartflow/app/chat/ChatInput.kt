package com.heartflow.app

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.heartflow.data.*
import java.util.*

// ─── 现代化输入栏（含语音 + 附件）─────────────────────

@Composable
fun ChatInput(
    isProcessing: Boolean,
    voiceState: VoiceUiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit = {},
    onDream: () -> Unit = {},
    onEvolve: () -> Unit = {},
    onVoiceToggle: ((VoiceUiState) -> VoiceUiState) -> Unit,
    onVoiceSend: (String) -> Unit,
    onStartListening: () -> Unit = {},   // 委托给 ViewModel 启动语音识别
    onStopListening: () -> Unit = {},    // 委托给 ViewModel 停止语音识别
    onAttachment: (String, MediaAttachment, String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val scheme = LocalThemeScheme.current
    val isSendEnabled = text.isNotBlank() || voiceState.isRecording

    // 录音权限请求器 — 仅处理权限结果，不直接操作 SpeechRecognizer
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onVoiceToggle { it.copy(isRecording = true, partialResult = null, voiceError = null) }
            onStartListening()
        } else {
            Toast.makeText(context, "需要录音权限才能使用语音输入", Toast.LENGTH_SHORT).show()
            onVoiceToggle { it.copy(isRecording = false) }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val mimeType = context.contentResolver.getType(it) ?: "image/jpeg"
            val fileName = "image_${System.currentTimeMillis()}.jpg"
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val bytes = inputStream?.readBytes()
                inputStream?.close()
                if (bytes != null) {
                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    onAttachment(
                        text.ifBlank { "📷 发送了一张图片" },
                        MediaAttachment(uri = it.toString(), type = "image", mimeType = mimeType, fileName = fileName, fileSize = bytes.size.toLong()),
                        base64
                    )
                    text = ""
                }
            } catch (e: Exception) {
                Toast.makeText(context, "读取图片失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        bitmap?.let {
            try {
                val stream = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, stream)
                val bytes = stream.toByteArray()
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                onAttachment(
                    text.ifBlank { "📸 拍了一张照片" },
                    MediaAttachment(uri = "camera_${System.currentTimeMillis()}", type = "image", mimeType = "image/jpeg", fileName = "camera_${System.currentTimeMillis()}.jpg", fileSize = bytes.size.toLong()),
                    base64
                )
                text = ""
            } catch (e: Exception) {
                Toast.makeText(context, "拍照处理失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                val mimeType = context.contentResolver.getType(it) ?: "application/octet-stream"
                val cursor = context.contentResolver.query(it, null, null, null, null)
                var fileName = "file_${System.currentTimeMillis()}"
                var fileSize = 0L
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (nameIdx >= 0) fileName = c.getString(nameIdx) ?: fileName
                        if (sizeIdx >= 0) fileSize = c.getLong(sizeIdx)
                    }
                }
                val inputStream = context.contentResolver.openInputStream(it)
                val bytes = inputStream?.readBytes()
                inputStream?.close()
                if (bytes != null) {
                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    onAttachment(
                        text.ifBlank { "📎 发送了一个文件" },
                        MediaAttachment(uri = it.toString(), type = "file", mimeType = mimeType, fileName = fileName, fileSize = fileSize),
                        base64
                    )
                    text = ""
                }
            } catch (e: Exception) {
                Toast.makeText(context, "读取文件失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = scheme.glassSurface,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            )
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // ── 语音录音状态 ──
            if (voiceState.isRecording) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                    tonalElevation = 0.dp
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val infiniteTransition = rememberInfiniteTransition(label = "record")
                                val alpha by infiniteTransition.animateFloat(
                                    initialValue = 1f, targetValue = 0.2f,
                                    animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                                    label = "pulse"
                                )
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFEF5350).copy(alpha = alpha))
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "录音中 ${voiceState.recordingDuration}s",
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                            }
                            IconButton(
                                onClick = {
                                    onStopListening()
                                    onVoiceToggle { it.copy(isRecording = false, partialResult = null) }
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Stop, "停止", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                        // ── 部分识别结果（实时显示） ──
                        if (voiceState.partialResult != null) {
                            Text(
                                text = voiceState.partialResult,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                fontSize = 13.sp,
                                maxLines = 2,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }

            // ── 快捷操作栏 ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 附件按钮
                IconButton(
                    onClick = { if (!isProcessing) showAttachmentMenu = !showAttachmentMenu },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.AttachFile, "附件",
                        modifier = Modifier.size(18.dp),
                        tint = if (isProcessing) Color.Gray else MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.width(2.dp))

                // 附件菜单
                Box {
                    DropdownMenu(
                        expanded = showAttachmentMenu,
                        onDismissRequest = { showAttachmentMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("📷 相册") },
                            onClick = { showAttachmentMenu = false; imagePickerLauncher.launch("image/*") },
                            leadingIcon = { Icon(Icons.Default.Photo, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("📸 拍照") },
                            onClick = { showAttachmentMenu = false; cameraLauncher.launch(null) },
                            leadingIcon = { Icon(Icons.Default.CameraAlt, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("📎 文件") },
                            onClick = { showAttachmentMenu = false; filePickerLauncher.launch("*/*") },
                            leadingIcon = { Icon(Icons.Default.Description, null) }
                        )
                    }
                }

                Spacer(Modifier.width(4.dp))

                // 快捷 Chip 滚动条
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SuggestionChip(
                        onClick = { onDream() },
                        label = { Text("做梦", fontSize = 12.sp) },
                        icon = { Text("💤", fontSize = 12.sp) },
                        modifier = Modifier.height(28.dp)
                    )
                    SuggestionChip(
                        onClick = { onEvolve() },
                        label = { Text("进化", fontSize = 12.sp) },
                        icon = { Text("🧬", fontSize = 12.sp) },
                        modifier = Modifier.height(28.dp)
                    )
                    SuggestionChip(
                        onClick = { text = "/new " },
                        label = { Text("新对话", fontSize = 12.sp) },
                        icon = { Text("✨", fontSize = 12.sp) },
                        modifier = Modifier.height(28.dp)
                    )
                }
            }

            // ── 输入行 ──
            Row(
                modifier = Modifier.fillMaxWidth().imePadding(),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { isFocused = it.isFocused },
                    placeholder = { Text("输入消息...", fontSize = 15.sp) },
                    shape = RoundedCornerShape(28.dp),
                    singleLine = false,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (text.isNotBlank()) {
                                onSend(text.trim()); text = ""
                            }
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        unfocusedContainerColor = scheme.surface.copy(alpha = 0.5f),
                        focusedContainerColor = scheme.surface.copy(alpha = 0.7f)
                    )
                )

                Spacer(Modifier.width(8.dp))

                // 语音按钮（仅当没有文字且不在处理时显示）
                if (!isProcessing && !voiceState.isRecording && text.isBlank()) {
                    Surface(
                        onClick = {
                            // 检查录音权限
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                == PackageManager.PERMISSION_GRANTED
                            ) {
                                onVoiceToggle { it.copy(isRecording = true, partialResult = null, voiceError = null) }
                                onStartListening()
                            } else {
                                recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        tonalElevation = 0.dp,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Mic, "语音",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                }

                // 停止/发送按钮
                if (isProcessing) {
                    Surface(
                        onClick = { onStop(); text = "" },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.error,
                        tonalElevation = 0.dp,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Close, "停止", tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                    }
                } else {
                    val sendBtnColor by animateColorAsState(
                        targetValue = if (text.isNotBlank()) MaterialTheme.colorScheme.primary else scheme.surfaceVariant,
                        animationSpec = tween(200),
                        label = "sendBtnColor"
                    )
                    val sendIconColor by animateColorAsState(
                        targetValue = if (text.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        animationSpec = tween(200),
                        label = "sendIconColor"
                    )
                    Surface(
                        onClick = {
                            if (text.isNotBlank()) {
                                onSend(text.trim()); text = ""
                            }
                        },
                        shape = CircleShape,
                        color = sendBtnColor,
                        tonalElevation = 0.dp,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send, "发送",
                                tint = sendIconColor,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }

        // 顶部细线分割
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))
                .align(Alignment.TopCenter)
        )
    }
}
