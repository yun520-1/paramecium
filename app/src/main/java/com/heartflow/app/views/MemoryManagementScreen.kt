package com.heartflow.app.views

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heartflow.app.ChatViewModel
import com.heartflow.memory.MemoryItem
import com.heartflow.memory.MemoryLayer
import com.heartflow.memory.MemoryStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记忆管理页面 — 按层浏览和逐条删除记忆
 *
 * 布局：
 * ┌─────────────────────────────────┐
 * │  [←]  记忆管理     [层切换Tabs]  │  ← 顶栏
 * ├─────────────────────────────────┤
 * │  [卡片1]     [X]               │
 * │  · 内容摘要...                   │
 * │  · 标签/重要性/时间             │
 * ├─────────────────────────────────┤
 * │  [卡片2]     [X]               │
 * │  · 内容摘要...                   │
 * ├─────────────────────────────────┤
 * │  [空状态] "暂无记忆"            │
 * └─────────────────────────────────┘
 */

/** 避免各个 Composable 中直接依赖非稳定类型，转为列表存储 */
private data class MemoryUiItem(
    val id: String,
    val content: String,
    val layer: String,
    val importance: Int,
    val timestamp: Long,
    val tags: List<String>,
    val emotion: String?,
    val source: String?
)

private fun MemoryItem.toUi(): MemoryUiItem = MemoryUiItem(
    id = id,
    content = content,
    layer = layer.name,
    importance = importance,
    timestamp = timestamp,
    tags = tags,
    emotion = emotion,
    source = source
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryManagementScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit = {}
) {
    // 从 ViewModel 的 memorySystem 刷新数据
    var stats by remember { mutableStateOf(MemoryStats()) }
    var selectedLayer by remember { mutableIntStateOf(0) } // 0=全部 1=工作 2=情景 3=核心
    var memoryMap by remember { mutableStateOf(mapOf<String, List<MemoryUiItem>>()) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }
    var toastMessage by remember { mutableStateOf<String?>(null) }

    // 刷新数据
    fun refresh() {
        stats = viewModel.memorySystem.getStats()
        val all = viewModel.memorySystem.getAllItems()
        memoryMap = mapOf(
            "全部" to all.map { it.toUi() },
            "工作" to viewModel.memorySystem.query(MemoryLayer.WORKING).map { it.toUi() },
            "情景" to viewModel.memorySystem.query(MemoryLayer.EPISODIC).map { it.toUi() },
            "核心" to viewModel.memorySystem.query(MemoryLayer.CORE).map { it.toUi() }
        )
    }

    LaunchedEffect(Unit) { refresh() }

    val tabs = listOf("全部", "工作", "情景", "核心")
    val currentItems = when (selectedLayer) {
        0 -> memoryMap["全部"] ?: emptyList()
        1 -> memoryMap["工作"] ?: emptyList()
        2 -> memoryMap["情景"] ?: emptyList()
        3 -> memoryMap["核心"] ?: emptyList()
        else -> emptyList()
    }
    val currentLabel = tabs.getOrElse(selectedLayer) { "全部" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记忆管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // ── 统计概览 ──
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatChip("工作", stats.workingCount, MaterialTheme.colorScheme.primary)
                    StatChip("情景", stats.episodicCount, MaterialTheme.colorScheme.tertiary)
                    StatChip("核心", stats.coreCount, MaterialTheme.colorScheme.secondary)
                    StatChip("总计", stats.totalMemories, Color.Gray)
                }
            }

            // ── 层切换 Tab ──
            TabRow(
                selectedTabIndex = selectedLayer,
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                tabs.forEachIndexed { idx, label ->
                    Tab(
                        selected = selectedLayer == idx,
                        onClick = { selectedLayer = idx },
                        text = {
                            Text(
                                label,
                                fontSize = 13.sp,
                                maxLines = 1,
                                fontWeight = if (selectedLayer == idx) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            // ── 记忆列表 ──
            if (currentItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Memory,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "暂无${currentLabel}记忆",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(currentItems, key = { it.id }) { item ->
                        MemoryItemCard(
                            item = item,
                            onDelete = { showDeleteConfirm = item.id }
                        )
                    }

                    // 底部间距
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }

    // 删除确认对话框
    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("确认删除") },
            text = { Text("确定要删除这条记忆吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm?.let { id ->
                            viewModel.memorySystem.forget(id)
                            refresh()
                            toastMessage = "已删除 1 条记忆"
                        }
                        showDeleteConfirm = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF5350))
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("取消") }
            }
        )
    }

    // Toast 消息
    if (toastMessage != null) {
        AlertDialog(
            onDismissRequest = { toastMessage = null },
            confirmButton = { TextButton(onClick = { toastMessage = null }) { Text("知道了") } },
            title = { Text(toastMessage!!) }
        )
    }
}

@Composable
private fun StatChip(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(count.toString(), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun MemoryItemCard(
    item: MemoryUiItem,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // 内容
                Text(
                    text = item.content,
                    fontSize = 13.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(4.dp))

                // 元信息行
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 重要性徽章
                    if (item.importance > 3) {
                        Surface(
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                "重要:${item.importance}",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }

                    // 标签
                    item.tags.take(3).forEach { tag ->
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                tag,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    // 时间
                    Text(
                        dateFormat.format(Date(item.timestamp)),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }

                // 来源/情绪行
                if (item.source != null || item.emotion != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        item.source?.let {
                            Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                        }
                        item.emotion?.let {
                            Text("· $it", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                        }
                    }
                }
            }

            // 删除按钮
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    "删除",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
