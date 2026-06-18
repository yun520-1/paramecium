package com.heartflow.scanner

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
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
     * 智能自动裁剪空白边缘
     *
     * v2.0 — 使用动态阈值 + 内容感知算法：
     * 1. 先计算全图的亮度直方图，取中位亮度作为参考
     * 2. 基于中位亮度动态计算空白阈值（比固定 240 智能得多）
     * 3. 从四边向中心扫描，找到第一个"非空白"内容边界
     * 4. 保留边距防止裁切过紧
     *
     * @param bitmap 源图
     * @param margin 保留的边距像素（防止裁切过紧）
     * @param sensitivity 灵敏度 0.0-1.0（越大越积极裁剪，默认 0.15）
     * @return 裁剪后的 bitmap
     */
    fun autoCrop(bitmap: Bitmap, margin: Int = 0, sensitivity: Float = 0.12f): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val shortSide = minOf(width, height)

        // 自动计算边距：短边的 2%，至少 20px（margin=0 时启用）
        val userMargin = if (margin > 0) margin else maxOf(20, shortSide / 50)
        val MIN_OUTPUT_RATIO = 0.60f  // 最小输出尺寸守卫

        // 计算采样步长（大图加速）
        val step = maxOf(1, shortSide / 200)

        // 1. 计算全图的亮度直方图，确定动态阈值
        val hist = IntArray(256)
        for (y in 0 until height step step) {
            for (x in 0 until width step step) {
                val p = bitmap.getPixel(x, y)
                val l = (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)).toInt().coerceIn(0, 255)
                hist[l]++
            }
        }

        // 计算累计直方图，找到中位亮度值
        val total = (height / step + 1) * (width / step + 1)
        var cumulative = 0
        var medianLuma = 128
        for (i in 0..255) {
            cumulative += hist[i]
            if (cumulative >= total / 2) { medianLuma = i; break }
        }

        // 动态阈值：中位亮度越亮，阈值越高
        val threshold = minOf(245, maxOf(140, medianLuma + 60))

        // 判定像素是否为"空白"（基于动态阈值 + RGB 方差）
        fun isBlank(pixel: Int): Boolean {
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)

            // 任一通道明显暗于阈值 → 非空白（内容/阴影）
            if (r < threshold * 0.65f || g < threshold * 0.65f || b < threshold * 0.65f) return false

            // RGB 方差（max-min）≤ 30 视为纯色/背景区域
            val minC = minOf(r, g, b)
            val maxC = maxOf(r, g, b)
            return (maxC - minC) <= 30
        }

        // 2. 从四边扫描空白边界

        // 从上往下扫描
        var top = 0
        top@ while (top < height) {
            var blankPixels = 0
            var totalCheck = 0
            for (x in 0 until width step step) {
                totalCheck++
                if (isBlank(bitmap.getPixel(x, top))) blankPixels++
            }
            if (blankPixels.toFloat() / totalCheck <= (1f - sensitivity)) break@top
            top++
        }

        // 从下往上扫描
        var bottom = height - 1
        bottom@ while (bottom > top) {
            var blankPixels = 0
            var totalCheck = 0
            for (x in 0 until width step step) {
                totalCheck++
                if (isBlank(bitmap.getPixel(x, bottom))) blankPixels++
            }
            if (blankPixels.toFloat() / totalCheck <= (1f - sensitivity)) break@bottom
            bottom--
        }

        // 从左往右扫描
        var left = 0
        left@ while (left < width) {
            var blankPixels = 0
            var totalCheck = 0
            for (y in top..bottom step step) {
                totalCheck++
                if (isBlank(bitmap.getPixel(left, y))) blankPixels++
            }
            if (blankPixels.toFloat() / totalCheck <= (1f - sensitivity)) break@left
            left++
        }

        // 从右往左扫描
        var right = width - 1
        right@ while (right > left) {
            var blankPixels = 0
            var totalCheck = 0
            for (y in top..bottom step step) {
                totalCheck++
                if (isBlank(bitmap.getPixel(right, y))) blankPixels++
            }
            if (blankPixels.toFloat() / totalCheck <= (1f - sensitivity)) break@right
            right--
        }

        // 3. 如果全部空白，返回原图
        if (top >= bottom || left >= right) return bitmap

        // 4. 添加边距（不超过该侧检测到的空白距离，避免溢出）
        val marginTop = minOf(userMargin, top)
        val marginBottom = minOf(userMargin, height - 1 - bottom)
        val marginLeft = minOf(userMargin, left)
        val marginRight = minOf(userMargin, width - 1 - right)

        val croppedLeft = (left - marginLeft).coerceAtLeast(0)
        val croppedTop = (top - marginTop).coerceAtLeast(0)
        val croppedRight = (right + marginRight).coerceAtMost(width - 1)
        val croppedBottom = (bottom + marginBottom).coerceAtMost(height - 1)

        var cropWidth = croppedRight - croppedLeft + 1
        var cropHeight = croppedBottom - croppedTop + 1

        // 5. 最小输出尺寸守卫：如果裁剪后任一维度 ≤ 输入 × MIN_OUTPUT_RATIO，保守扩边
        val minW = (width * MIN_OUTPUT_RATIO).toInt()
        val minH = (height * MIN_OUTPUT_RATIO).toInt()
        if (cropWidth < minW || cropHeight < minH) {
            // 用 userMargin × 2 做保守边界，取检测结果和保守边界的并集
            val fallbackL = minOf(croppedLeft, userMargin * 2)
            val fallbackT = minOf(croppedTop, userMargin * 2)
            val fallbackR = maxOf(croppedRight, width - userMargin * 2)
            val fallbackB = maxOf(croppedBottom, height - userMargin * 2)
            cropWidth = fallbackR - fallbackL + 1
            cropHeight = fallbackB - fallbackT + 1
            return Bitmap.createBitmap(bitmap, fallbackL, fallbackT, cropWidth, cropHeight)
        }

        return Bitmap.createBitmap(bitmap, croppedLeft, croppedTop, cropWidth, cropHeight)
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
