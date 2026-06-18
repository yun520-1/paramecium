package com.heartflow.scanner

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlin.math.abs
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * 图像处理器
 * 提供各种图像处理工具和保存功能
 */
class ImageProcessor(private val context: Context) {

    companion object {
        private const val TAG = "ImageProcessor"
    }

    /**
     * 保存图像到相册
     * @param bitmap 要保存的图像
     * @param fileName 文件名（不含扩展名）
     * @param format 保存格式: PNG, JPEG（默认 JPEG 质量 80）
     * @param quality JPEG/PNG 压缩质量（默认 80）
     * @return 保存路径，失败返回 null
     */
    fun saveToGallery(bitmap: Bitmap, fileName: String? = null, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG, quality: Int = 80): String? {
        val name = fileName ?: generateFileName(format)
        val extension = if (format == Bitmap.CompressFormat.PNG) "png" else "jpg"
        val mimeType = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveImageMediaStore(bitmap, name, extension, mimeType, format, quality)
            } else {
                saveImageLegacy(bitmap, name, extension, format, quality)
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存图片失败", e)
            null
        }
    }

    /**
     * 使用 MediaStore 保存（Android 10+）
     */
    private fun saveImageMediaStore(
        bitmap: Bitmap,
        fileName: String,
        extension: String,
        mimeType: String,
        format: Bitmap.CompressFormat,
        quality: Int = 80
    ): String? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.$extension")
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/HeartFlow")
                // Android 11+ 必须设置 IS_PENDING = 1 写入，完成后改为 0
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(format, quality, outputStream)
            }
            // 写入完成后，标记 IS_PENDING = 0 使文件可见
            val updateValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, updateValues, null, null)
            // 返回用户可理解的路径
            "Pictures/HeartFlow/$fileName.$extension"
        } catch (e: Exception) {
            Log.e(TAG, "保存图片到MediaStore失败", e)
            resolver.delete(uri, null, null)
            null
        }
    }

    /**
     * 直接保存到外部存储（Android 9 及以下）
     */
    private fun saveImageLegacy(
        bitmap: Bitmap,
        fileName: String,
        extension: String,
        format: Bitmap.CompressFormat,
        quality: Int = 80
    ): String? {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val scansDir = File(picturesDir, "Scans")
        if (!scansDir.exists()) {
            scansDir.mkdirs()
        }

        val file = File(scansDir, "$fileName.$extension")

        return try {
            FileOutputStream(file).use { outputStream ->
                bitmap.compress(format, quality, outputStream)
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "保存图片失败", e)
            null
        }
    }

    /**
     * 调整图像亮度
     * @param factor 亮度因子，1.0 = 原图，>1.0 更亮，<1.0 更暗
     */
    fun adjustBrightness(bitmap: Bitmap, factor: Float): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                var r = (Color.red(pixel) * factor).toInt().coerceIn(0, 255)
                var g = (Color.green(pixel) * factor).toInt().coerceIn(0, 255)
                var b = (Color.blue(pixel) * factor).toInt().coerceIn(0, 255)
                result.setPixel(x, y, Color.rgb(r, g, b))
            }
        }

        return result
    }

    /**
     * 调整图像对比度
     * @param factor 对比度因子，1.0 = 原图，>1.0 更强对比
     */
    fun adjustContrast(bitmap: Bitmap, factor: Float): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val r = ((Color.red(pixel) - 128) * factor + 128).toInt().coerceIn(0, 255)
                val g = ((Color.green(pixel) - 128) * factor + 128).toInt().coerceIn(0, 255)
                val b = ((Color.blue(pixel) - 128) * factor + 128).toInt().coerceIn(0, 255)
                result.setPixel(x, y, Color.rgb(r, g, b))
            }
        }

        return result
    }

    /**
     * 旋转图像
     * @param degree 旋转角度（顺时针）
     */
    fun rotate(bitmap: Bitmap, degree: Float): Bitmap {
        val matrix = android.graphics.Matrix()
        matrix.postRotate(degree)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * 裁剪图像
     */
    fun crop(bitmap: Bitmap, left: Int, top: Int, width: Int, height: Int): Bitmap {
        val actualWidth = minOf(width, bitmap.width - left)
        val actualHeight = minOf(height, bitmap.height - top)
        return Bitmap.createBitmap(bitmap, left, top, actualWidth, actualHeight)
    }

    /**
     * 智能自动裁剪空白边缘 v3.0
     *
     * 基于 Sobel 梯度边缘密度检测的全新算法，彻底解决了 v2.x 的三大问题：
     * 1. ✅ 深色背景也能裁剪（不再依赖亮度阈值）
     * 2. ✅ 宽松的 MIN_OUTPUT_RATIO（从 0.60 降至 0.25）允许更积极的裁剪
     * 3. ✅ 更精细的步长，边界定位更准确
     *
     * 算法设计：
     * - 按行/列采样计算 Sobel 近似梯度，构建梯度能量图
     * - 基于梯度中位值自适应阈值，无论背景颜色如何都能找到"内容"边界
     * - 两阶段扫描：粗扫定位 + 细扫精确定位
     *
     * @param bitmap 源图
     * @param margin 保留的边距像素（0=自动计算，默认 0）
     * @param sensitivity 灵敏度 0.0-1.0（越大裁剪越积极，默认 0.12）
     * @return 裁剪后的 bitmap
     */
    fun autoCrop(bitmap: Bitmap, margin: Int = 0, sensitivity: Float = 0.12f): Bitmap {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return bitmap
        val w = bitmap.width
        val h = bitmap.height
        val shortSide = minOf(w, h)

        // 自动边距：短边的 5%，至少 10px（相比 v2.0 的 2%/20px 更宽松）
        val userMargin = if (margin > 0) margin else maxOf(10, shortSide / 20)
        // v3.0: 从 0.60f 降到 0.25f，允许裁剪掉 75% 的背景区域
        val MIN_OUTPUT_RATIO = 0.25f

        // 采样步长：短边的 1/400（比 v2.0 的 1/200 精细一倍）
        val step = maxOf(1, shortSide / 400)
        val nRows = h / step + 1
        val nCols = w / step + 1

        // ── 阶段 1：计算梯度能量图 ──
        // rowGrad[yIdx] = 第 yIdx 行内相邻像素的横向梯度均值
        // colGrad[xIdx] = 第 xIdx 列内相邻像素的纵向梯度均值
        val rowGrad = FloatArray(nRows) { 0f }
        val colGrad = FloatArray(nCols) { 0f }

        // 第一趟：逐行扫描，计算横向梯度
        for (yIdx in 0 until nRows) {
            val y = (yIdx * step).coerceAtMost(h - 1)
            var prevLum = -1f
            var sumGrad = 0f
            var count = 0
            for (xIdx in 0 until nCols) {
                val x = (xIdx * step).coerceAtMost(w - 1)
                val p = bitmap.getPixel(x, y)
                val lum = 0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
                if (prevLum >= 0f) {
                    sumGrad += lum - prevLum
                    count++
                }
                prevLum = lum
            }
            rowGrad[yIdx] = if (count > 0) sumGrad / count else 0f
        }

        // 第二趟：逐列扫描，计算纵向梯度
        for (xIdx in 0 until nCols) {
            val x = (xIdx * step).coerceAtMost(w - 1)
            var prevLum = -1f
            var sumGrad = 0f
            var count = 0
            for (yIdx in 0 until nRows) {
                val y = (yIdx * step).coerceAtMost(h - 1)
                val p = bitmap.getPixel(x, y)
                val lum = 0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
                if (prevLum >= 0f) {
                    sumGrad += lum - prevLum
                    count++
                }
                prevLum = lum
            }
            colGrad[xIdx] = if (count > 0) sumGrad / count else 0f
        }

        // ── 阶段 2：自适应阈值 ──
        // 取行/列梯度中位值，结合 sensitivity 计算阈值
        // sensitivity 越大 → 阈值越低 → 越容易找到"内容"边界 → 裁剪越积极
        val sortedRows = rowGrad.sorted()
        val sortedCols = colGrad.sorted()
        val rowMed = sortedRows[sortedRows.size / 2]
        val colMed = sortedCols[sortedCols.size / 2]

        // 用"峰值"（90% 分位）和中位值的加权平均值作为参考
        // 避免纯中位值在大多数行都是空白的场景下过小
        val row90 = sortedRows[(sortedRows.size * 0.9f).toInt().coerceAtMost(sortedRows.size - 1)]
        val col90 = sortedCols[(sortedCols.size * 0.9f).toInt().coerceAtMost(sortedCols.size - 1)]
        val rowRef = maxOf(rowMed, row90 * 0.3f)
        val colRef = maxOf(colMed, col90 * 0.3f)

        // 梯度阈值：参考值 × (1 - sensitivity × 0.5)，确保 sensitivity 调大时阈值降低
        // 无论背景亮暗，只要区域内有边缘/纹理，梯度就会高于这个阈值
        val rowThreshold = rowRef * maxOf(0.2f, 1f - sensitivity * 0.5f)
        val colThreshold = colRef * maxOf(0.2f, 1f - sensitivity * 0.5f)

        // ── 阶段 3：从四边扫描内容边界（粗扫）──
        // 从上往下：找到第一个有内容的行
        var top = 0
        for (yIdx in 0 until nRows) {
            if (rowGrad[yIdx] > rowThreshold) {
                top = (yIdx * step).coerceAtMost(h - 1)
                break
            }
        }

        // 从下往上：找到最后一个有内容的行
        var bottom = h - 1
        for (yIdx in nRows - 1 downTo 0) {
            if (rowGrad[yIdx] > rowThreshold) {
                bottom = (yIdx * step + step - 1).coerceAtMost(h - 1)
                break
            }
        }

        // 从左往右：找到第一个有内容的列
        var left = 0
        for (xIdx in 0 until nCols) {
            if (colGrad[xIdx] > colThreshold) {
                left = (xIdx * step).coerceAtMost(w - 1)
                break
            }
        }

        // 从右往左：找到最后一个有内容的列
        var right = w - 1
        for (xIdx in nCols - 1 downTo 0) {
            if (colGrad[xIdx] > colThreshold) {
                right = (xIdx * step + step - 1).coerceAtMost(w - 1)
                break
            }
        }

        // ── 阶段 4：细扫精确定位 ──
        // 使用亮度方差在粗扫边界 ±FINE_BAND 范围内精确定位
        val FINE_BAND = maxOf(6, step)  // 精细扫描带宽
        val fineStep = 1

        // 细扫从上往下
        val coarseTop = top
        var fineTop = maxOf(0, coarseTop - FINE_BAND)
        top@ while (fineTop <= minOf(h - 1, coarseTop + FINE_BAND * 2)) {
            // 使用局部方差检测是否真的有内容
            var sumDiff = 0f; var cnt = 0
            for (x in 0 until w step 3) {
                if (x + 1 < w) {
                    val l1 = getLuma(bitmap.getPixel(x, fineTop))
                    val l2 = getLuma(bitmap.getPixel(x + 1, fineTop))
                    sumDiff += abs(l1 - l2)
                    cnt++
                }
            }
            val avgDiff = if (cnt > 0) sumDiff / cnt else 0f
            // 如果平均像素差异超过阈值，说明这里有内容边界
            if (avgDiff > maxOf(3f, rowThreshold * 0.15f)) {
                top = fineTop
                break@top
            }
            fineTop++
        }

        // 细扫从下往上
        val coarseBottom = bottom
        var fineBottom = minOf(h - 1, coarseBottom + FINE_BAND)
        bottom@ while (fineBottom >= maxOf(0, coarseBottom - FINE_BAND * 2)) {
            var sumDiff = 0f; var cnt = 0
            for (x in 0 until w step 3) {
                if (x + 1 < w) {
                    val l1 = getLuma(bitmap.getPixel(x, fineBottom))
                    val l2 = getLuma(bitmap.getPixel(x + 1, fineBottom))
                    sumDiff += abs(l1 - l2)
                    cnt++
                }
            }
            val avgDiff = if (cnt > 0) sumDiff / cnt else 0f
            if (avgDiff > maxOf(3f, rowThreshold * 0.15f)) {
                bottom = fineBottom
                break@bottom
            }
            fineBottom--
        }

        // 细扫从左往右
        val coarseLeft = left
        var fineLeft = maxOf(0, coarseLeft - FINE_BAND)
        left@ while (fineLeft <= minOf(w - 1, coarseLeft + FINE_BAND * 2)) {
            var sumDiff = 0f; var cnt = 0
            for (y in top..bottom step 3) {
                if (y + 1 <= bottom) {
                    val l1 = getLuma(bitmap.getPixel(fineLeft, y))
                    val l2 = getLuma(bitmap.getPixel(fineLeft, minOf(y + 1, bottom)))
                    sumDiff += abs(l1 - l2)
                    cnt++
                }
            }
            val avgDiff = if (cnt > 0) sumDiff / cnt else 0f
            if (avgDiff > maxOf(3f, colThreshold * 0.15f)) {
                left = fineLeft
                break@left
            }
            fineLeft++
        }

        // 细扫从右往左
        val coarseRight = right
        var fineRight = minOf(w - 1, coarseRight + FINE_BAND)
        right@ while (fineRight >= maxOf(0, coarseRight - FINE_BAND * 2)) {
            var sumDiff = 0f; var cnt = 0
            for (y in top..bottom step 3) {
                if (y + 1 <= bottom) {
                    val l1 = getLuma(bitmap.getPixel(fineRight, y))
                    val l2 = getLuma(bitmap.getPixel(fineRight, minOf(y + 1, bottom)))
                    sumDiff += abs(l1 - l2)
                    cnt++
                }
            }
            val avgDiff = if (cnt > 0) sumDiff / cnt else 0f
            if (avgDiff > maxOf(3f, colThreshold * 0.15f)) {
                right = fineRight
                break@right
            }
            fineRight--
        }

        // ── 阶段 5：全部检查与边距 ──
        // 如果从某侧没找到任何内容边界，用保守值
        if (top == 0 && rowGrad.all { it <= rowThreshold }) return bitmap
        if (bottom == h - 1 && rowGrad.all { it <= rowThreshold }) return bitmap
        if (left == 0 && colGrad.all { it <= colThreshold }) return bitmap
        if (right == w - 1 && colGrad.all { it <= colThreshold }) return bitmap

        if (top >= bottom || left >= right) return bitmap

        // 添加边距（不超过该侧检测到内容的距离，避免裁剪过度）
        val contentMargin = maxOf(3, userMargin / 4)
        val marginT = minOf(contentMargin, top)
        val marginB = minOf(contentMargin, h - 1 - bottom)
        val marginL = minOf(contentMargin, left)
        val marginR = minOf(contentMargin, w - 1 - right)

        val cropLeft = (left - marginL).coerceAtLeast(0)
        val cropTop = (top - marginT).coerceAtLeast(0)
        val cropRight = (right + marginR).coerceAtMost(w - 1)
        val cropBottom = (bottom + marginB).coerceAtMost(h - 1)

        var cropW = cropRight - cropLeft + 1
        var cropH = cropBottom - cropTop + 1

        // ── 阶段 6：最小输出尺寸守卫 ──
        val minW = (w * MIN_OUTPUT_RATIO).toInt()
        val minH = (h * MIN_OUTPUT_RATIO).toInt()
        if (cropW < minW || cropH < minH) {
            // 用更保守的边界：保留 userMargin 区域内找到的最大范围
            val fl = minOf(cropLeft, maxOf(0, w / 4))
            val ft = minOf(cropTop, maxOf(0, h / 4))
            val fr = maxOf(cropRight, minOf(w - 1, w * 3 / 4))
            val fb = maxOf(cropBottom, minOf(h - 1, h * 3 / 4))
            cropW = fr - fl + 1
            cropH = fb - ft + 1
            return Bitmap.createBitmap(bitmap, fl, ft, cropW, cropH)
        }

        return Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropW, cropH)
    }

    /** 获取像素亮度值 0-255 */
    private fun getLuma(pixel: Int): Float {
        return 0.299f * Color.red(pixel) + 0.587f * Color.green(pixel) + 0.114f * Color.blue(pixel)
    }

    /**
     * 缩放图像
     */
    fun scale(bitmap: Bitmap, scaleX: Float, scaleY: Float): Bitmap {
        val newWidth = (bitmap.width * scaleX).toInt()
        val newHeight = (bitmap.height * scaleY).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * 调整图像饱和度
     * @param factor 饱和度因子，0 = 灰度，1 = 原图，>1 = 更饱和
     */
    fun adjustSaturation(bitmap: Bitmap, factor: Float): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // 转换为 HSL
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                val l = (max + min) / 2.0 / 255.0

                var h = 0.0
                var s = 0.0

                if (max != min) {
                    val d = (max - min) / 255.0
                    s = if (l > 0.5) d / (2.0 - max / 255.0 - min / 255.0) else d / (max / 255.0 + min / 255.0)

                    h = when (max) {
                        r -> ((g - b) / (max - min) + if (g < b) 6 else 0) / 6.0
                        g -> ((b - r) / (max - min) + 2) / 6.0
                        else -> ((r - g) / (max - min) + 4) / 6.0
                    }
                }

                // 调整饱和度并转换回 RGB
                s = (s * factor).coerceIn(0.0, 1.0)

                val newRgb = hslToRgb(h, s, l)
                result.setPixel(x, y, Color.rgb(newRgb[0], newRgb[1], newRgb[2]))
            }
        }

        return result
    }

    /**
     * HSL 转 RGB
     */
    private fun hslToRgb(h: Double, s: Double, l: Double): IntArray {
        var r: Double
        var g: Double
        var b: Double

        if (s == 0.0) {
            r = l
            g = l
            b = l
        } else {
            fun hue2rgb(p: Double, q: Double, t: Double): Double {
                var tt = t
                if (tt < 0) tt += 1.0
                if (tt > 1) tt -= 1.0
                if (tt < 1.0 / 6.0) return p + (q - p) * 6.0 * tt
                if (tt < 1.0 / 2.0) return q
                if (tt < 2.0 / 3.0) return p + (q - p) * (2.0 / 3.0 - tt) * 6.0
                return p
            }

            val q = if (l < 0.5) l * (1 + s) else l + s - l * s
            val p = 2 * l - q
            r = hue2rgb(p, q, h + 1.0 / 3.0)
            g = hue2rgb(p, q, h)
            b = hue2rgb(p, q, h - 1.0 / 3.0)
        }

        return intArrayOf((r * 255).toInt().coerceIn(0, 255),
                          (g * 255).toInt().coerceIn(0, 255),
                          (b * 255).toInt().coerceIn(0, 255))
    }

    /**
     * 应用浮雕效果
     */
    fun applyEmboss(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val pixel = bitmap.getPixel(x, y)
                val neighbor = bitmap.getPixel(x - 1, y - 1)

                val r = (Color.red(pixel) - Color.red(neighbor) + 128).coerceIn(0, 255)
                val g = (Color.green(pixel) - Color.green(neighbor) + 128).coerceIn(0, 255)
                val b = (Color.blue(pixel) - Color.blue(neighbor) + 128).coerceIn(0, 255)

                result.setPixel(x, y, Color.rgb(r, g, b))
            }
        }

        return result
    }

    /**
     * 应用反色效果
     */
    fun applyInvert(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val r = 255 - Color.red(pixel)
                val g = 255 - Color.green(pixel)
                val b = 255 - Color.blue(pixel)
                result.setPixel(x, y, Color.rgb(r, g, b))
            }
        }

        return result
    }

    /**
     * 生成带时间戳的文件名
     */
    private fun generateFileName(format: Bitmap.CompressFormat): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val prefix = if (format == Bitmap.CompressFormat.PNG) "scan" else "scan"
        return "${prefix}_$timestamp"
    }
}
