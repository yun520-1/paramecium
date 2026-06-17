package com.heartflow.scanner

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * PDF 导出器
 * 将图像导出为 PDF 文档
 */
class PdfExporter(private val context: Context) {

    companion object {
        private const val TAG = "PdfExporter"
        private const val PDF_QUALITY = 100
    }

    /**
     * 将单张图像导出为 PDF
     * @param bitmap 要导出的图像
     * @param fileName 文件名（不含扩展名）
     * @return 保存路径，失败返回 null
     */
    fun exportToPdf(bitmap: Bitmap, fileName: String? = null): String? {
        val name = fileName ?: generateFileName("pdf")

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用 MediaStore
                exportToPdfMediaStore(bitmap, name)
            } else {
                // Android 9 及以下使用直接文件写入
                exportToPdfLegacy(bitmap, name)
            }
        } catch (e: Exception) {
            Log.e(TAG, "导出PDF失败", e)
            null
        }
    }

    /**
     * 将多张图像导出为多页 PDF
     * @param images 要导出的图像列表
     * @param fileName 文件名（不含扩展名）
     * @return 保存路径，失败返回 null
     */
    fun exportMultiplePages(images: List<Bitmap>, fileName: String? = null): String? {
        if (images.isEmpty()) return null

        val name = fileName ?: generateFileName("pdf")

        return try {
            val pdfDocument = PdfDocument()

            images.forEachIndexed { index, bitmap ->
                // 创建页面
                val pageInfo = PdfDocument.PageInfo.Builder(
                    bitmap.width,
                    bitmap.height,
                    index + 1
                ).create()

                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                // 绘制图像
                canvas.drawBitmap(bitmap, 0f, 0f, null)

                pdfDocument.finishPage(page)
            }

            // 保存
            val outputPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                savePdfMediaStore(pdfDocument, name)
            } else {
                savePdfLegacy(pdfDocument, name)
            }

            pdfDocument.close()
            outputPath

        } catch (e: Exception) {
            Log.e(TAG, "导出多页PDF失败", e)
            null
        }
    }

    /**
     * Android 10+ 使用 MediaStore 保存
     */
    private fun exportToPdfMediaStore(bitmap: Bitmap, fileName: String): String? {
        val pdfDocument = PdfDocument()

        // 创建页面（使用A4比例）
        val pageWidth = 595  // A4 width in points (72 dpi)
        val pageHeight = 842 // A4 height in points

        // 计算缩放比例以适应页面
        val scaleX = pageWidth.toFloat() / bitmap.width
        val scaleY = pageHeight.toFloat() / bitmap.height
        val scale = minOf(scaleX, scaleY)

        val scaledWidth = (bitmap.width * scale).toInt()
        val scaledHeight = (bitmap.height * scale).toInt()

        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = pdfDocument.startPage(pageInfo)

        val canvas = page.canvas

        // 居中绘制
        val left = (pageWidth - scaledWidth) / 2f
        val top = (pageHeight - scaledHeight) / 2f

        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        canvas.drawBitmap(scaledBitmap, left, top, null)

        pdfDocument.finishPage(page)

        val result = savePdfMediaStore(pdfDocument, fileName)
        pdfDocument.close()

        return result
    }

    /**
     * Android 9 及以下直接保存文件
     */
    private fun exportToPdfLegacy(bitmap: Bitmap, fileName: String): String? {
        val pdfDocument = PdfDocument()

        val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
        val page = pdfDocument.startPage(pageInfo)

        val canvas = page.canvas
        canvas.drawBitmap(bitmap, 0f, 0f, null)

        pdfDocument.finishPage(page)

        val result = savePdfLegacy(pdfDocument, fileName)
        pdfDocument.close()

        return result
    }

    /**
     * 使用 MediaStore 保存 PDF（Android 10+）
     */
    private fun savePdfMediaStore(pdfDocument: PdfDocument, fileName: String): String? {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$fileName.pdf")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/Scans")
                put(MediaStore.Files.FileColumns.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
            ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                pdfDocument.writeTo(outputStream)
            }
            // Android 10+ 清除 IS_PENDING 使文件可见
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pendingValues = ContentValues().apply {
                    put(MediaStore.Files.FileColumns.IS_PENDING, 0)
                }
                resolver.update(uri, pendingValues, null, null)
            }
            "$fileName.pdf"
        } catch (e: Exception) {
            Log.e(TAG, "保存PDF到MediaStore失败", e)
            resolver.delete(uri, null, null)
            null
        }
    }

    /**
     * 直接保存 PDF 到外部存储（Android 9 及以下）
     */
    private fun savePdfLegacy(pdfDocument: PdfDocument, fileName: String): String? {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val scansDir = File(downloadsDir, "Scans")
        if (!scansDir.exists()) {
            scansDir.mkdirs()
        }

        val file = File(scansDir, "$fileName.pdf")

        return try {
            FileOutputStream(file).use { outputStream ->
                pdfDocument.writeTo(outputStream)
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "保存PDF失败", e)
            null
        }
    }

    /**
     * 生成带时间戳的文件名
     */
    private fun generateFileName(prefix: String): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return "${prefix}_$timestamp"
    }
}
