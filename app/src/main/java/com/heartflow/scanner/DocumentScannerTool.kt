package com.heartflow.scanner

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * 文档扫描工具
 * 提供图片选择、扫描处理、导出功能
 */
object DocumentScannerTool {

    private const val TAG = "DocumentScannerTool"

    // 扫描状态
    private val _scanState = mutableStateOf<ScanState>(ScanState.Idle)
    val scanState: MutableState<ScanState> = _scanState

    // 扫描结果
    private val _scanResult = mutableStateOf<ScanResult?>(null)
    val scanResult: MutableState<ScanResult?> = _scanResult

    /**
     * 扫描状态
     */
    sealed class ScanState {
        object Idle : ScanState()
        object Selecting : ScanState()
        object Processing : ScanState()
        object Success : ScanState()
        data class Error(val message: String) : ScanState()
    }

    /**
     * 扫描结果
     */
    data class ScanResult(
        val originalBitmap: Bitmap,
        val scannedBitmap: Bitmap,
        val outputPath: String?,
        val format: String
    )

    /**
     * 从 URI 加载 Bitmap
     */
    fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            val contentResolver: ContentResolver = context.contentResolver

            // 首先获取图片尺寸
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            // 计算采样率
            val maxSize = 2048
            var sampleSize = 1
            while (options.outWidth / sampleSize > maxSize || options.outHeight / sampleSize > maxSize) {
                sampleSize *= 2
            }

            // 加载缩小后的图片
            val loadOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, loadOptions)
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载图片失败", e)
            null
        }
    }

    /**
     * 处理扫描文档
     * @param context Android 上下文
     * @param sourceBitmap 源图片
     * @param effect 效果: original, gray, colorful
     * @param format 格式: image, pdf
     */
    fun processDocument(
        context: Context,
        sourceBitmap: Bitmap,
        effect: String = "colorful",
        format: String = "image"
    ): ScanResult {
        _scanState.value = ScanState.Processing

        return try {
            val scanner = DocumentScanner()
            val processor = ImageProcessor(context)

            // 1. 检测边缘
            val corners = scanner.detectCorners(sourceBitmap)
            Log.d(TAG, "检测到 ${corners.size} 个角点")

            // 2. 透视变换
            val transformed = if (corners.size == 4) {
                scanner.perspectiveTransform(sourceBitmap, corners)
            } else {
                sourceBitmap
            }

            // 3. 应用扫描效果
            val enhanced = scanner.applyScanEffect(transformed, effect)

            // 4. 自动裁剪空白边缘（减小文件体积）
            val scanned = processor.autoCrop(enhanced)

            // 5. 导出
            val outputPath = when (format.lowercase()) {
                "pdf" -> {
                    val exporter = PdfExporter(context)
                    exporter.exportToPdf(scanned)
                }
                else -> {
                    processor.saveToGallery(scanned)
                }
            }

            val result = ScanResult(
                originalBitmap = sourceBitmap,
                scannedBitmap = scanned,
                outputPath = outputPath,
                format = format
            )

            _scanResult.value = result
            _scanState.value = ScanState.Success

            result
        } catch (e: Exception) {
            Log.e(TAG, "扫描处理失败", e)
            _scanState.value = ScanState.Error(e.message ?: "未知错误")
            throw e
        }
    }

    /**
     * 异步处理扫描文档
     */
    fun processDocumentAsync(
        context: Context,
        sourceBitmap: Bitmap,
        effect: String = "colorful",
        format: String = "image",
        onComplete: (ScanResult) -> Unit,
        onError: (String) -> Unit
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                _scanState.value = ScanState.Processing

                val result = withContext(Dispatchers.Default) {
                    processDocument(context, sourceBitmap, effect, format)
                }

                onComplete(result)
            } catch (e: Exception) {
                val message = e.message ?: "未知错误"
                _scanState.value = ScanState.Error(message)
                onError(message)
            }
        }
    }

    /**
     * 执行扫描工具（供 AgentTools 调用）
     * 注意：此方法需要 Activity 上下文才能启动图片选择器
     */
    fun execute(
        context: Context,
        imageUri: String? = null,
        effect: String = "colorful",
        format: String = "image"
    ): String {
        // 如果提供了 URI，直接处理
        if (!imageUri.isNullOrBlank()) {
            return try {
                val uri = Uri.parse(imageUri)
                val bitmap = loadBitmapFromUri(context, uri)
                    ?: return "❌ 无法加载图片，请检查路径是否正确"

                val result = processDocument(context, bitmap, effect, format)

                val effectDesc = when (effect.lowercase()) {
                    "gray" -> "灰度"
                    "original" -> "原色增强"
                    else -> "彩色扫描"
                }

                val formatDesc = if (format.lowercase() == "pdf") "PDF" else "图片"
                val pathDesc = result.outputPath?.let { "\n📁 已保存至: $it" } ?: "\n⚠️ 保存失败"

                "✅ 扫描完成！\n🎨 效果: $effectDesc\n📄 格式: $formatDesc$pathDesc"

            } catch (e: Exception) {
                "❌ 扫描失败: ${e.message}"
            }
        }

        // 返回提示信息（需要用户手动选择图片）
        return """
📷 文档扫描功能

请在应用中点击「扫描文档」按钮选择图片。

支持的参数：
• effect: original（自动增强）/ gray（灰度）/ colorful（彩色扫描，默认）
• format: image（图片，默认）/ pdf

示例：「帮我把图片 xxx.jpg 扫描成灰度 PDF」
        """.trimIndent()
    }

    /**
     * 重置状态
     */
    fun reset() {
        _scanState.value = ScanState.Idle
        _scanResult.value = null
    }
}
