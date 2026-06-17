package com.heartflow.app.views

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heartflow.app.imaging.ScanImageProcessor
import com.heartflow.app.model.ScanFilterType
import com.heartflow.scanner.DocumentScanner
import com.heartflow.scanner.ImageProcessor as ImageProcessor
import com.heartflow.scanner.PdfExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 专业级文档扫描页面 v2.0
 *
 * 核心流程：
 * 1. 从相册选择图片（自动采样缩放至 2048px 以内）
 * 2. 【新】一键文档矫正 — Canny 边缘检测 + 透视变换矫正
 * 3. 8 种专业级扫描效果增强
 * 4. 原图/处理后对比预览
 * 5. 导出为 JPEG 或 PDF
 *
 * 文档矫正算法（纯 Kotlin 实现，无 OpenCV 依赖）：
 * - Canny 边缘检测 + 形态学闭运算 + 连通组件分析 + Monotone Chain 凸包 + Douglas-Peucker 简化
 * - 自动降级：canny 管道检测失败时回退到传统四边边界扫描
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScannerScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── 状态 ──────────────────────────────────────────────
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var correctedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var processedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(ScanFilterType.AUTO_ENHANCE) }
    var selectedFormat by remember { mutableStateOf("jpg") }
    var saveResult by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var detectionStatus by remember { mutableStateOf<String?>(null) }

    val bitmapLoader = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { targetUri ->
            scope.launch {
                loadAndSetBitmap(context, targetUri, scope,
                    onLoaded = { bmp ->
                        selectedBitmap = bmp
                        correctedBitmap = null
                        processedBitmap = null
                        saveResult = null
                        errorMessage = null
                        detectionStatus = null
                    },
                    onError = { errorMessage = it }
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("专业文档扫描") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 功能说明 ──────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.AutoAwesome, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "专业扫描 v2：边缘检测 + 透视矫正 + 8种增强效果",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }

            // ── 选择图片 ──────────────────────────────
            Button(
                onClick = { bitmapLoader.launch("image/*") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("从相册选择图片")
            }

            // ── 错误提示 ──────────────────────────────
            StatusCard(
                visible = errorMessage != null,
                icon = { Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error) },
                text = errorMessage ?: "",
                textColor = MaterialTheme.colorScheme.error,
                containerColor = MaterialTheme.colorScheme.errorContainer
            )

            // ── 检测状态 ──────────────────────────────
            StatusCard(
                visible = detectionStatus != null,
                icon = { Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp)) },
                text = detectionStatus ?: "",
                textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )

            // ── 有图片时显示操作区 ────────────────────
            if (selectedBitmap != null) {
                ActionBar(
                    isProcessing = isProcessing,
                    correctionAvailable = correctedBitmap != null && processedBitmap == null && detectionStatus?.contains("降级") == false,
                    onCorrect = {
                        scope.launch {
                            runDocumentCorrection(
                                context, selectedBitmap!!,
                                onStart = { isProcessing = true; detectionStatus = null; errorMessage = null },
                                onSuccess = { corrected, note ->
                                    correctedBitmap = corrected
                                    processedBitmap = corrected
                                    detectionStatus = note
                                },
                                onFallback = { enhanced ->
                                    correctedBitmap = enhanced
                                    processedBitmap = enhanced
                                    detectionStatus = "⚠️ 边缘检测降级，正在使用增强模式"
                                },
                                onError = { errorMessage = it },
                                onFinish = { isProcessing = false }
                            )
                        }
                    },
                    onPreview = {
                        scope.launch {
                            runFilterPreview(
                                context, correctedBitmap ?: selectedBitmap!!, selectedFilter,
                                onStart = { isProcessing = true; errorMessage = null },
                                onSuccess = { result ->
                                    processedBitmap = result
                                    if (correctedBitmap != null) detectionStatus = "✅ 文档矫正 + 滤镜已应用"
                                },
                                onError = { errorMessage = it },
                                onFinish = { isProcessing = false }
                            )
                        }
                    },
                    onSave = {
                        scope.launch {
                            runSave(
                                context, correctedBitmap ?: selectedBitmap!!, selectedFilter, selectedFormat,
                                onStart = { isProcessing = true; errorMessage = null },
                                onSuccess = { path ->
                                    processedBitmap = if (selectedFormat == "jpg") {
                                        ScanImageProcessor.apply(selectedFilter, correctedBitmap ?: selectedBitmap!!)
                                    } else null
                                    saveResult = path
                                    Toast.makeText(context, "✅ 已保存至: $path", Toast.LENGTH_LONG).show()
                                },
                                onError = { errorMessage = it },
                                onFinish = { isProcessing = false }
                            )
                        }
                    }
                )

                // ── 滤镜选择器 ──────────────────────────
                FilterSelector(
                    selectedFilter = selectedFilter,
                    hasCorrection = correctedBitmap != null,
                    onFilterSelected = { selectedFilter = it }
                )

                // ── 导出格式 ──────────────────────────
                FormatSelector(
                    selectedFormat = selectedFormat,
                    onFormatSelected = { selectedFormat = it }
                )

                // ── 双栏预览 ──────────────────────────
                DualPreviewPane(
                    original = selectedBitmap!!,
                    processed = processedBitmap,
                    corrected = correctedBitmap,
                    filterName = selectedFilter.displayName,
                    isProcessing = isProcessing
                )

                // ── 保存结果提示 ──────────────────────
                StatusCard(
                    visible = saveResult != null && !isProcessing,
                    icon = { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary) },
                    text = "已保存: ${saveResult ?: ""}",
                    textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    small = true
                )
            } else {
                EmptyState()
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  UI 子组件
// ═══════════════════════════════════════════════════════════

/**
 * 通用状态卡片
 */
@Composable
private fun StatusCard(
    visible: Boolean,
    icon: @Composable () -> Unit,
    text: String,
    textColor: androidx.compose.ui.graphics.Color,
    containerColor: androidx.compose.ui.graphics.Color,
    small: Boolean = false
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = containerColor)
        ) {
            Row(
                modifier = Modifier.padding(if (small) 8.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                icon()
                Spacer(Modifier.width(if (small) 6.dp else 8.dp))
                Text(text, fontSize = if (small) 12.sp else 14.sp, color = textColor)
            }
        }
    }
}

/**
 * 操作按钮：文档矫正 / 预览效果 / 保存
 */
@Composable
private fun ActionBar(
    isProcessing: Boolean,
    correctionAvailable: Boolean,
    onCorrect: () -> Unit,
    onPreview: () -> Unit,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Button(
            onClick = onCorrect,
            enabled = !isProcessing,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("文档矫正", fontSize = 13.sp)
        }

        OutlinedButton(
            onClick = onPreview,
            enabled = !isProcessing,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("预览效果", fontSize = 13.sp)
        }

        Button(
            onClick = onSave,
            enabled = !isProcessing,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("保存", fontSize = 13.sp)
        }
    }
}

/**
 * 横向滚动的滤镜选择器
 */
@Composable
private fun FilterSelector(
    selectedFilter: ScanFilterType,
    hasCorrection: Boolean,
    onFilterSelected: (ScanFilterType) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("扫描效果", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                if (hasCorrection) "点击切换滤镜，原图为矫正后文档" else "点击切换滤镜预览效果",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ScanFilterType.entries.forEach { filter ->
                    FilterItem(
                        filter = filter,
                        isSelected = selectedFilter == filter,
                        onClick = { onFilterSelected(filter) }
                    )
                }
            }
        }
    }
}

/**
 * 单个滤镜项
 */
@Composable
private fun FilterItem(
    filter: ScanFilterType,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .then(
                if (isSelected) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(8.dp)
                ) else Modifier
            )
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    when (filter) {
                        ScanFilterType.ORIGINAL -> Color(0xFF888888)
                        ScanFilterType.AUTO_ENHANCE -> Color(0xFF4CAF50)
                        ScanFilterType.WHITE_DOCUMENT -> Color.White
                        ScanFilterType.BLACK_AND_WHITE -> Color.Black
                        ScanFilterType.REMOVE_NOISE -> Color(0xFF2196F3)
                        ScanFilterType.BRIGHTEN -> Color(0xFFFFEB3B)
                        ScanFilterType.SHARPEN_TEXT -> Color(0xFF9C27B0)
                        ScanFilterType.RECEIPT -> Color(0xFFFF9800)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                filter.icon,
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            filter.displayName,
            fontSize = 10.sp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

/**
 * 导出格式选择
 */
@Composable
private fun FormatSelector(
    selectedFormat: String,
    onFormatSelected: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("导出格式:", fontWeight = FontWeight.Bold, fontSize = 14.sp)

            FilterChip(
                selected = selectedFormat == "jpg",
                onClick = { onFormatSelected("jpg") },
                label = { Text("JPEG图片") },
                leadingIcon = { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
            )

            FilterChip(
                selected = selectedFormat == "pdf",
                onClick = { onFormatSelected("pdf") },
                label = { Text("PDF文档") },
                leadingIcon = { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
            )
        }
    }
}

/**
 * 双栏对比预览
 */
@Composable
private fun DualPreviewPane(
    original: Bitmap,
    processed: Bitmap?,
    corrected: Bitmap?,
    filterName: String,
    isProcessing: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 原图侧
        Card(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (corrected != null) "矫正前" else "原图",
                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(4.dp)
                )
                Image(
                    bitmap = original.asImageBitmap(),
                    contentDescription = "原始图片",
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Fit
                )
            }
        }

        // 处理后侧
        Card(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when {
                        processed != null -> filterName
                        corrected != null -> "矫正预览"
                        else -> "处理预览"
                    },
                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(4.dp)
                )
                if (processed != null) {
                    Image(
                        bitmap = processed.asImageBitmap(),
                        contentDescription = "处理后图片",
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Fit
                    )
                } else if (isProcessing) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(4.dp))
                            Text("处理中...", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "点击「文档矫正」\n或「预览效果」",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

/**
 * 空状态
 */
@Composable
private fun EmptyState() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.DocumentScanner,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "请选择图片开始扫描",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "支持自动文档边缘检测 + 透视矫正 + 8种增强效果",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  业务操作（顶层函数，便于独立测试）
// ═══════════════════════════════════════════════════════════

/**
 * 从 URI 采样加载 Bitmap（最大边长 2048px，避免 OOM）
 */
private suspend fun loadAndSetBitmap(
    context: Context,
    uri: Uri,
    scope: kotlinx.coroutines.CoroutineScope,
    onLoaded: (Bitmap) -> Unit,
    onError: (String) -> Unit
) {
    scope.launch {
        try {
            val bitmap = withContext(Dispatchers.IO) {
                loadBitmapSampled(context, uri, maxSize = 2048)
            }
            if (bitmap != null) onLoaded(bitmap)
            else onError("无法加载图片")
        } catch (e: Exception) {
            onError("加载失败: ${e.message}")
        }
    }
}

/**
 * 采样加载图片，限制最大尺寸
 */
private fun loadBitmapSampled(context: Context, uri: Uri, maxSize: Int): Bitmap? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            // 第一次解码：只读尺寸
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, opts)
        }

        context.contentResolver.openInputStream(uri)?.use { stream ->
            val opts = BitmapFactory.Options().apply {
                // 计算采样率
                val w = outWidth
                val h = outHeight
                var sample = 1
                while (w / sample > maxSize || h / sample > maxSize) sample *= 2
                inSampleSize = sample
            }
            BitmapFactory.decodeStream(stream, null, opts)
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * 文档矫正：Canny 边缘检测 → 透视变换
 */
private suspend fun runDocumentCorrection(
    context: Context,
    bitmap: Bitmap,
    onStart: () -> Unit,
    onSuccess: (Bitmap, String) -> Unit,
    onFallback: (Bitmap) -> Unit,
    onError: (String) -> Unit,
    onFinish: () -> Unit
) {
    onStart()
    try {
        val corrected = withContext(Dispatchers.Default) {
            val scanner = DocumentScanner()
            try {
                val corners = scanner.detectCorners(bitmap)
                val isFallback = corners.any { it.x == 0f || it.y == 0f } ||
                    computeQuadArea(corners) == 0f

                if (!isFallback && corners.size == 4) {
                    "✅ 文档边缘检测成功，正在透视矫正..." to
                        scanner.perspectiveTransform(bitmap, corners)
                } else {
                    null to bitmap  // 通知降级
                }
            } catch (e: Exception) {
                // 检测失败，做基础增强降级
                null to ImageProcessor(context).let { proc ->
                    proc.adjustContrast(proc.adjustBrightness(bitmap, 1.1f), 1.15f)
                }
            }
        }

        val (note, result) = corrected
        if (note == null) {
            onFallback(result)
        } else {
            onSuccess(result, note)
        }
    } catch (e: Exception) {
        onError("文档矫正失败: ${e.message}")
    } finally {
        onFinish()
    }
}

/**
 * 滤镜预览
 */
private suspend fun runFilterPreview(
    context: Context,
    source: Bitmap,
    filter: ScanFilterType,
    onStart: () -> Unit,
    onSuccess: (Bitmap) -> Unit,
    onError: (String) -> Unit,
    onFinish: () -> Unit
) {
    onStart()
    try {
        val result = withContext(Dispatchers.Default) {
            val filtered = ScanImageProcessor.apply(filter, source)
            ImageProcessor(context).autoCrop(filtered)
        }
        onSuccess(result)
    } catch (e: Exception) {
        onError("预览失败: ${e.message}")
    } finally {
        onFinish()
    }
}

/**
 * 保存扫描文档
 */
private suspend fun runSave(
    context: Context,
    source: Bitmap,
    filter: ScanFilterType,
    format: String,
    onStart: () -> Unit,
    onSuccess: (String) -> Unit,
    onError: (String) -> Unit,
    onFinish: () -> Unit
) {
    onStart()
    try {
        val path = withContext(Dispatchers.Default) {
            // 先应用滤镜
            val filtered = ScanImageProcessor.apply(filter, source)
            val cropped = ImageProcessor(context).autoCrop(filtered)
            // 保存
            saveScannedDocument(context, cropped, format, filter.raw)
        }
        onSuccess(path)
    } catch (e: Exception) {
        onError("保存失败: ${e.message}")
    } finally {
        onFinish()
    }
}

/**
 * 计算四边形面积
 */
private fun computeQuadArea(corners: List<PointF>): Float {
    if (corners.size != 4) return 0f
    val (tl, tr, bl, br) = corners.let { listOf(it[0], it[1], it[2], it[3]) }
    val area1 = kotlin.math.abs(
        tl.x * (tr.y - br.y) + tr.x * (br.y - tl.y) + br.x * (tl.y - tr.y)
    ) / 2f
    val area2 = kotlin.math.abs(
        tl.x * (bl.y - br.y) + bl.x * (br.y - tl.y) + br.x * (tl.y - bl.y)
    ) / 2f
    return area1 + area2
}

/**
 * 保存扫描文档（使用 ImageProcessor 或 PdfExporter）
 */
private fun saveScannedDocument(
    context: Context,
    bitmap: Bitmap,
    format: String,
    filterName: String
): String {
    val timestamp = System.currentTimeMillis()
    val fileName = "scan_${filterName}_$timestamp"

    return if (format == "pdf") {
        PdfExporter(context).exportToPdf(bitmap, fileName) ?: throw Exception("PDF 导出失败")
    } else {
        ImageProcessor(context).saveToGallery(
            bitmap = bitmap,
            fileName = fileName,
            format = Bitmap.CompressFormat.JPEG,
            quality = 85
        ) ?: throw Exception("图片保存失败")
    }
}
