package com.heartflow.app.views

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heartflow.app.ChatViewModel
import com.heartflow.app.LocalThemeScheme
import com.heartflow.memory.MemoryItem
import com.heartflow.memory.MemoryLayer
import com.heartflow.memory.MemoryStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val scheme = LocalThemeScheme.current

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
        containerColor = scheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "记忆管理",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = scheme.glassSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── 统计概览 ──
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 0.dp,
                color = scheme.surfaceContainerLow
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MemoryStatChip("工作", stats.workingCount, MaterialTheme.colorScheme.primary)
                    MemoryStatChip("情景", stats.episodicCount, MaterialTheme.colorScheme.tertiary)
                    MemoryStatChip("核心", stats.coreCount, MaterialTheme.colorScheme.secondary)
                    MemoryStatChip("总计", stats.totalMemories, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                }
            }

            // ── 层切换 Tab ──
            TabRow(
                selectedTabIndex = selectedLayer,
                containerColor = scheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                tabs.forEachIndexed { idx, label ->
                    val selected = selectedLayer == idx
                    Tab(
                        selected = selected,
                        onClick = { selectedLayer = idx },
                        text = {
                            Text(
                                label,
                                fontSize = 13.sp,
                                maxLines = 1,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

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
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "暂无${currentLabel}记忆",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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
            containerColor = scheme.glassSurface,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    "确认删除",
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    "确定要删除这条记忆吗？此操作不可撤销。",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            },
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
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
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
            containerColor = scheme.glassSurface,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    toastMessage!!,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { toastMessage = null },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("知道了", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
            }
        )
    }
}

@Composable
private fun MemoryStatChip(label: String, count: Int, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            count.toString(),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun MemoryItemCard(
    item: MemoryUiItem,
    onDelete: () -> Unit
) {
    val scheme = LocalThemeScheme.current
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 0.dp,
        color = scheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            // 记忆图标
            Surface(
                shape = RoundedCornerShape(10.dp),
                tonalElevation = 0.dp,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // 内容
                Text(
                    text = item.content,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(6.dp))

                // 元信息行
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 重要性徽章
                    if (item.importance > 3) {
                        Surface(
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "重要:${item.importance}",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // 标签
                    item.tags.take(3).forEach { tag ->
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                tag,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
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
                    Spacer(Modifier.height(3.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item.source?.let {
                            Text(
                                it,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                            )
                        }
                        item.emotion?.let {
                            Text(
                                "· $it",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                            )
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
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
