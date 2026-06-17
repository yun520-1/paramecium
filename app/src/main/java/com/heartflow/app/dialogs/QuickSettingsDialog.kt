package com.heartflow.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heartflow.app.ApiConfig
import com.heartflow.data.Personalities
import com.heartflow.data.Personality

@Composable
fun QuickSettingsDialog(
    currentConfig: ApiConfig?,
    personality: Personality,
    onDismiss: () -> Unit,
    onSaveConfig: (ApiConfig) -> Unit,
    onSelectPersonality: (Personality) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("快速设置") },
        text = {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { tab = 0 }, modifier = Modifier.weight(1f)) { Text("性格", fontWeight = if (tab == 0) FontWeight.Bold else FontWeight.Normal) }
                    TextButton(onClick = { tab = 1 }, modifier = Modifier.weight(1f)) { Text("API", fontWeight = if (tab == 1) FontWeight.Bold else FontWeight.Normal) }
                }

                when (tab) {
                    0 -> {
                        Personalities.all.forEach { p ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onSelectPersonality(p) }.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(p.emoji, fontSize = 24.sp)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, fontWeight = if (p.id == personality.id) FontWeight.Bold else FontWeight.Normal)
                                    Text(p.description, fontSize = 11.sp, color = Color.Gray)
                                }
                                if (p.id == personality.id) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    1 -> {
                        var provider by remember { mutableStateOf(currentConfig?.provider ?: "moonshot") }
                        var apiKey by remember { mutableStateOf(currentConfig?.apiKey ?: "") }
                        var model by remember { mutableStateOf(currentConfig?.model ?: "") }

                        OutlinedTextField(value = provider, onValueChange = { provider = it }, label = { Text("提供商") }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("模型") }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            if (apiKey.isNotBlank()) {
                                onSaveConfig(ApiConfig(provider = provider, apiKey = apiKey, model = model))
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("保存") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
