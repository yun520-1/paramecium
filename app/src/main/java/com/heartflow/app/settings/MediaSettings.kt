package com.heartflow.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.heartflow.app.LocalThemeScheme

@Composable
fun MediaSettings(viewModel: ChatViewModel) {
    val scheme = LocalThemeScheme.current
    val uiState by viewModel.uiState.collectAsState()
    var sttEngine by remember { mutableStateOf("system") }
    var mediaQuality by remember { mutableStateOf("compressed") }
    var autoDownload by remember { mutableStateOf(true) }
    var ttsSpeed by remember { mutableFloatStateOf(viewModel.getTtsSpeed()) }
    var ttsPitch by remember { mutableFloatStateOf(viewModel.getTtsPitch()) }
    var savedMessage by remember { mutableStateOf("") }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── 语音识别 ──
        item {
            SettingsSectionHeader(
                icon = Icons.Default.Mic,
                title = "语音识别",
                subtitle = "选择语音转文字的引擎"
            )
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 0.dp,
                color = scheme.surfaceContainerLow
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = sttEngine == "system", onClick = { sttEngine = "system" })
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("系统内置识别", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("使用 Android SpeechRecognizer", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = sttEngine == "api", onClick = { sttEngine = "api" })
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("API 语音转文字", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("使用大模型能力进行转写", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }

        // ── 媒体质量 ──
        item {
            SettingsSectionHeader(
                icon = Icons.Default.Image,
                title = "媒体质量",
                subtitle = "控制图片和文件的发送质量"
            )
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 0.dp,
                color = scheme.surfaceContainerLow
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = mediaQuality == "compressed", onClick = { mediaQuality = "compressed" })
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("压缩（省流量）", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("自动压缩图片以节省流量", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = mediaQuality == "original", onClick = { mediaQuality = "original" })
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("原图（高清）", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("保持原始分辨率发送", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }

        // ── 自动下载 ──
        item {
            SettingsSectionHeader(
                icon = Icons.Default.Download,
                title = "自动下载",
                subtitle = "控制媒体文件的自动下载行为"
            )
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 0.dp,
                color = scheme.surfaceContainerLow
            ) {
                Row(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("自动下载媒体", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text("自动下载对话中的图片和文件", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                    Switch(checked = autoDownload, onCheckedChange = { autoDownload = it })
                }
            }
        }

        // ── 语音朗读 ──
        item {
            SettingsSectionHeader(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                title = "语音朗读",
                subtitle = "调节 AI 回复的语音朗读参数"
            )
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 0.dp,
                color = scheme.surfaceContainerLow
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
                    // 语速
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("语速", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("%.1fx".format(ttsSpeed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                        Slider(
                            value = ttsSpeed,
                            onValueChange = { ttsSpeed = it },
                            onValueChangeFinished = {
                                viewModel.updateTtsSettings(ttsSpeed, ttsPitch)
                            },
                            valueRange = 0.5f..2.0f,
                            steps = 5,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 音调
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("音调", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("%.1f".format(ttsPitch), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                        Slider(
                            value = ttsPitch,
                            onValueChange = { ttsPitch = it },
                            onValueChangeFinished = {
                                viewModel.updateTtsSettings(ttsSpeed, ttsPitch)
                            },
                            valueRange = 0.5f..2.0f,
                            steps = 5,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 测试朗读按钮 — 使用 ViewModel 统一管理
                    OutlinedButton(
                        onClick = { viewModel.testTts() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("测试朗读", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // ── 保存提示 ──
        item {
            Spacer(Modifier.height(4.dp))
            Text(
                "调整后自动生效，无需手动保存",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}
