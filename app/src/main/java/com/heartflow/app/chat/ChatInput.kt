package com.heartflow.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heartflow.data.*
import java.util.*

// ─── 输入框（含语音 + 附件） ─────────────────────────

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
    onAttachment: (String, MediaAttachment, String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    val context = LocalContext.current

    // 语音识别器清理：离开页面时销毁
    DisposableEffect(Unit) {
        onDispose {
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
    }

    val recordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) { }
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
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            )
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (voiceState.isRecording) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        Modifier.padding(12.dp).fillMaxWidth(),
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
                                Modifier
                                    .size(12.dp)
                                    .background(Color.Red.copy(alpha = alpha), CircleShape)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "录音中... ${voiceState.recordingDuration}s",
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(onClick = {
                            speechRecognizer?.destroy()
                            speechRecognizer = null
                            onVoiceToggle { it.copy(isRecording = false) }
                        }) {
                            Icon(Icons.Default.Stop, "停止录音", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { if (!isProcessing) showAttachmentMenu = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = "附件",
                        modifier = Modifier.size(18.dp),
                        tint = if (isProcessing) Color.Gray else MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.width(2.dp))

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

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = { onDream() },
                        label = { Text("做梦", fontSize = 12.sp) },
                        leadingIcon = { Text("💤", fontSize = 13.sp) },
                        modifier = Modifier.height(30.dp)
                    )
                    AssistChip(
                        onClick = { onEvolve() },
                        label = { Text("进化", fontSize = 12.sp) },
                        leadingIcon = { Text("🧬", fontSize = 13.sp) },
                        modifier = Modifier.height(30.dp)
                    )
                    AssistChip(
                        onClick = { text = "/new " },
                        label = { Text("新对话", fontSize = 12.sp) },
                        leadingIcon = { Text("✨", fontSize = 13.sp) },
                        modifier = Modifier.height(30.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().imePadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { isFocused = it.isFocused }
                        .shadow(
                            elevation = if (isFocused) 14.dp else 4.dp,
                            shape = RoundedCornerShape(28.dp),
                            clip = false
                        ),
                    placeholder = { Text("输入消息...", fontSize = 15.sp) },
                    shape = RoundedCornerShape(28.dp),
                    singleLine = false,
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                )

                Spacer(Modifier.width(6.dp))

                if (!isProcessing && !voiceState.isRecording && text.isBlank()) {
                    FilledIconButton(
                        onClick = {
                            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            onVoiceToggle { it.copy(isRecording = true, recordingDuration = 0) }
                            // 创建并启动 SpeechRecognizer
                            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                            recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
                                override fun onResults(results: Bundle) {
                                    val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    if (matches != null && matches.isNotEmpty()) {
                                        onVoiceSend(matches[0])
                                    }
                                    recognizer.destroy()
                                    if (speechRecognizer === recognizer) speechRecognizer = null
                                    onVoiceToggle { it.copy(isRecording = false) }
                                }
                                override fun onReadyForSpeech(params: Bundle) {}
                                override fun onBeginningOfSpeech() {}
                                override fun onRmsChanged(rmsdB: Float) {}
                                override fun onBufferReceived(buffer: ByteArray) {}
                                override fun onEndOfSpeech() {}
                                override fun onError(error: Int) {
                                    val msg = when (error) {
                                        SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音"
                                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音输入超时"
                                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙，请重试"
                                        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                                        SpeechRecognizer.ERROR_AUDIO -> "音频错误"
                                        else -> "语音识别失败: $error"
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    recognizer.destroy()
                                    if (speechRecognizer === recognizer) speechRecognizer = null
                                    onVoiceToggle { it.copy(isRecording = false) }
                                }
                                override fun onPartialResults(partialResults: Bundle) {}
                                override fun onEvent(eventType: Int, params: Bundle) {}
                            })
                            speechRecognizer = recognizer
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE)
                                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                            }
                            recognizer.startListening(intent)
                        },
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        )
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            "语音",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }

                if (isProcessing) {
                    FilledIconButton(
                        onClick = { onStop(); text = "" },
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Close, "停止", tint = Color.White)
                    }
                } else {
                    FilledIconButton(
                        onClick = {
                            if (text.isNotBlank()) {
                                onSend(text.trim())
                                text = ""
                            }
                        },
                        enabled = text.isNotBlank(),
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (text.isNotBlank())
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            "发送",
                            tint = if (text.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                .align(Alignment.TopCenter)
        )
    }
}
