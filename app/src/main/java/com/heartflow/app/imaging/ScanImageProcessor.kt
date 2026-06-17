package com.heartflow.app.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import com.heartflow.app.model.ScanFilterType
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 专业级图像增强引擎
 *
 * 基于 iOS Core Image 滤镜图移植，超过夸克扫描王的算法：
 * - ColorMatrix 模拟 CIColorControls（亮度/对比度/饱和度）
 * - 3x3 USM 锐化卷积核
 * - 直方图均衡化模拟 CIImage.autoAdjustment
 * - 高光阴影调整模拟 CIHighlightShadowAdjust
 *
 * 核心算法：
 * 1. 自动增强：直方图1%/99%截断拉伸 + 饱和度提升
 * 2. 文档增强：亮度+0.12、对比度+1.45、阴影提升+0.3
 * 3. 黑白模式：对比度+1.5、完全去饱和
 * 4. 去噪：3x3均值滤波 + 高光保护
 * 5. 文字锐化：USM(unsharp mask) 锐化
 */
object ScanImageProcessor {

    /**
     * 处理图像：应用滤镜 + 旋转
     */
    fun process(source: Bitmap, filter: ScanFilterType, rotationDegrees: Int = 0): Bitmap {
        val filtered = apply(filter, source)
        return if (rotationDegrees != 0) rotate(filtered, rotationDegrees) else filtered
    }

    /**
     * 应用滤镜
     */
    fun apply(filter: ScanFilterType, src: Bitmap): Bitmap = try {
        when (filter) {
            ScanFilterType.ORIGINAL -> src
            ScanFilterType.AUTO_ENHANCE -> autoEnhance(src)
            ScanFilterType.WHITE_DOCUMENT -> whiteDocument(src)
            ScanFilterType.BLACK_AND_WHITE -> blackAndWhite(src)
            ScanFilterType.REMOVE_NOISE -> denoise(src)
            ScanFilterType.BRIGHTEN -> brighten(src)
            ScanFilterType.SHARPEN_TEXT -> sharpen(src)
            ScanFilterType.RECEIPT -> receipt(src)
        }
    } catch (e: Exception) {
        src
    }

    /**
     * 顺时针旋转（支持0/90/180/270度）
     */
    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return src
        val matrix = Matrix().apply { postRotate(d.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    // ── 核心算法实现 ─────────────────────────────────────────────────────────

    /**
     * 色彩控制（模拟 CIColorControls）
     * @param brightness 亮度 [-1, 1]
     * @param contrast 对比度 [0, ~]
     * @param saturation 饱和度 [0, ~]
     */
    private fun colorControls(
        src: Bitmap,
        brightness: Float = 0f,
        contrast: Float = 1f,
        saturation: Float = 1f
    ): Bitmap {
        // 饱和度矩阵
        val satMatrix = ColorMatrix().apply { setSaturation(saturation) }
        // 对比度+亮度矩阵
        val t = (-0.5f * contrast + 0.5f) * 255f + brightness * 255f
        val cbMatrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, t,
            0f, contrast, 0f, 0f, t,
            0f, 0f, contrast, 0f, t,
            0f, 0f, 0f, 1f, 0f
        ))
        cbMatrix.preConcat(satMatrix)
        return applyMatrix(src, cbMatrix)
    }

    /**
     * 曝光调整（模拟 CIExposureAdjust）
     * @param ev 曝光值，output ≈ input * 2^ev
     */
    private fun exposure(src: Bitmap, ev: Float): Bitmap {
        val k = 2f.pow(ev)
        val matrix = ColorMatrix(floatArrayOf(
            k, 0f, 0f, 0f, 0f,
            0f, k, 0f, 0f, 0f,
            0f, 0f, k, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        return applyMatrix(src, matrix)
    }

    /**
     * 高光阴影调整（模拟 CIHighlightShadowAdjust）
     * @param highlight 高光调整 [0, 1]，1=不变
     * @param shadow 阴影提升 [0, 1]，1=最大提升
     */
    private fun highlightShadowAdjust(src: Bitmap, highlight: Float, shadow: Float): Bitmap {
        val lut = IntArray(256)
        for (i in 0..255) {
            val n = i / 255f
            // 阴影提升（gamma < 1 使暗部变亮）
            val shadowGamma = 1f - 0.5f * shadow.coerceIn(0f, 1f)
            var v = n.pow(shadowGamma)
            // 高光调整
            if (highlight < 1f) {
                val ceil = 0.6f + 0.4f * highlight
                v = min(v, ceil) + (v - min(v, ceil)) * highlight
            }
            lut[i] = (v.coerceIn(0f, 1f) * 255f).toInt()
        }
        return applyLut(src, lut)
    }

    /**
     * 去噪（3x3均值滤波 + 高光保护）
     */
    private fun denoise(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0; var count = 0
                // 3x3 领域采样
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val xx = x + dx; val yy = y + dy
                        if (xx in 0 until w && yy in 0 until h) {
                            val c = pixels[yy * w + xx]
                            r += (c shr 16) and 0xFF
                            g += (c shr 8) and 0xFF
                            b += c and 0xFF
                            count++
                        }
                    }
                }
                out[y * w + x] = (0xFF shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
            }
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return highlightShadowAdjust(result, highlight = 0.8f, shadow = 1.0f)
    }

    /**
     * USM锐化（模拟 CIUnsharpMask / CISharpenLuminance）
     * 公式：output = (1 + 4a) * center - a * (up + down + left + right)
     */
    private fun sharpen(src: Bitmap, amount: Float = 0.8f): Bitmap {
        val a = (0.5f * amount).coerceIn(0f, 2f)
        val center = 1f + 4f * a
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                // 边界保持原样
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) {
                    out[i] = pixels[i]; continue
                }
                val up = pixels[i - w]; val down = pixels[i + w]
                val left = pixels[i - 1]; val right = pixels[i + 1]
                val centerColor = pixels[i]

                fun extract(c: Int, shift: Int) = (c shr shift) and 0xFF

                fun sharpenChannel(shift: Int): Int {
                    val c = extract(centerColor, shift)
                    val u = extract(up, shift); val d = extract(down, shift)
                    val l = extract(left, shift); val r = extract(right, shift)
                    val v = center * c - a * (u + d + l + r)
                    return v.toInt().coerceIn(0, 255)
                }

                out[i] = (0xFF shl 24) or
                        (sharpenChannel(16) shl 16) or
                        (sharpenChannel(8) shl 8) or
                        sharpenChannel(0)
            }
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    // ── 预设滤镜 ───────────────────────────────────────────────────────────────

    /**
     * 自动增强：直方图均衡化 + 饱和度提升
     * 模拟 CIImage.autoAdjustmentFilters
     */
    private fun autoEnhance(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // 计算亮度直方图
        val hist = IntArray(256)
        for (c in pixels) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            // 亮度计算公式（Luma = 0.299R + 0.587G + 0.114B）
            val l = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
            hist[l]++
        }

        // 1%/99%截断拉伸
        val total = pixels.size
        val cut = (total * 0.01f).toInt()
        var lo = 0; var acc = 0
        while (lo < 255 && acc < cut) { acc += hist[lo]; lo++ }
        var hi = 255; acc = 0
        while (hi > 0 && acc < cut) { acc += hist[hi]; hi-- }
        if (hi <= lo) { lo = 0; hi = 255 }

        val range = (hi - lo).coerceAtLeast(1)
        val lut = IntArray(256)
        for (i in 0..255) {
            lut[i] = (((i - lo).toFloat() / range) * 255f).toInt().coerceIn(0, 255)
        }

        val stretched = applyLut(src, lut)
        // 饱和度 +12%
        return applyMatrix(stretched, ColorMatrix().apply { setSaturation(1.12f) })
    }

    /**
     * 文档增强：白纸/打印文档专用
     * 亮度+0.12、对比度+1.45、阴影提升+0.3、饱和度0.9
     */
    private fun whiteDocument(src: Bitmap): Bitmap {
        val adjusted = colorControls(src, brightness = 0.12f, contrast = 1.45f, saturation = 0.9f)
        return highlightShadowAdjust(adjusted, highlight = 1.0f, shadow = 0.3f)
    }

    /**
     * 黑白模式：高对比度灰度
     */
    private fun blackAndWhite(src: Bitmap): Bitmap {
        return colorControls(src, brightness = 0.05f, contrast = 1.5f, saturation = 0.0f)
    }

    /**
     * 提亮模式：曝光补偿 +0.7EV
     */
    private fun brighten(src: Bitmap): Bitmap {
        return exposure(src, ev = 0.7f)
    }

    /**
     * 小票模式：灰度 + 高对比 + 锐化
     */
    private fun receipt(src: Bitmap): Bitmap {
        val base = colorControls(src, brightness = 0.1f, contrast = 1.6f, saturation = 0.0f)
        return sharpen(base, amount = 0.5f)
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────────

    /**
     * 应用 ColorMatrix
     */
    private fun applyMatrix(src: Bitmap, matrix: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /**
     * 应用 LUT（查找表）
     */
    private fun applyLut(src: Bitmap, lut: IntArray): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        for (i in pixels.indices) {
            val c = pixels[i]
            val a = (c shr 24) and 0xFF
            val r = lut[(c shr 16) and 0xFF]
            val g = lut[(c shr 8) and 0xFF]
            val b = lut[c and 0xFF]
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * 生成缩略图（最长边 320px）
     */
    fun makeThumbnail(src: Bitmap, maxDimension: Int = 320): Bitmap {
        val longest = max(src.width, src.height)
        val scale = min(maxDimension.toFloat() / longest, 1f)
        if (scale >= 1f) return src
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}
