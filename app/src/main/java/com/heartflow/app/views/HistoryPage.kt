package com.heartflow.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryPage(viewModel: ChatViewModel, uiState: ChatUiState) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("对话历史") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = Color.White)
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(icon = { Icon(Icons.AutoMirrored.Filled.Chat, "聊天") }, label = { Text("聊天") }, selected = false, onClick = { viewModel.setPage("chat") })
                NavigationBarItem(icon = { Icon(Icons.Default.History, "历史") }, label = { Text("历史") }, selected = true, onClick = { viewModel.setPage("history") })
                NavigationBarItem(icon = { Icon(Icons.Default.Terminal, "终端") }, label = { Text("终端") }, selected = false, onClick = { viewModel.setPage("terminal") })
                NavigationBarItem(icon = { Icon(Icons.Default.Settings, "设置") }, label = { Text("设置") }, selected = false, onClick = { viewModel.setPage("settings") })
            }
        }
    ) { padding ->
        if (uiState.conversations.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ChatBubbleOutline, null, modifier = Modifier.size(64.dp), tint = Color.Gray)
                    Spacer(Modifier.height(16.dp))
                    Text("暂无对话记录", color = Color.Gray)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.conversations) { conv ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            viewModel.loadConversation(conv.id)
                            viewModel.setPage("chat")
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(conv.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(conv.preview, fontSize = 12.sp, color = Color.Gray, maxLines = 1)
                                Text("${conv.messageCount}条消息 · ${conv.personalityId}", fontSize = 10.sp, color = Color.Gray)
                            }
                            IconButton(onClick = { viewModel.deleteConversation(conv.id) }) {
                                Icon(Icons.Default.Delete, "删除", tint = Color.Gray, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
