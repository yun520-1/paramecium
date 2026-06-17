package com.heartflow.app

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heartflow.data.*

@Composable
fun MediaSettings(viewModel: ChatViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var sttEngine by remember { mutableStateOf("system") }
    var mediaQuality by remember { mutableStateOf("compressed") }
    var autoDownload by remember { mutableStateOf(true) }
    var ttsSpeed by remember { mutableFloatStateOf(viewModel.getTtsSpeed()) }
    var ttsPitch by remember { mutableFloatStateOf(viewModel.getTtsPitch()) }
    var savedMessage by remember { mutableStateOf("") }
    val context = LocalContext.current

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = sttEngine == "system", onClick = { sttEngine = "system" })
                        Column(Modifier.weight(1f)) {
                            Text("系统内置识别", fontSize = 14.sp)
                            Text("使用 Android SpeechRecognizer", fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                    Divider()
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = sttEngine == "api", onClick = { sttEngine = "api" })
                        Column(Modifier.weight(1f)) {
                            Text("API 语音转文字", fontSize = 14.sp)
                            Text("使用大模型能力进行转写", fontSize = 11.sp, color = Color.Gray)
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = mediaQuality == "compressed", onClick = { mediaQuality = "compressed" })
                        Column(Modifier.weight(1f)) {
                            Text("压缩（省流量）", fontSize = 14.sp)
                            Text("自动压缩图片以节省流量", fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                    Divider()
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = mediaQuality == "original", onClick = { mediaQuality = "original" })
                        Column(Modifier.weight(1f)) {
                            Text("原图（高清）", fontSize = 14.sp)
                            Text("保持原始分辨率发送", fontSize = 11.sp, color = Color.Gray)
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("自动下载媒体", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("自动下载对话中的图片和文件", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(checked = autoDownload, onCheckedChange = { autoDownload = it })
                }
            }
        }

        // ── 语音朗读 ──
        item {
            SettingsSectionHeader(
                icon = Icons.Default.VolumeUp,
                title = "语音朗读",
                subtitle = "调节 AI 回复的语音朗读参数"
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    // 语速
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("语速", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Text("%.1fx".format(ttsSpeed), fontSize = 13.sp, color = Color.Gray)
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
                    Spacer(Modifier.height(8.dp))

                    // 音调
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("音调", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Text("%.1f".format(ttsPitch), fontSize = 13.sp, color = Color.Gray)
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
                    Spacer(Modifier.height(12.dp))

                    // 测试朗读按钮
                    OutlinedButton(
                        onClick = {
                            var testTts: TextToSpeech? = null
                            testTts = TextToSpeech(context) { status ->
                                val engine = testTts ?: return@TextToSpeech
                                if (status == TextToSpeech.SUCCESS) {
                                    engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                                        override fun onStart(id: String?) {}
                                        override fun onDone(id: String?) { engine.shutdown() }
                                        override fun onError(id: String?) { engine.shutdown() }
                                        override fun onStop(id: String?, isInterrupted: Boolean) { engine.shutdown() }
                                    })
                                    engine.language = java.util.Locale.CHINESE
                                    engine.setPitch(ttsPitch)
                                    engine.setSpeechRate(ttsSpeed)
                                    engine.speak("你好，我是心虫。这是我的语音朗读测试。", TextToSpeech.QUEUE_FLUSH, null, "tts_test")
                                } else {
                                    engine.shutdown()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("测试朗读", fontSize = 14.sp)
                    }
                }
            }
        }

        // ── 保存 ──
        item {
            Spacer(Modifier.height(4.dp))
            Text("调整后自动生效，无需手动保存", fontSize = 12.sp, color = Color.Gray)
        }
    }
}
