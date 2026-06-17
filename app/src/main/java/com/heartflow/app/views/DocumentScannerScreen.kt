package com.heartflow.app.views

import android.graphics.Bitmap
import android.net.Uri
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
import com.heartflow.scanner.ImageProcessor
import com.heartflow.app.model.ScanFilterType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 专业级文档扫描页面
 *
 * 核心功能：
 * 1. 从相册选择图片
 * 2. 8种专业级扫描效果（超越夸克扫描王）
 * 3. 原图/处理后对比预览
 * 4. 导出为PNG或PDF
 *
 * 滤镜算法基于 iOS Core Image 移植：
 * - 自动增强：直方图1%/99%截断拉伸 + 饱和度提升
 * - 文档增强：亮度+0.12、对比度+1.45、阴影提升
 * - 文字锐化：USM(unsharp mask) 锐化
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScannerScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 状态
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var processedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(ScanFilterType.AUTO_ENHANCE) }
    var selectedFormat by remember { mutableStateOf("jpg") }
    var saveResult by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 图片选择器
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    val bitmap = withContext(Dispatchers.IO) {
                        loadBitmapFromUri(context, it)
                    }
                    if (bitmap != null) {
                        selectedBitmap = bitmap
                        processedBitmap = null
                        saveResult = null
                        errorMessage = null
                    } else {
                        errorMessage = "无法加载图片"
                    }
                } catch (e: Exception) {
                    errorMessage = "加载失败: ${e.message}"
                }
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
                actions = {
                    // 帮助按钮
                    IconButton(onClick = {
                        Toast.makeText(context, "选择图片后，点击滤镜预览效果，再点击保存", Toast.LENGTH_LONG).show()
                    }) {
                        Icon(Icons.Default.HelpOutline, "帮助")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
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
            // 功能说明
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
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "专业扫描：8种增强效果，超越夸克扫描王",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }

            // 选择图片按钮
            Button(
                onClick = { imagePickerLauncher.launch("image/*") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("从相册选择图片")
            }

            // 错误提示
            AnimatedVisibility(
                visible = errorMessage != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(errorMessage ?: "", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // 图片预览区
            if (selectedBitmap != null) {
                // 8种滤镜选择器
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("扫描效果", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "点击选择不同效果，预览对比",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(8.dp))

                        // 横向滚动的滤镜列表
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
                                    onClick = { selectedFilter = filter }
                                )
                            }
                        }
                    }
                }

                // 格式选择
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
                            onClick = { selectedFormat = "jpg" },
                            label = { Text("JPEG图片") },
                            leadingIcon = {
                                Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                            }
                        )

                        FilterChip(
                            selected = selectedFormat == "pdf",
                            onClick = { selectedFormat = "pdf" },
                            label = { Text("PDF文档") },
                            leadingIcon = {
                                Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                            }
                        )
                    }
                }

                // 预览区
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 原图预览
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "原图",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(4.dp)
                            )
                            Image(
                                bitmap = selectedBitmap!!.asImageBitmap(),
                                contentDescription = "原图",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(4.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }

                    // 处理后预览
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                selectedFilter.displayName,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(4.dp)
                            )
                            if (processedBitmap != null) {
                                Image(
                                    bitmap = processedBitmap!!.asImageBitmap(),
                                    contentDescription = "处理后",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .padding(4.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .padding(4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                                }
                            }
                        }
                    }
                }

                // 操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重选")
                    }

                    // 预览按钮
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isProcessing = true
                                try {
                                    val result = withContext(Dispatchers.Default) {
                                        val filtered = ScanImageProcessor.apply(selectedFilter, selectedBitmap!!)
                                        // 自动裁剪空白边缘
                                        ImageProcessor(context).autoCrop(filtered)
                                    }
                                    processedBitmap = result
                                } catch (e: Exception) {
                                    errorMessage = "预览失败: ${e.message}"
                                } finally {
                                    isProcessing = false
                                }
                            }
                        },
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("预览效果")
                    }

                    // 保存按钮
                    Button(
                        onClick = {
                            scope.launch {
                                isProcessing = true
                                errorMessage = null
                                try {
                                    // 先处理（滤镜+自动裁剪）
                                    val processed = withContext(Dispatchers.Default) {
                                        val filtered = ScanImageProcessor.apply(selectedFilter, selectedBitmap!!)
                                        // 自动裁剪空白边缘
                                        ImageProcessor(context).autoCrop(filtered)
                                    }
                                    // 保存
                                    val path = withContext(Dispatchers.IO) {
                                        saveScannedDocument(context, processed, selectedFormat, selectedFilter.raw)
                                    }
                                    processedBitmap = processed
                                    saveResult = path
                                    Toast.makeText(context, "✅ 已保存至: $path", Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    errorMessage = "保存失败: ${e.message}"
                                } finally {
                                    isProcessing = false
                                }
                            }
                        },
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("保存")
                    }
                }

                // 保存结果
                AnimatedVisibility(
                    visible = saveResult != null && !isProcessing,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "已保存: ${saveResult ?: ""}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            } else {
                // 空状态提示
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
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
                                "支持8种专业增强效果",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 滤镜选择项组件
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
                if (isSelected) {
                    Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(8.dp)
                    )
                } else Modifier
            )
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    when (filter) {
                        ScanFilterType.ORIGINAL -> Color.Gray
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
                tint = if (filter == ScanFilterType.WHITE_DOCUMENT || filter == ScanFilterType.BLACK_AND_WHITE)
                    Color.White else Color.White.copy(alpha = 0.8f),
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

// ── 辅助函数 ────────────────────────────────────────────────────────────────

/**
 * 从 URI 加载 Bitmap
 */
private fun loadBitmapFromUri(context: android.content.Context, uri: Uri): Bitmap? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            android.graphics.BitmapFactory.decodeStream(inputStream)
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * 保存扫描文档
 */
private fun saveScannedDocument(
    context: android.content.Context,
    bitmap: Bitmap,
    format: String,
    filterName: String
): String {
    val timestamp = System.currentTimeMillis()
    val fileName = "scan_${filterName}_$timestamp"

    return if (format == "pdf") {
        saveAsPdf(context, bitmap, fileName)
    } else {
        saveAsJpeg(context, bitmap, fileName)
    }
}

/**
 * 扫描保存方法（MediaStore 方式，兼容 Android 10+）
 * 使用 JPEG 格式 + 质量 80，大幅减小文件体积
 */
private fun saveAsJpeg(context: android.content.Context, bitmap: Bitmap, fileName: String): String {
    val extension = "jpg"
    val mimeType = "image/jpeg"
    val quality = 80
    val relativePath = "${android.os.Environment.DIRECTORY_PICTURES}/HeartFlow"

    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        // Android 10+ 使用 MediaStore，确保相册可见
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$fileName.$extension")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = context.contentResolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        ) ?: throw Exception("无法创建 MediaStore 条目")

        try {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, outputStream)
            }
            // 写入完成后清除 IS_PENDING，使媒体扫描器可见
            val pendingValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, pendingValues, null, null)
            // 触发媒体扫描器立即扫描
            val fileUri = android.net.Uri.parse(uri.toString())
            val scanIntent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, fileUri)
            context.sendBroadcast(scanIntent)
            return "$relativePath/$fileName.$extension"
        } catch (e: Exception) {
            // 写入失败时删除临时的 MediaStore 条目
            context.contentResolver.delete(uri, null, null)
            throw e
        }
    } else {
        // Android 9 及以下使用传统文件 API
        val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)
        val heartflowDir = java.io.File(dir, "HeartFlow")
        if (!heartflowDir.exists()) heartflowDir.mkdirs()
        val file = java.io.File(heartflowDir, "$fileName.$extension")
        java.io.FileOutputStream(file).use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
        }
        // 发送广播通知媒体扫描器
        val scanIntent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
            android.net.Uri.fromFile(file))
        context.sendBroadcast(scanIntent)
        return file.absolutePath
    }
}

/**
 * 保存为 PDF（使用 MediaStore）
 */
private fun saveAsPdf(context: android.content.Context, bitmap: Bitmap, fileName: String): String {
    val extension = "pdf"
    val mimeType = "application/pdf"
    val relativePath = "${android.os.Environment.DIRECTORY_DOCUMENTS}/HeartFlow"

    val outputUri: android.net.Uri
    val outputPath: String

    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME, "$fileName.$extension")
            put(android.provider.MediaStore.Files.FileColumns.MIME_TYPE, mimeType)
            put(android.provider.MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath)
            put(android.provider.MediaStore.Files.FileColumns.IS_PENDING, 1)
        }

        outputUri = context.contentResolver.insert(
            android.provider.MediaStore.Files.getContentUri("external"), contentValues
        ) ?: throw Exception("无法创建 MediaStore 条目")

        outputPath = "$relativePath/$fileName.$extension"
    } else {
        val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
        val heartflowDir = java.io.File(dir, "HeartFlow")
        if (!heartflowDir.exists()) heartflowDir.mkdirs()
        val file = java.io.File(heartflowDir, "$fileName.$extension")
        outputUri = android.net.Uri.fromFile(file)
        outputPath = file.absolutePath
    }

    try {
        val pdfDocument = android.graphics.pdf.PdfDocument()
        val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(
            bitmap.width, bitmap.height, 1
        ).create()
        val page = pdfDocument.startPage(pageInfo)
        page.canvas.drawBitmap(bitmap, 0f, 0f, null)
        pdfDocument.finishPage(page)

        context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
            pdfDocument.writeTo(outputStream)
        } ?: throw Exception("无法打开输出流")

        pdfDocument.close()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val pendingValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Files.FileColumns.IS_PENDING, 0)
            }
            context.contentResolver.update(outputUri, pendingValues, null, null)
        }

        return outputPath
    } catch (e: Exception) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            outputUri.toString().startsWith("content://")) {
            context.contentResolver.delete(outputUri, null, null)
        }
        throw e
    }
}
