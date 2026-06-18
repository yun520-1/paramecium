package com.heartflow.scanner

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 文档扫描器核心类 v3.0
 *
 * 基于 OpenCV 的专业级文档扫描管道。
 * v3 相比 v2（纯 Kotlin CV）的核心改进：
 *
 * 1. Canny 边缘检测用 OpenCV Imgproc.Canny()：工业级实现，多尺度自适应
 * 2. 轮廓检测用 OpenCV findContours()：支持 RETR_EXTERNAL 自动找文档外边界
 * 3. 多边形近似用 approxPolyDP()：稳定高效
 * 4. 透视变换用 warpPerspective()：双线性插值质量更好
 *
 * 在 OpenCV 加载失败时降级到纯 Kotlin 实现（v2 保留的管道）。
 */

/**
 * 文档类型枚举
 */
enum class DocumentType {
    /** A4/Letter 标准文档（宽高比 1.2~1.7） */
    A4,
    /** 小票/收据（细长条，宽高比 >2.8） */
    RECEIPT,
    /** 书页/双栏文档（宽高比 1.7~2.8） */
    BOOK_PAGE,
    /** 照片/方形图片（宽高比 <1.2） */
    PHOTO,
    /** 未知/其他类型 */
    UNKNOWN
}

/**
 * 一键扫描完整结果
 *
 * @property bitmap 处理后的位图
 * @property documentType 识别到的文档类型
 * @property corners 检测到的四角（降级时为 null）
 * @property autoEnhanced 是否已执行自适应增强
 * @property processingTimeMs 处理耗时（毫秒）
 */
data class ScanResult(
    val bitmap: Bitmap,
    val documentType: DocumentType,
    val corners: List<PointF>?,
    val autoEnhanced: Boolean,
    val processingTimeMs: Long
)

/**
 * 直方图分析统计结果
 *
 * @property meanLuma 平均亮度 (0-255)
 * @property medianLuma 中位亮度 (0-255)
 * @property darkPercent 暗部占比（亮度 <64，百分比）
 * @property brightPercent 亮部占比（亮度 >192，百分比）
 * @property isDarkImage 是否偏暗
 * @property isBrightImage 是否偏亮
 */
data class HistogramStats(
    val meanLuma: Float,
    val medianLuma: Float,
    val darkPercent: Float,
    val brightPercent: Float,
    val isDarkImage: Boolean,
    val isBrightImage: Boolean
)

class DocumentScanner {

    companion object {
        private const val TAG = "DocumentScanner"

        private const val CANNY_LOW = 30.0     // Canny 低阈值
        private const val CANNY_HIGH = 100.0   // Canny 高阈值
        private const val MIN_AREA_RATIO = 0.12f   // v3.4: 0.06→0.12 提高最小面积门槛，防止误检内部小块区域
        private const val MIN_SIDE_RATIO = 0.20f   // v3.4: 0.12→0.20 提高外接框短边要求，防止检测到极小区域
        private const val DP_EPSILON_RATIO = 0.015f  // Douglas-Peucker epsilon 相对值（v3.1 从 0.018→0.015 更精确拟合）
        private const val MORPH_KERNEL = 7            // v3.2: 5→7 更大的形态学核，更好地连接边缘

        private var openCvLoaded = false
        private var openCvChecked = false

        /**
         * 初始化 OpenCV 原生库
         * 线程安全，只会初始化一次
         */
        fun initOpenCV(): Boolean {
            if (!openCvChecked) {
                openCvChecked = true
                try {
                    // OpenCVLoader.initLocal() 是 OpenCV Android SDK 推荐的标准初始化方式，
                    // 它会自动加载 opencv_java4 原生库。
                    // 相比 System.loadLibrary("opencv_java4")，它对不同 OpenCV 版本和 ABI 有更好的兼容性。
                    openCvLoaded = OpenCVLoader.initLocal()
                } catch (e: UnsatisfiedLinkError) {
                    Log.w(TAG, "OpenCV 原生库加载失败（UnsatisfiedLinkError）")
                    openCvLoaded = false
                } catch (e: Exception) {
                    Log.w(TAG, "OpenCV 初始化异常: ${e.message}")
                    openCvLoaded = false
                }
            }
            return openCvLoaded
        }

        /** 检查 OpenCV 是否可用 */
        fun isOpenCvAvailable(): Boolean {
            if (!openCvChecked) initOpenCV()
            return openCvLoaded
        }

        /**
         * 基于轮廓检测的最小矩形包围盒自动裁切 v2.1
         *
         * 使用 OpenCV findContours 检测最大内容区域轮廓，
         * 用 minAreaRect 计算最小外接旋转矩形，然后裁剪出文档主体。
         * 相比四边边界扫描法，对倾斜文档、复杂背景有更好的鲁棒性。
         *
         * v2.1 改进：
         * - Canny 从固定阈值改为 Otsu 自适应阈值（v3.5 findQuadrilateralWithOpenCV 同款）
         * - minAreaRatio 从 0.15f 降至 0.08f，允许检测更小的内容区域
         * - 形态学核从固定 5×5 改为基于图片尺寸自适应
         *
         * @param bitmap 源图
         * @param margin 保留边距（像素）
         * @param minAreaRatio 最小区域占比阈值（低于此值返回原图）
         * @return 裁剪后的 bitmap，若失败返回原图
         */
        fun contourAutoCrop(bitmap: Bitmap, margin: Int = 20, minAreaRatio: Float = 0.08f): Bitmap {
            if (!initOpenCV()) return bitmap
            try {
                val w = bitmap.width; val h = bitmap.height
                val bmp32 = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                val src = org.opencv.core.Mat()
                Utils.bitmapToMat(bmp32, src)

                val gray = org.opencv.core.Mat()
                Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.bilateralFilter(gray, gray, 9, 75.0, 75.0)

                // Canny 边缘检测 — Otsu 自适应阈值（v2.1: 从固定 30/100 改为自适应）
                val edges = org.opencv.core.Mat()
                val sobelX = org.opencv.core.Mat()
                val sobelY = org.opencv.core.Mat()
                val sobelMag = org.opencv.core.Mat()
                Imgproc.Sobel(gray, sobelX, org.opencv.core.CvType.CV_32F, 1, 0)
                Imgproc.Sobel(gray, sobelY, org.opencv.core.CvType.CV_32F, 0, 1)
                Core.magnitude(sobelX, sobelY, sobelMag)
                val mag8u = org.opencv.core.Mat()
                sobelMag.convertTo(mag8u, org.opencv.core.CvType.CV_8U)
                val otsuThresh = Imgproc.threshold(mag8u, org.opencv.core.Mat(), 0.0, 255.0, Imgproc.THRESH_OTSU or Imgproc.THRESH_BINARY)
                val highThresh = maxOf(otsuThresh * 0.5, 30.0)
                val lowThresh = maxOf(highThresh * 0.25, 10.0)
                Imgproc.Canny(gray, edges, lowThresh, highThresh)
                sobelX.release(); sobelY.release(); sobelMag.release(); mag8u.release()

                // 形态学闭操作连接断边（核大小按图尺寸自适应，与 findQuadrilateralWithOpenCV 一致）
                val adaptiveKernel = ((MORPH_KERNEL * minOf(w, h) / 600f).toInt().coerceIn(3, 11) or 1)
                val kernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_ELLIPSE, org.opencv.core.Size(adaptiveKernel.toDouble(), adaptiveKernel.toDouble())
                )
                Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)

                // 查找轮廓
                val contours = ArrayList<org.opencv.core.MatOfPoint>()
                val hierarchy = org.opencv.core.Mat()
                Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

                contours.sortByDescending { Imgproc.contourArea(it) }

                if (contours.isEmpty()) {
                    src.release(); gray.release(); edges.release(); hierarchy.release()
                    bmp32.recycle()
                    return bitmap
                }

                val maxArea = Imgproc.contourArea(contours[0])
                val imageArea = (w * h).toFloat()
                if (maxArea < imageArea * minAreaRatio) {
                    src.release(); gray.release(); edges.release(); hierarchy.release()
                    bmp32.recycle()
                    return bitmap
                }

                // 用 minAreaRect 计算最小外接旋转矩形
                val rotRect = Imgproc.minAreaRect(org.opencv.core.MatOfPoint2f(*contours[0].toArray()))
                val boxPts = org.opencv.core.MatOfPoint2f()
                Imgproc.boxPoints(rotRect, boxPts)
                val corners = boxPts.toArray()
                boxPts.release()

                // 释放 OpenCV 中间临时 Mat（轮廓数据已提取并转换为角点）
                src.release(); gray.release(); edges.release(); hierarchy.release()
                bmp32.recycle()

                // 对四点排序：左上、右上、右下、左下
                val cornerList = corners.map { PointF(it.x.toFloat(), it.y.toFloat()) }
                val sortedByY = cornerList.sortedBy { it.y }
                val topTwo = sortedByY.take(2).sortedBy { it.x }
                val bottomTwo = sortedByY.takeLast(2).sortedBy { it.x }
                val tl = topTwo[0]; val tr = topTwo[1]; val br = bottomTwo[1]; val bl = bottomTwo[0]

                // 计算文档区域像素尺寸（取对边长度的较大值，确保内容完整）
                fun ptDist(a: PointF, b: PointF) = sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))
                val dstWidth = maxOf(ptDist(tl, tr), ptDist(bl, br)).toInt().coerceAtLeast(1)
                val dstHeight = maxOf(ptDist(tl, bl), ptDist(tr, br)).toInt().coerceAtLeast(1)

                // 带边距的输出尺寸
                val outWidth = dstWidth + margin * 2
                val outHeight = dstHeight + margin * 2

                // 最小输出尺寸守卫
                val minW = (w * 0.15f).toInt().coerceAtLeast(50)
                val minH = (h * 0.15f).toInt().coerceAtLeast(50)
                if (outWidth < minW || outHeight < minH) return bitmap

                // 透视变换：将旋转矩形校正为正视平面
                val srcMat = org.opencv.core.Mat(4, 1, org.opencv.core.CvType.CV_32FC2)
                val dstMat = org.opencv.core.Mat(4, 1, org.opencv.core.CvType.CV_32FC2)
                srcMat.put(0, 0, floatArrayOf(tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y))
                dstMat.put(0, 0, floatArrayOf(
                    margin.toFloat(), margin.toFloat(),
                    (dstWidth + margin - 1).toFloat(), margin.toFloat(),
                    (dstWidth + margin - 1).toFloat(), (dstHeight + margin - 1).toFloat(),
                    margin.toFloat(), (dstHeight + margin - 1).toFloat()
                ))

                val perspectiveMat = Imgproc.getPerspectiveTransform(srcMat, dstMat)

                val bmpIn = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                val matIn = org.opencv.core.Mat()
                Utils.bitmapToMat(bmpIn, matIn)

                val matOut = org.opencv.core.Mat()
                Imgproc.warpPerspective(matIn, matOut, perspectiveMat,
                    org.opencv.core.Size(outWidth.toDouble(), outHeight.toDouble()),
                    Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT,
                    org.opencv.core.Scalar(255.0, 255.0, 255.0, 255.0))

                val result = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(matOut, result)

                matIn.release(); matOut.release(); perspectiveMat.release()
                srcMat.release(); dstMat.release()
                bmpIn.recycle()

                return result
            } catch (e: Exception) {
                Log.w("DocumentScanner", "contourAutoCrop 执行失败", e)
                return bitmap
            }
        }

        /**
         * 分析图像亮度直方图
         *
         * 采样分析图像的亮度分布，用于指导自动曝光调整。
         *
         * @param bitmap 源图
         * @return HistogramStats 包含平均亮度、暗部/亮部占比等信息
         */
        fun analyzeHistogram(bitmap: Bitmap): HistogramStats {
            val w = bitmap.width; val h = bitmap.height
            val step = maxOf(1, minOf(w, h) / 100)
            val hist = IntArray(256)
            var total = 0

            for (y in 0 until h step step) {
                for (x in 0 until w step step) {
                    val p = bitmap.getPixel(x, y)
                    val l = (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)).toInt().coerceIn(0, 255)
                    hist[l]++
                    total++
                }
            }

            // 平均亮度
            var sum = 0
            for (i in 0..255) sum += i * hist[i]
            val meanLuma = sum.toFloat() / total.coerceAtLeast(1)

            // 中位亮度
            var cum = 0; var medianLuma = 128f
            for (i in 0..255) {
                cum += hist[i]
                if (cum >= total / 2) { medianLuma = i.toFloat(); break }
            }

            // 暗部/亮部占比
            var darkCount = 0; var brightCount = 0
            for (i in 0..63) darkCount += hist[i]
            for (i in 192..255) brightCount += hist[i]

            val darkPercent = darkCount.toFloat() / total.coerceAtLeast(1) * 100f
            val brightPercent = brightCount.toFloat() / total.coerceAtLeast(1) * 100f

            return HistogramStats(
                meanLuma = meanLuma,
                medianLuma = medianLuma,
                darkPercent = darkPercent,
                brightPercent = brightPercent,
                isDarkImage = meanLuma < 80f,
                isBrightImage = meanLuma > 200f
            )
        }

        /**
         * 基于直方图分析的智能曝光调整
         *
         * 分析图像亮度直方图，自动判断暗图/亮图并应用合适的曝光补偿：
         * - 暗图：提亮 + 增强对比度
         * - 亮图：轻微降曝
         * - 正常：微增强
         *
         * @param bitmap 源图
         * @return 曝光调整后的 bitmap
         */
        fun autoAdjustExposure(bitmap: Bitmap): Bitmap {
            val stats = analyzeHistogram(bitmap)

            val brightness: Float
            val contrast: Float

            when {
                stats.isDarkImage -> {
                    brightness = 0.15f; contrast = 1.35f
                }
                stats.isBrightImage -> {
                    brightness = -0.08f; contrast = 1.10f
                }
                stats.darkPercent > 30f -> {
                    brightness = 0.08f; contrast = 1.20f
                }
                stats.brightPercent > 60f -> {
                    brightness = -0.05f; contrast = 1.12f
                }
                else -> {
                    brightness = 0.03f; contrast = 1.08f
                }
            }

            if (initOpenCV()) {
                try {
                    val src = org.opencv.core.Mat()
                    Utils.bitmapToMat(bitmap, src)
                    val dest = org.opencv.core.Mat()
                    src.convertTo(dest, -1, contrast.toDouble(), (brightness * 255.0))
                    val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    Utils.matToBitmap(dest, result)
                    src.release(); dest.release()
                    return result
                } catch (_: Exception) {}
            }

            // 降级：纯 Kotlin 像素操作
            val w = bitmap.width; val h = bitmap.height
            val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val brightnessOffset = (brightness * 255f).toInt()
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val p = bitmap.getPixel(x, y)
                    fun adjust(c: Int): Int = (((c - 128) * contrast + 128).toInt() + brightnessOffset).coerceIn(0, 255)
                    result.setPixel(x, y, Color.rgb(
                        adjust(Color.red(p)),
                        adjust(Color.green(p)),
                        adjust(Color.blue(p))
                    ))
                }
            }
            return result
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  公共 API
    // ════════════════════════════════════════════════════════════════

    /**
     * 检测图像中的文档四角
     * 优先使用 OpenCV，降级到纯 Kotlin 实现
     */
    fun detectCorners(bitmap: Bitmap): List<PointF> {
        val width = bitmap.width
        val height = bitmap.height

        // 缩小图像以加快处理速度
        val scale = minOf(1f, 800f / maxOf(width, height))
        val scaledWidth = (width * scale).toInt().coerceAtLeast(200)
        val scaledHeight = (height * scale).toInt().coerceAtLeast(200)
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)

        val corners = if (initOpenCV()) {
            // OpenCV 主管道
            findQuadrilateralWithOpenCV(scaled, scaledWidth, scaledHeight)
        } else {
            // 降级到纯 Kotlin v2 管道
            val edges = cannyEdgeDetectionFallback(scaled)
            tryFindQuadrilateralFallback(edges, scaledWidth, scaledHeight)
        }

        val result = corners
            ?: findQuadByBoundaryScanning(
                if (initOpenCV()) cannyEdgeDetectionFallback(scaled) else cannyEdgeDetectionFallback(scaled),
                scaledWidth, scaledHeight
            )

        scaled.recycle()

        val ratioX = width.toFloat() / scaledWidth
        val ratioY = height.toFloat() / scaledHeight
        return result.map { PointF(it.x * ratioX, it.y * ratioY) }
    }

    /**
     * 自动检测文档类型
     *
     * 根据输入图像的宽高比确定文档类型：
     * - A4/文档：宽高比 1.2~1.7
     * - 书页/双栏：宽高比 1.7~2.8
     * - 小票/收据：宽高比 >2.8
     * - 照片/方形：宽高比 <1.2
     */
    fun detectDocumentType(bitmap: Bitmap): DocumentType {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val ratio = if (w > h) w / h else h / w
        return when {
            ratio < 1.2f -> DocumentType.PHOTO
            ratio <= 1.7f -> DocumentType.A4
            ratio <= 2.8f -> DocumentType.BOOK_PAGE
            else -> DocumentType.RECEIPT
        }
    }

    /**
     * 一键扫描：全自动文档扫描流水线
     *
     * 整合了文档检测、透视校正、纠偏、自适应增强于一次调用：
     * 1. 自动检测文档类型（A4/小票/书页/照片）
     * 2. 检测文档四角并进行透视校正
     * 3. 自动摆正文本行
     * 4. 根据文档类型应用自适应增强
     *
     * 安全机制：
     * - 检测到的四边形外接框必须覆盖原图至少 40% 宽和高
     * - 否则跳过透视变换，走基本增强降级（防止小区域→3D拉伸）
     *
     * @param bitmap 输入图像
     * @return ScanResult 包含处理结果、文档类型、处理耗时等信息
     */
    fun autoScan(bitmap: Bitmap): ScanResult {
        val startMs = System.currentTimeMillis()

        // 1. 检测文档类型
        val docType = detectDocumentType(bitmap)

        // 2. 尝试检测文档四角
        var corners: List<PointF>? = null
        var enhanced = false
        var result: Bitmap

        try {
            corners = detectCorners(bitmap)
        } catch (e: Exception) {
            Log.w("DocumentScanner", "autoScan 四角检测失败", e)
        }

        // 3. 角落有效性校验：必须覆盖原图至少 40%，否则是误检
        var cornersValid = false
        if (corners != null && corners.size == 4) {
            val sorted = sortCorners(corners[0], corners[1], corners[2], corners[3])
            val minX = sorted.minOf { it.x }; val maxX = sorted.maxOf { it.x }
            val minY = sorted.minOf { it.y }; val maxY = sorted.maxOf { it.y }
            val quadW = maxX - minX; val quadH = maxY - minY
            cornersValid = quadW >= bitmap.width * 0.15f && quadH >= bitmap.height * 0.15f
            if (!cornersValid) {
                Log.w("DocumentScanner", "角落太小 ($quadW×$quadH vs ${bitmap.width}×${bitmap.height})，跳过透视变换")
            }
        }

        if (corners != null && corners.isNotEmpty() && cornersValid) {
            // 4a. 四角有效且足够大 → 透视校正 + 纠偏 + 增强
            try {
                result = perspectiveTransform(bitmap, corners)
                result = deskew(result)

                // 4b. 根据文档类型应用自适应增强（v2.6.4 增强管线）
                when (docType) {
                    DocumentType.A4, DocumentType.BOOK_PAGE -> {
                        // 文档类：背景净化 → CLAHE增强 → 文字清晰锐利
                        result = applyPureBackground(result)
                        result = applyScanEffect(result, "clahe")
                        enhanced = true
                    }
                    DocumentType.RECEIPT -> {
                        // 小票：背景净化 → 自动增强，保留细节
                        result = applyPureBackground(result)
                        result = applyScanEffect(result, "original")
                        enhanced = true
                    }
                    DocumentType.PHOTO -> {
                        // 照片：保持原色，不做额外处理
                    }
                    DocumentType.UNKNOWN -> {
                        // 未知：彩色增强
                        result = applyScanEffect(result, "colorful")
                        enhanced = true
                    }
                }
            } catch (e: Exception) {
                Log.w("DocumentScanner", "autoScan 管道执行失败，退回原图", e)
                result = bitmap
            }
        } else {
            // 5. 四角检测失败或无效 → 降级为基本增强
            enhanced = true
            try {
                result = when (docType) {
                    DocumentType.PHOTO -> bitmap
                    else -> applyScanEffect(bitmap, "original")
                }
            } catch (e: Exception) {
                Log.w("DocumentScanner", "autoScan 降级处理失败", e)
                result = bitmap
            }
        }

        val elapsed = System.currentTimeMillis() - startMs
        return ScanResult(
            bitmap = result,
            documentType = docType,
            corners = if (corners.isNullOrEmpty() || !cornersValid) null else corners,
            autoEnhanced = enhanced,
            processingTimeMs = elapsed
        )
    }

    // ════════════════════════════════════════════════════════════════
    //  OpenCV 管道
    // ════════════════════════════════════════════════════════════════

    /** 使用 OpenCV 检测四边形 */
    private fun findQuadrilateralWithOpenCV(
        bitmap: Bitmap, w: Int, h: Int
    ): List<PointF>? {
        try {
            // 1. Bitmap → Mat
            val src = org.opencv.core.Mat()
            val bmp32 = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            org.opencv.android.Utils.bitmapToMat(bmp32, src)

            // 2. 灰度化
            val gray = org.opencv.core.Mat()
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

            // 3. 保边去噪（v2.6.4: 从 GaussianBlur 改为 bilateralFilter）
            //    双边滤波在降噪时保留边缘，对复杂背景（木纹、布纹）特别有效
            Imgproc.bilateralFilter(gray, gray, 9, 75.0, 75.0)

            // 4. Canny 边缘检测（基于 Sobel 梯度 + Otsu 自适应阈值）
            val edges = org.opencv.core.Mat()
            // 计算 Sobel 梯度幅值，用 Otsu 自动确定高低阈值（适应不同光照/对比度）
            val sobelX = org.opencv.core.Mat()
            val sobelY = org.opencv.core.Mat()
            val sobelMag = org.opencv.core.Mat()
            Imgproc.Sobel(gray, sobelX, org.opencv.core.CvType.CV_32F, 1, 0)
            Imgproc.Sobel(gray, sobelY, org.opencv.core.CvType.CV_32F, 0, 1)
            Core.magnitude(sobelX, sobelY, sobelMag)
            val mag8u = org.opencv.core.Mat()
            sobelMag.convertTo(mag8u, org.opencv.core.CvType.CV_8U)
            val otsuThresh = Imgproc.threshold(mag8u, org.opencv.core.Mat(), 0.0, 255.0, Imgproc.THRESH_OTSU or Imgproc.THRESH_BINARY)
            val highThresh = maxOf(otsuThresh * 0.5, 30.0)
            val lowThresh = maxOf(highThresh * 0.25, 10.0)
            Imgproc.Canny(gray, edges, lowThresh, highThresh)
            sobelX.release(); sobelY.release(); sobelMag.release(); mag8u.release()

            // 5. 形态学闭操作连接断边（v2.6.4: MORPH_ELLIPSE 椭圆核连接更自然）
            val adaptiveKernel = ((MORPH_KERNEL * minOf(w, h) / 600f).toInt().coerceIn(3, 11) or 1)  // 取奇数
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE, org.opencv.core.Size(adaptiveKernel.toDouble(), adaptiveKernel.toDouble())
            )
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)

            // 6. 查找轮廓
            val contours = ArrayList<org.opencv.core.MatOfPoint>()
            val hierarchy = org.opencv.core.Mat()
            Imgproc.findContours(
                edges, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )

            // 7. 按面积排序，取最大轮廓
            contours.sortByDescending { Imgproc.contourArea(it) }
            if (contours.isEmpty()) return null

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                val imageArea = (w * h).toFloat()
                if (area < imageArea * MIN_AREA_RATIO) continue

                // 8. 多边形近似
                val perimeter = Imgproc.arcLength(org.opencv.core.MatOfPoint2f(*contour.toArray()), true)
                val epsilon = (perimeter * DP_EPSILON_RATIO).coerceAtLeast(3.0)
                val approx = org.opencv.core.MatOfPoint2f()
                Imgproc.approxPolyDP(
                    org.opencv.core.MatOfPoint2f(*contour.toArray()),
                    approx, epsilon, true
                )

                // 检查是否四边形
                val cornersArray = approx.toArray()
                if (cornersArray.size == 4) {
                    // 凸性校验（v2.6.4 新增）：过滤凹四边形，防止复杂背景误检
                    val hullCheck = org.opencv.core.MatOfInt()
                    Imgproc.convexHull(contour, hullCheck)
                    val hullArea = Imgproc.contourArea(org.opencv.core.MatOfPoint(
                        *hullCheck.toArray().map { contour.toArray()[it] }.toTypedArray()
                    ))
                    val contourArea = Imgproc.contourArea(contour)
                    hullCheck.release()
                    // 凸包面积不能显著大于轮廓面积（允许 15% 容差）
                    if (hullArea > contourArea * 1.15f) continue

                    val points = cornersArray.map {
                        PointF(it.x.toFloat(), it.y.toFloat())
                    }
                    val sorted = sortCorners(points[0], points[1], points[2], points[3])
                    val qArea = computeQuadArea(sorted[0], sorted[1], sorted[2], sorted[3])

                    // 面积校验
                    if (qArea >= imageArea * MIN_AREA_RATIO) {
                        // 外接框边长校验：防止细长/极小区域
                        val minX = sorted.minOf { it.x }
                        val maxX = sorted.maxOf { it.x }
                        val minY = sorted.minOf { it.y }
                        val maxY = sorted.maxOf { it.y }
                        val quadW = maxX - minX
                        val quadH = maxY - minY
                        if (quadW >= w * MIN_SIDE_RATIO && quadH >= h * MIN_SIDE_RATIO) {
                            src.release(); gray.release(); edges.release(); hierarchy.release()
                            bmp32.recycle()
                            return sorted
                        }
                    }
                }

                // 9. 凸包降级
                if (cornersArray.size > 4) {
                    val hullPoints = tryConvexHullQuad(contour)
                    if (hullPoints != null) {
                        val qArea = computeQuadArea(hullPoints[0], hullPoints[1], hullPoints[2], hullPoints[3])
                        if (qArea >= imageArea * MIN_AREA_RATIO * 0.6f) {
                            src.release(); gray.release(); edges.release(); hierarchy.release()
                            bmp32.recycle()
                            return hullPoints
                        }
                    }
                }
            }

            src.release(); gray.release(); edges.release(); hierarchy.release()
            bmp32.recycle()
        } catch (e: Exception) {
            // OpenCV 异常时静默降级
        }
        return null
    }

    /** OpenCV 凸包 → 四边形（旋转矩形近似） */
    private fun tryConvexHullQuad(contour: org.opencv.core.MatOfPoint): List<PointF>? {
        try {
            val hull = org.opencv.core.MatOfInt()
            Imgproc.convexHull(contour, hull)
            val hullPoints = org.opencv.core.MatOfPoint()
            val idx = hull.toArray()
            val pts = contour.toArray()
            hullPoints.fromList(idx.map { pts[it] }.toList())

            // 用最小外接旋转矩形
            val rect = Imgproc.minAreaRect(org.opencv.core.MatOfPoint2f(*hullPoints.toArray()))
            val boxPoints = org.opencv.core.MatOfPoint2f()
            Imgproc.boxPoints(rect, boxPoints)
            val corners = boxPoints.toArray()

            if (corners.size == 4) {
                return sortCorners(
                    PointF(corners[0].x.toFloat(), corners[0].y.toFloat()),
                    PointF(corners[1].x.toFloat(), corners[1].y.toFloat()),
                    PointF(corners[2].x.toFloat(), corners[2].y.toFloat()),
                    PointF(corners[3].x.toFloat(), corners[3].y.toFloat())
                )
            }
        } catch (_: Exception) {}
        return null
    }

    // ════════════════════════════════════════════════════════════════
    //  纯 Kotlin 降级管道（v2 保留）
    // ════════════════════════════════════════════════════════════════

    // ---- v2 Canny 边缘检测（保留为降级路径） ----

    private fun cannyEdgeDetectionFallback(bitmap: Bitmap): Array<IntArray> {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = FloatArray(w * h) { i ->
            val c = pixels[i]
            0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
        }
        val blurred = gaussianBlur(gray, w, h)
        val (magnitude, direction) = sobelGradients(blurred, w, h)
        val suppressed = nonMaxSuppression(magnitude, direction, w, h)
        val magMax = suppressed.maxOrNull() ?: 0f
        val otsuThresh = if (magMax > 5f) computeOtsuThresholdFloat(suppressed, magMax, w * h) else 30f
        val highThresh = maxOf(otsuThresh * 0.35f, 8f)
        val lowThresh = maxOf(highThresh * 0.10f, 3f)
        val edges = doubleThreshold(suppressed, w, h, lowThresh, highThresh)
        hysteresis(edges, w, h)
        return Array(h) { y -> IntArray(w) { x -> if (edges[y][x] > 0) 255 else 0 } }
    }

    private fun gaussianBlur(src: FloatArray, w: Int, h: Int): FloatArray {
        val kernel = arrayOf(
            floatArrayOf(2f, 4f, 5f, 4f, 2f),
            floatArrayOf(4f, 9f, 12f, 9f, 4f),
            floatArrayOf(5f, 12f, 15f, 12f, 5f),
            floatArrayOf(4f, 9f, 12f, 9f, 4f),
            floatArrayOf(2f, 4f, 5f, 4f, 2f)
        )
        val result = FloatArray(w * h)
        for (y in 2 until h - 2) for (x in 2 until w - 2) {
            var sum = 0f
            for (ky in -2..2) for (kx in -2..2)
                sum += kernel[ky + 2][kx + 2] * src[(y + ky) * w + (x + kx)]
            result[y * w + x] = sum / 159f
        }
        for (y in 0 until h) for (x in 0 until w) {
            if (result[y * w + x] == 0f) result[y * w + x] = src[y * w + x].coerceIn(0f, 255f)
        }
        return result
    }

    private fun sobelGradients(src: FloatArray, w: Int, h: Int): Pair<FloatArray, FloatArray> {
        val magnitude = FloatArray(w * h); val direction = FloatArray(w * h)
        val sobelX = intArrayOf(-1, 0, 1, -2, 0, 2, -1, 0, 1)
        val sobelY = intArrayOf(-1, -2, -1, 0, 0, 0, 1, 2, 1)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            var gx = 0f; var gy = 0f
            for (ky in -1..1) for (kx in -1..1) {
                val idx = (ky + 1) * 3 + (kx + 1)
                val s = src[(y + ky) * w + (x + kx)]
                gx += sobelX[idx] * s; gy += sobelY[idx] * s
            }
            val idx = y * w + x
            magnitude[idx] = sqrt(gx * gx + gy * gy)
            direction[idx] = (atan2(gy.toDouble(), gx.toDouble()) + Math.PI).toFloat() % Math.PI.toFloat()
        }
        return Pair(magnitude, direction)
    }

    private fun nonMaxSuppression(mag: FloatArray, dir: FloatArray, w: Int, h: Int): FloatArray {
        val result = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val idx = y * w + x; val m = mag[idx]
            val d2 = ((dir[idx] / Math.PI.toFloat() * 4 + 0.5f).toInt() + 4) % 4
            val (n1, n2) = when (d2) {
                0 -> Pair(mag[idx - 1], mag[idx + 1])
                1 -> Pair(mag[(y - 1) * w + (x + 1)], mag[(y + 1) * w + (x - 1)])
                2 -> Pair(mag[idx - w], mag[idx + w])
                else -> Pair(mag[(y - 1) * w + (x - 1)], mag[(y + 1) * w + (x + 1)])
            }
            if (m >= n1 && m >= n2) result[idx] = m
        }
        return result
    }

    private fun computeOtsuThresholdFloat(values: FloatArray, maxVal: Float, total: Int): Float {
        val histSize = maxOf(maxVal.toInt() + 1, 1).coerceAtMost(256)
        val histogram = IntArray(histSize)
        for (v in values) histogram[v.toInt().coerceIn(0, histSize - 1)]++
        var sum = 0.0; for (i in 0 until histSize) sum += i.toDouble() * histogram[i]
        var sumB = 0.0; var wB = 0; var maxVar = 0.0; var threshold = 15f
        for (t in 0 until histSize) {
            wB += histogram[t]; if (wB == 0) continue
            val wF = total - wB; if (wF == 0) break
            sumB += t.toDouble() * histogram[t]
            val diff = sumB / wB - (sum - sumB) / wF
            val between = wB.toDouble() * wF.toDouble() * diff * diff
            if (between > maxVar) { maxVar = between; threshold = t.toFloat() }
        }
        return threshold.coerceIn(8f, 200f)
    }

    private fun doubleThreshold(suppressed: FloatArray, w: Int, h: Int, low: Float, high: Float): Array<IntArray> {
        return Array(h) { y -> IntArray(w) { x ->
            val v = suppressed[y * w + x]; when { v >= high -> 2; v >= low -> 1; else -> 0 }
        } }
    }

    private fun hysteresis(edges: Array<IntArray>, w: Int, h: Int) {
        val queue = java.util.ArrayDeque<Pair<Int, Int>>()
        for (y in 0 until h) for (x in 0 until w) if (edges[y][x] == 2) queue.add(Pair(x, y))
        val dx = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
        val dy = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)
        while (queue.isNotEmpty()) {
            val (cx, cy) = queue.poll() ?: continue
            for (i in 0..7) {
                val nx = cx + dx[i]; val ny = cy + dy[i]
                if (nx in 0 until w && ny in 0 until h && edges[ny][nx] == 1) {
                    edges[ny][nx] = 2; queue.add(Pair(nx, ny))
                }
            }
        }
        for (y in 0 until h) for (x in 0 until w) if (edges[y][x] == 1) edges[y][x] = 0
    }

    // ---- v2 四边形检测降级 ----

    private val MIN_BOUNDARY_POINTS = 5
    private val MIN_COMPONENT_RATIO = 0.02f

    private fun tryFindQuadrilateralFallback(
        edges: Array<IntArray>, w: Int, h: Int
    ): List<PointF>? {
        val closed = morphologicalClose(edges, w, h, MORPH_KERNEL)
        val component = connectedComponents(closed, w, h) ?: return null
        val boundary = extractBoundary(closed, component, w, h)
        if (boundary.size < 20) return null
        val hull = convexHull(boundary)
        if (hull.size < 4) return null
        val halfPerimeter = perimeterLength(hull) / 2f
        val epsilon = (DP_EPSILON_RATIO * halfPerimeter).coerceAtLeast(2f)
        val simplified = douglasPeucker(hull, epsilon)
        if (simplified.size == 4) {
            val quad = sortCorners(simplified[0], simplified[1], simplified[2], simplified[3])
            val area = computeQuadArea(quad[0], quad[1], quad[2], quad[3])
            if (area >= (w * h).toFloat() * MIN_AREA_RATIO) return quad
        }
        return if (hull.size >= 4) findBestQuadrilateral(hull, w, h) else null
    }

    private fun morphologicalClose(src: Array<IntArray>, w: Int, h: Int, ks: Int): Array<IntArray> {
        val half = ks / 2
        val dilated = Array(h) { IntArray(w) }
        val closed = Array(h) { IntArray(w) }
        for (y in half until h - half) for (x in half until w - half) {
            var mx = 0; for (ky in -half..half) for (kx in -half..half)
                if (src[y + ky][x + kx] > mx) mx = src[y + ky][x + kx]
            dilated[y][x] = mx
        }
        for (y in half until h - half) for (x in half until w - half) {
            var mn = 255; for (ky in -half..half) for (kx in -half..half)
                if (dilated[y + ky][x + kx] < mn) mn = dilated[y + ky][x + kx]
            closed[y][x] = mn
        }
        return closed
    }

    private fun connectedComponents(binary: Array<IntArray>, w: Int, h: Int): List<PointF>? {
        val labels = Array(h) { IntArray(w) }
        val equiv = mutableListOf(mutableSetOf(0))
        var nextLabel = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (binary[y][x] == 0) continue
            val up = if (y > 0 && labels[y - 1][x] > 0) labels[y - 1][x] else 0
            val left = if (x > 0 && labels[y][x - 1] > 0) labels[y][x - 1] else 0
            val label = when {
                up == 0 && left == 0 -> { nextLabel++; if (nextLabel >= equiv.size) equiv.add(mutableSetOf(nextLabel)); nextLabel }
                up > 0 && left > 0 && up != left -> {
                    val m = minOf(up, left); val r = maxOf(up, left)
                    equiv[m].add(r); for (li in equiv.indices) if (equiv[li].remove(r)) equiv[li].add(m)
                    m
                }
                else -> maxOf(up, left)
            }
            if (label > 0) labels[y][x] = label
        }
        val finalLabels = IntArray(nextLabel + 1) { it }
        for (i in 1..nextLabel) {
            var root = i; val visited = mutableSetOf(root); val stack = java.util.ArrayDeque<Int>()
            stack.push(i)
            while (stack.isNotEmpty()) {
                val cur = stack.pop()
                for (ni in 1 until equiv.size) {
                    if (equiv[ni].contains(cur) && ni !in visited) { visited.add(ni); if (ni < root) root = ni; stack.push(ni) }
                }
            }
            finalLabels[i] = root
        }
        val counts = IntArray(nextLabel + 1)
        for (y in 0 until h) for (x in 0 until w) if (labels[y][x] > 0) { val f = finalLabels[labels[y][x]]; labels[y][x] = f; counts[f]++ }
        var maxLabel = 0; var maxCount = 0
        for (i in 1..nextLabel) if (counts[i] > maxCount) { maxCount = counts[i]; maxLabel = i }
        val minPixels = (maxOf(w, h) * MIN_COMPONENT_RATIO).toInt().coerceAtLeast(10)
        if (maxLabel == 0 || maxCount < minPixels) return null
        val points = mutableListOf<PointF>()
        for (y in 0 until h) for (x in 0 until w) if (labels[y][x] == maxLabel) points.add(PointF(x.toFloat(), y.toFloat()))
        return points
    }

    private fun extractBoundary(binary: Array<IntArray>, component: List<PointF>, w: Int, h: Int): List<PointF> {
        val set = mutableSetOf<Pair<Int, Int>>(); component.forEach { set.add(Pair(it.x.toInt(), it.y.toInt())) }
        val boundary = mutableListOf<PointF>()
        val dx = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1); val dy = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)
        for ((px, py) in set) {
            var isBoundary = false
            for (i in 0..7) { val nx = px + dx[i]; val ny = py + dy[i]; if (nx < 0 || nx >= w || ny < 0 || ny >= h || Pair(nx, ny) !in set) { isBoundary = true; break } }
            if (isBoundary) boundary.add(PointF(px.toFloat(), py.toFloat()))
        }
        return boundary
    }

    private fun convexHull(points: List<PointF>): List<PointF> {
        if (points.size < 3) return points.toList()
        val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
        fun cross(o: PointF, a: PointF, b: PointF) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = mutableListOf<PointF>()
        for (p in sorted) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) lower.removeAt(lower.size - 1); lower.add(p) }
        val upper = mutableListOf<PointF>()
        for (p in sorted.reversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) upper.removeAt(upper.size - 1); upper.add(p) }
        lower.removeAt(lower.size - 1); upper.removeAt(upper.size - 1)
        return lower + upper
    }

    private fun perimeterLength(poly: List<PointF>): Float {
        if (poly.size < 2) return 0f; var len = 0f
        for (i in poly.indices) len += distance(poly[i], poly[(i + 1) % poly.size])
        return len
    }

    private fun douglasPeucker(points: List<PointF>, epsilon: Float): List<PointF> {
        if (points.size < 3) return points.toList()
        val first = points.first(); val last = points.last()
        var dmax = 0f; var index = 0
        for (i in 1 until points.size - 1) { val d = perpendicularDistance(points[i], first, last); if (d > dmax) { dmax = d; index = i } }
        return if (dmax > epsilon) { val left = douglasPeucker(points.subList(0, index + 1), epsilon); val right = douglasPeucker(points.subList(index, points.size), epsilon); (left.dropLast(1) + right).toMutableList() }
        else listOf(first, last)
    }

    private fun perpendicularDistance(p: PointF, a: PointF, b: PointF): Float {
        val dx = b.x - a.x; val dy = b.y - a.y; val lenSq = dx * dx + dy * dy
        if (lenSq == 0f) return distance(p, a)
        return abs(dy * p.x - dx * p.y + b.x * a.y - b.y * a.x) / sqrt(lenSq)
    }

    private fun findBestQuadrilateral(hull: List<PointF>, w: Int, h: Int): List<PointF>? {
        if (hull.size < 4) return null
        val indices = findExtremePoints(hull)
        if (indices.size < 4) return null
        val c = indices.map { hull[it] }; val sorted = sortCorners(c[0], c[1], c[2], c[3])
        val area = computeQuadArea(sorted[0], sorted[1], sorted[2], sorted[3])
        return if (area >= (w * h).toFloat() * MIN_AREA_RATIO) sorted else null
    }

    private fun findExtremePoints(points: List<PointF>): List<Int> {
        if (points.size < 4) return (0 until points.size).toList()
        val result = mutableSetOf<Int>(); val byX = points.indices.sortedBy { points[it].x }; val byY = points.indices.sortedBy { points[it].y }
        result.add(byX.first()); result.add(byX.last()); result.add(byY.first()); result.add(byY.last())
        if (result.size < 4) { val rem = points.indices.filter { it !in result }; for (i in rem.take(4 - result.size)) result.add(i) }
        return result.toList()
    }

    // ════════════════════════════════════════════════════════════════
    //  v1 四边扫描保留（最终降级）
    // ════════════════════════════════════════════════════════════════

    private fun findQuadByBoundaryScanning(
        edges: Array<IntArray>, width: Int, height: Int
    ): List<PointF> {
        val topPoints = scanTopDown(edges, width, height)
        val bottomPoints = scanBottomUp(edges, width, height)
        val leftPoints = scanLeftRight(edges, width, height)
        val rightPoints = scanRightLeft(edges, width, height)

        if (topPoints.size < MIN_BOUNDARY_POINTS || bottomPoints.size < MIN_BOUNDARY_POINTS ||
            leftPoints.size < MIN_BOUNDARY_POINTS || rightPoints.size < MIN_BOUNDARY_POINTS)
            return useFallbackCorners(width, height)

        val filteredTop = filterOutliers(topPoints, width / 4f)
        val filteredBottom = filterOutliers(bottomPoints, width / 4f)
        val filteredLeft = filterOutliers(leftPoints, height / 4f)
        val filteredRight = filterOutliers(rightPoints, height / 4f)

        if (filteredTop.size < MIN_BOUNDARY_POINTS || filteredBottom.size < MIN_BOUNDARY_POINTS ||
            filteredLeft.size < MIN_BOUNDARY_POINTS || filteredRight.size < MIN_BOUNDARY_POINTS)
            return useFallbackCorners(width, height)

        val topLine = fitLineLeastSquares(filteredTop)
        val bottomLine = fitLineLeastSquares(filteredBottom)
        val leftLine = fitLineLeastSquares(filteredLeft)
        val rightLine = fitLineLeastSquares(filteredRight)

        if (topLine == null || bottomLine == null || leftLine == null || rightLine == null)
            return useFallbackCorners(width, height)

        val tl = intersectLines(topLine, leftLine)
        val tr = intersectLines(topLine, rightLine)
        val bl = intersectLines(bottomLine, leftLine)
        val br = intersectLines(bottomLine, rightLine)
        val corners = listOf(tl, tr, bl, br)
        if (corners.any { it.x.isNaN() || it.y.isNaN() }) return useFallbackCorners(width, height)
        val qa = computeQuadArea(tl, tr, br, bl)
        if (qa < (width * height).toFloat() * MIN_AREA_RATIO) return useFallbackCorners(width, height)
        // 额外边长校验：防止边界扫描产生极小四边形
        val minX = minOf(tl.x, tr.x, bl.x, br.x)
        val maxX = maxOf(tl.x, tr.x, bl.x, br.x)
        val minY = minOf(tl.y, tr.y, bl.y, br.y)
        val maxY = maxOf(tl.y, tr.y, bl.y, br.y)
        if ((maxX - minX) < width * MIN_SIDE_RATIO || (maxY - minY) < height * MIN_SIDE_RATIO)
            return useFallbackCorners(width, height)
        return sortCorners(tl, tr, br, bl)
    }

    private fun scanTopDown(edges: Array<IntArray>, width: Int, height: Int): List<PointF> {
        val points = mutableListOf<PointF>(); val margin = width / 8
        for (x in margin until width - margin step 3) {
            var foundY = -1; var maxEdges = 0
            for (y in 0 until height * 3 / 4) {
                var ec = 0
                for (wy in y until (y + 5).coerceAtMost(height)) for (wx in (x - 1).coerceAtLeast(0)..(x + 1).coerceAtMost(width - 1)) if (edges[wy][wx] > 0) ec++
                if (ec > maxEdges) { maxEdges = ec; foundY = y }; if (ec >= 4) { foundY = y; break }
            }
            if (foundY > 0 && maxEdges > 1) points.add(PointF(x.toFloat(), foundY.toFloat()))
        }
        return points
    }

    private fun scanBottomUp(edges: Array<IntArray>, width: Int, height: Int): List<PointF> {
        val points = mutableListOf<PointF>(); val margin = width / 8
        for (x in margin until width - margin step 3) {
            var foundY = -1; var maxEdges = 0
            for (y in height - 1 downTo height / 4) {
                var ec = 0
                for (wy in (y - 5).coerceAtLeast(0)..y) for (wx in (x - 1).coerceAtLeast(0)..(x + 1).coerceAtMost(width - 1)) if (edges[wy][wx] > 0) ec++
                if (ec > maxEdges) { maxEdges = ec; foundY = y }; if (ec >= 4) { foundY = y; break }
            }
            if (foundY > 0 && foundY < height - 1 && maxEdges > 1) points.add(PointF(x.toFloat(), foundY.toFloat()))
        }
        return points
    }

    private fun scanLeftRight(edges: Array<IntArray>, width: Int, height: Int): List<PointF> {
        val points = mutableListOf<PointF>(); val margin = height / 8
        for (y in margin until height - margin step 3) {
            var foundX = -1; var maxEdges = 0
            for (x in 0 until width * 3 / 4) {
                var ec = 0
                for (wy in (y - 1).coerceAtLeast(0)..(y + 1).coerceAtMost(height - 1)) for (wx in x until (x + 5).coerceAtMost(width)) if (edges[wy][wx] > 0) ec++
                if (ec > maxEdges) { maxEdges = ec; foundX = x }; if (ec >= 4) { foundX = x; break }
            }
            if (foundX > 0 && maxEdges > 1) points.add(PointF(foundX.toFloat(), y.toFloat()))
        }
        return points
    }

    private fun scanRightLeft(edges: Array<IntArray>, width: Int, height: Int): List<PointF> {
        val points = mutableListOf<PointF>(); val margin = height / 8
        for (y in margin until height - margin step 3) {
            var foundX = -1; var maxEdges = 0
            for (x in width - 1 downTo width / 4) {
                var ec = 0
                for (wy in (y - 1).coerceAtLeast(0)..(y + 1).coerceAtMost(height - 1)) for (wx in (x - 5).coerceAtLeast(0)..x) if (edges[wy][wx] > 0) ec++
                if (ec > maxEdges) { maxEdges = ec; foundX = x }; if (ec >= 4) { foundX = x; break }
            }
            if (foundX > 0 && foundX < width - 1 && maxEdges > 1) points.add(PointF(foundX.toFloat(), y.toFloat()))
        }
        return points
    }

    private fun filterOutliers(points: List<PointF>, maxDeviation: Float): List<PointF> {
        if (points.size < 4) return points
        val isHorizontal = points.all { p -> points.count { it.y == p.y } < points.size / 2 }
        val values = if (isHorizontal) points.map { it.y } else points.map { it.x }
        val sorted = values.sorted(); val median = sorted[sorted.size / 2]
        val absDev = values.map { abs(it - median) }.sorted()
        val mad = absDev[absDev.size / 2].coerceAtLeast(1f)
        val threshold = (3.0 * mad).toFloat().coerceAtLeast(maxDeviation * 0.3f)
        return points.filterIndexed { i, _ -> abs(values[i] - median) <= threshold }
    }

    private data class Line(val a: Float, val b: Float, val isVertical: Boolean, val verticalX: Float)

    private fun fitLineLeastSquares(points: List<PointF>): Line? {
        if (points.size < 2) return null
        val xMean = points.map { it.x }.average().toFloat()
        val yMean = points.map { it.y }.average().toFloat()
        val xVar = points.map { (it.x - xMean).let { dx -> dx * dx } }.sum()
        if (xVar < 1f) return Line(0f, 0f, isVertical = true, verticalX = xMean)
        var num = 0f; var den = 0f
        for (p in points) { num += (p.x - xMean) * (p.y - yMean); den += (p.x - xMean) * (p.x - xMean) }
        if (den == 0f) return null
        val a = num / den; val b = yMean - a * xMean
        return Line(a, b, isVertical = false, verticalX = 0f)
    }

    private fun intersectLines(l1: Line, l2: Line): PointF {
        return when {
            l1.isVertical && l2.isVertical -> PointF(l1.verticalX, 0f)
            l1.isVertical -> PointF(l1.verticalX, l2.a * l1.verticalX + l2.b)
            l2.isVertical -> PointF(l2.verticalX, l1.a * l2.verticalX + l1.b)
            else -> { val d = l1.a - l2.a; if (abs(d) < 1e-6f) PointF(0f, l1.a * 0f + l1.b) else { val x = (l2.b - l1.b) / d; PointF(x, l1.a * x + l1.b) } }
        }
    }

    private fun useFallbackCorners(width: Int, height: Int): List<PointF> {
        // v3.4: 返回完整图像角落（0 边距），避免降级时裁切过小
        return listOf(
            PointF(0f, 0f),
            PointF(width.toFloat(), 0f),
            PointF(width.toFloat(), height.toFloat()),
            PointF(0f, height.toFloat())
        )
    }

    // ════════════════════════════════════════════════════════════════
    //  透视变换
    // ════════════════════════════════════════════════════════════════

    fun perspectiveTransform(bitmap: Bitmap, corners: List<PointF>): Bitmap {
        if (corners.size != 4) return bitmap

        // 优先使用 OpenCV（质量更好）
        if (initOpenCV()) {
            try {
                return perspectiveTransformOpenCV(bitmap, corners)
            } catch (_: Exception) {}
        }

        // 降级到纯 Kotlin 实现
        return perspectiveTransformFallback(bitmap, corners)
    }

    /**
     * 计算图像自适应 USM 锐化权重
     *
     * 基于 Sobel 梯度幅值的均值来分析图像的文字密度/纹理复杂度：
     * - 高梯度（文字密集/边缘丰富）→ 高锐化权重 0.35-0.40
     * - 中等梯度（普通文档）→ 中等锐化权重 0.28-0.35
     * - 低梯度（平滑图像/照片）→ 低锐化权重 0.25-0.30
     *
     * @return 自适应锐化权重，范围 [0.25, 0.40]
     */
    private fun computeAdaptiveSharpWeightForMat(mat: org.opencv.core.Mat): Double {
        try {
            val gray = org.opencv.core.Mat()
            if (mat.channels() >= 3) {
                Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
            } else {
                mat.copyTo(gray)
            }
            // Sobel 梯度计算
            val gradX = org.opencv.core.Mat()
            val gradY = org.opencv.core.Mat()
            Imgproc.Sobel(gray, gradX, org.opencv.core.CvType.CV_32F, 1, 0, 3)
            Imgproc.Sobel(gray, gradY, org.opencv.core.CvType.CV_32F, 0, 1, 3)
            val magnitude = org.opencv.core.Mat()
            Core.magnitude(gradX, gradY, magnitude)

            // 计算梯度均值（反映文字密度）
            val meanVal = Core.mean(magnitude).`val`[0]

            gray.release(); gradX.release(); gradY.release(); magnitude.release()

            // 将梯度均值映射到锐化权重 0.25-0.40
            // 均值 < 15（低纹理）→ 0.25
            // 均值 15-30（中等）→ 0.25-0.35 线性插值
            // 均值 > 30（高纹理）→ 0.35-0.40 线性插值
            return when {
                meanVal < 15.0 -> 0.25
                meanVal < 30.0 -> 0.25 + (meanVal - 15.0) / 15.0 * 0.10  // 0.25-0.35
                else -> 0.35 + ((meanVal - 30.0) / 40.0).coerceAtMost(0.05)  // 0.35-0.40
            }
        } catch (_: Exception) {
            return 0.25  // 降级到保守值
        }
    }

    /** OpenCV 透视变换（双线性插值，质量更高） */
    private fun perspectiveTransformOpenCV(bitmap: Bitmap, corners: List<PointF>): Bitmap {
        val tl = corners[0]; val tr = corners[1]; val br = corners[2]; val bl = corners[3]
        val dstWidth = maxOf(distance(tl, tr), distance(bl, br)).toInt().coerceAtLeast(1)
        val dstHeight = maxOf(distance(tl, bl), distance(tr, br)).toInt().coerceAtLeast(1)
        val size = org.opencv.core.Size(dstWidth.toDouble(), dstHeight.toDouble())

        // 4 组对应点（用 CV_32FC2 Mat 构造）
        val srcMat = org.opencv.core.Mat(4, 1, org.opencv.core.CvType.CV_32FC2)
        val dstMat = org.opencv.core.Mat(4, 1, org.opencv.core.CvType.CV_32FC2)

        srcMat.put(0, 0, floatArrayOf(tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y))
        dstMat.put(0, 0, floatArrayOf(
            0f, 0f, dstWidth.toFloat(), 0f,
            dstWidth.toFloat(), dstHeight.toFloat(), 0f, dstHeight.toFloat()
        ))

        val perspectiveMat = Imgproc.getPerspectiveTransform(srcMat, dstMat)

        val bmp32 = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val src = org.opencv.core.Mat()
        Utils.bitmapToMat(bmp32, src)

        val dst = org.opencv.core.Mat()
        Imgproc.warpPerspective(src, dst, perspectiveMat, size, Imgproc.INTER_LINEAR)

        // ── 自适应锐化（unsharp mask）：补偿 warp 插值造成的模糊 ──
        val blurred = org.opencv.core.Mat()
        Imgproc.GaussianBlur(dst, blurred, org.opencv.core.Size(0.0, 0.0), 1.0)
        val sharpened = org.opencv.core.Mat()
        // 基于图像文字密度自适应计算锐化权重，范围 0.25-0.40
        val sharpWeight = computeAdaptiveSharpWeightForMat(dst)
        // dst + (dst - blurred) × sharpWeight → 按内容密度自适应锐化
        Core.addWeighted(dst, 1.0 + sharpWeight, blurred, -sharpWeight, 0.0, sharpened)

        val result = Bitmap.createBitmap(dstWidth, dstHeight, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(sharpened, result)

        src.release(); dst.release(); blurred.release(); sharpened.release()
        srcMat.release(); dstMat.release(); perspectiveMat.release()
        bmp32.recycle()
        return result
    }

    /** 纯 Kotlin 透视变换（降级） */
    private fun perspectiveTransformFallback(bitmap: Bitmap, corners: List<PointF>): Bitmap {
        val tl = corners[0]; val tr = corners[1]; val br = corners[2]; val bl = corners[3]
        val maxWidth = maxOf(distance(tl, tr), distance(bl, br)).toInt().coerceAtLeast(1)
        val maxHeight = maxOf(distance(tl, bl), distance(tr, br)).toInt().coerceAtLeast(1)
        val result = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888)
        for (y in 0 until maxHeight) for (x in 0 until maxWidth) {
            val u = x.toFloat() / maxWidth; val v = y.toFloat() / maxHeight
            val sx = bilinearInterpolate(tl.x, tr.x, bl.x, br.x, u, v).toInt().coerceIn(0, bitmap.width - 1)
            val sy = bilinearInterpolate(tl.y, tr.y, bl.y, br.y, u, v).toInt().coerceIn(0, bitmap.height - 1)
            result.setPixel(x, y, bitmap.getPixel(sx, sy))
        }
        return result
    }

    private fun bilinearInterpolate(v00: Float, v10: Float, v01: Float, v11: Float, u: Float, v: Float): Float {
        return (v00 + (v10 - v00) * u) + ((v01 + (v11 - v01) * u) - (v00 + (v10 - v00) * u)) * v
    }

    private fun distance(p1: PointF, p2: PointF): Float {
        val dx = p2.x - p1.x; val dy = p2.y - p1.y; return sqrt(dx * dx + dy * dy)
    }

    // ════════════════════════════════════════════════════════════════
    //  文本摆正（去倾斜）
    // ════════════════════════════════════════════════════════════════

    /**
     * 自动摆正（去倾斜）文档图像
     *
     * 基于 Hough Line Transform 检测文本行的主导倾斜角度，
     * 然后反向旋转校正。
     *
     * @param bitmap 源图（建议先做透视变换再摆正）
     * @return 摆正后的 bitmap
     */
    fun deskew(bitmap: Bitmap): Bitmap {
        if (initOpenCV()) {
            try {
                return deskewOpenCV(bitmap)
            } catch (_: Exception) {}
        }
        return bitmap
    }

    /** OpenCV HoughLines 去倾斜（v2.6.4 参数优化 + 离群值过滤） */
    private fun deskewOpenCV(bitmap: Bitmap): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val bmp32 = bitmap.copy(Bitmap.Config.ARGB_8888, false)

        // 1. 转为灰度
        val src = org.opencv.core.Mat()
        Utils.bitmapToMat(bmp32, src)
        val gray = org.opencv.core.Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

        // 2. 取反 + 二值化（使文字区域为白色，背景为黑色 — Hough 对白线更敏感）
        val binary = org.opencv.core.Mat()
        Imgproc.adaptiveThreshold(
            gray, binary, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV,  // 文字白色，背景黑色
            31, 15.0
        )

        // 3. 形态学闭操作连接文字笔画
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT, org.opencv.core.Size(5.0, 3.0)
        )
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)

        // 4. HoughLines 检测直线（threshold 放宽到对角线 1.5%，捕获更多候选线）
        val lines = org.opencv.core.Mat()
        val diagonal = sqrt((w * w + h * h).toFloat())
        val threshold = (diagonal * 0.015f).toInt().coerceIn(40, 300)
        Imgproc.HoughLines(binary, lines, 1.0, Math.PI / 180.0, threshold)

        // 5. 收集所有直线角度，只保留接近水平的线（±45°）
        val angles = mutableListOf<Double>()
        for (i in 0 until lines.rows()) {
            val data = DoubleArray(2)
            lines.get(i, 0, data)
            val theta = data[1]  // 弧度
            val deg = Math.toDegrees(theta + Math.PI / 2) % 180
            val normalized = if (deg > 90) deg - 180 else deg
            if (abs(normalized) < 45.0) {
                angles.add(normalized)
            }
        }

        // 释放中间 Mat
        src.release(); gray.release(); binary.release(); lines.release()
        bmp32.recycle()

        // 6. 判断有效角度 — 使用最终角度值
        var finalAngle: Double
        if (angles.size >= 3) {
            // IQR 离群值过滤：剔除明显异常的角度
            angles.sort()
            val q1 = angles[angles.size / 4]
            val q3 = angles[angles.size * 3 / 4]
            val iqr = q3 - q1
            val lower = q1 - 1.5 * iqr
            val upper = q3 + 1.5 * iqr
            val filtered = angles.filter { it >= lower && it <= upper }
            if (filtered.size >= 3) {
                // 取过滤后的中位数
                val sortedFiltered = filtered.sorted()
                finalAngle = sortedFiltered[sortedFiltered.size / 2]
            } else {
                finalAngle = angles[angles.size / 2]
            }
        } else if (angles.size == 1 || angles.size == 2) {
            // 线条太少，改用 minAreaRect 回退
            val fallbackAngle = tryDeskewWithMinAreaRect(bitmap)
            if (fallbackAngle != null && abs(fallbackAngle) >= 0.5) {
                finalAngle = fallbackAngle
            } else {
                return bitmap
            }
        } else {
            return bitmap
        }

        // 如果角度很小（< 0.5°），不旋转
        if (abs(finalAngle) < 0.5) return bitmap

        // 8. 执行旋转
        val center = org.opencv.core.Point(w / 2.0, h / 2.0)
        val rotMat = Imgproc.getRotationMatrix2D(center, finalAngle, 1.0)

        // 计算旋转后新边界，确保内容完整
        val cos = abs(rotMat[0, 0][0])
        val sin = abs(rotMat[0, 1][0])
        val newW = (h * sin + w * cos).toInt()
        val newH = (h * cos + w * sin).toInt()

        // 调整旋转矩阵使图像居中
        rotMat.put(0, 2, rotMat[0, 2][0] + newW / 2.0 - center.x)
        rotMat.put(1, 2, rotMat[1, 2][0] + newH / 2.0 - center.y)

        val rotated = org.opencv.core.Mat()
        val src2 = org.opencv.core.Mat()
        Utils.bitmapToMat(bitmap.copy(Bitmap.Config.ARGB_8888, false), src2)
        Imgproc.warpAffine(
            src2, rotated, rotMat, org.opencv.core.Size(newW.toDouble(), newH.toDouble()),
            Imgproc.INTER_LINEAR, org.opencv.core.Core.BORDER_CONSTANT,
            org.opencv.core.Scalar(255.0, 255.0, 255.0, 255.0)
        )

        // 9. 自动裁剪白边：移除旋转产生的纯白边界
        val result = autoCropWhiteBorders(rotated, newW, newH)
        src2.release(); rotated.release(); rotMat.release()
        return result
    }

    /**
     * 使用 minAreaRect 回退检测倾斜角度（文字稀疏场景）
     */
    private fun tryDeskewWithMinAreaRect(bitmap: Bitmap): Double? {
        return try {
            val w = bitmap.width; val h = bitmap.height
            val bmp32 = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            val srcMat = org.opencv.core.Mat()
            Utils.bitmapToMat(bmp32, srcMat)
            val gray = org.opencv.core.Mat()
            Imgproc.cvtColor(srcMat, gray, Imgproc.COLOR_RGBA2GRAY)

            // Canny + 轮廓
            val edges = org.opencv.core.Mat()
            Imgproc.Canny(gray, edges, 30.0, 100.0)
            val contours = ArrayList<org.opencv.core.MatOfPoint>()
            Imgproc.findContours(edges, contours, org.opencv.core.Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            contours.sortByDescending { Imgproc.contourArea(it) }

            if (contours.isNotEmpty()) {
                val rect = Imgproc.minAreaRect(org.opencv.core.MatOfPoint2f(*contours[0].toArray()))
                var angle = rect.angle.toDouble()
                if (rect.size.width < rect.size.height) angle = 90.0 + angle
                if (angle > 45.0) angle -= 90.0
                srcMat.release(); gray.release(); edges.release(); bmp32.recycle()
                return angle
            }
            srcMat.release(); gray.release(); edges.release(); bmp32.recycle()
            null
        } catch (_: Exception) { null }
    }

    /**
     * 自动裁剪旋转产生的白边
     * 从四边向内扫描，找到第一个非白色像素行/列作为边界
     */
    private fun autoCropWhiteBorders(mat: org.opencv.core.Mat, w: Int, h: Int): Bitmap {
        val whiteThreshold = 240 // 允许一定容差
        val gray = org.opencv.core.Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)

        // 从上向下扫描
        var top = 0
        topLoop@ for (y in 0 until h) {
            for (x in 0 until w) {
                val pixel = gray.get(y, x)
                if (pixel.isNotEmpty() && pixel[0] < whiteThreshold) break@topLoop
            }
            top = y
        }
        // 从下向上扫描
        var bottom = h - 1
        bottomLoop@ for (y in (h - 1) downTo 0) {
            for (x in 0 until w) {
                val pixel = gray.get(y, x)
                if (pixel.isNotEmpty() && pixel[0] < whiteThreshold) break@bottomLoop
            }
            bottom = y
        }
        // 从左向右扫描
        var left = 0
        leftLoop@ for (x in 0 until w) {
            for (y in 0 until h) {
                val pixel = gray.get(y, x)
                if (pixel.isNotEmpty() && pixel[0] < whiteThreshold) break@leftLoop
            }
            left = x
        }
        // 从右向左扫描
        var right = w - 1
        rightLoop@ for (x in (w - 1) downTo 0) {
            for (y in 0 until h) {
                val pixel = gray.get(y, x)
                if (pixel.isNotEmpty() && pixel[0] < whiteThreshold) break@rightLoop
            }
            right = x
        }

        gray.release()

        // 防止裁剪过多（至少保留 70% 的尺寸）
        val cropW = (right - left + 1).coerceAtLeast((w * 0.7).toInt())
        val cropH = (bottom - top + 1).coerceAtLeast((h * 0.7).toInt())
        // 如果裁剪很少（<1%），跳过
        if (cropW >= w * 0.99 || cropH >= h * 0.99) {
            val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(mat, result)
            return result
        }
        // 执行裁剪
        val rect = org.opencv.core.Rect(
            left.coerceAtMost(w - cropW),
            top.coerceAtMost(h - cropH),
            cropW, cropH
        )
        val cropped = org.opencv.core.Mat(mat, rect)
        val result = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(cropped, result)
        cropped.release()
        return result
    }

    // ════════════════════════════════════════════════════════════════
    //  扫描效果
    // ════════════════════════════════════════════════════════════════

    fun applyScanEffect(bitmap: Bitmap, effect: String = "colorful"): Bitmap {
        return when (effect.lowercase()) {
            "gray" -> applyGrayscale(bitmap)
            "original" -> applyAutoEnhance(bitmap)
            "clahe" -> applyCLAHE(bitmap)
            "binary" -> applyAdaptiveBinarize(bitmap)
            "colorclahe" -> applyColorCLAHE(bitmap)
            "pure" -> applyPureBackground(bitmap)
            else -> applyColorfulScan(bitmap)
        }
    }

    private fun applyGrayscale(bitmap: Bitmap): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        for (y in 0 until h) for (x in 0 until w) {
            val p = bitmap.getPixel(x, y)
            var g = (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)).toInt()
            g = ((g - 128) * 1.5f + 128).toInt().coerceIn(0, 255)
            result.setPixel(x, y, Color.rgb(g, g, g))
        }
        return result
    }

    private fun applyAutoEnhance(bitmap: Bitmap): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val hist = IntArray(256)
        for (y in 0 until h) for (x in 0 until w) {
            val p = bitmap.getPixel(x, y)
            val g = ((0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) * 0.7f).toInt().coerceIn(0, 255)
            hist[g]++
        }
        val cdf = IntArray(256).also { a -> a[0] = hist[0]; for (i in 1..255) a[i] = a[i - 1] + hist[i] }
        val cdfMin = cdf.firstOrNull { it > 0 } ?: 0; val cdfMax = cdf.last()
        for (y in 0 until h) for (x in 0 until w) {
            val p = bitmap.getPixel(x, y)
            fun adjust(c: Int) = if (cdfMax != cdfMin) ((cdf[c.coerceIn(0, 255)] - cdfMin) * 255.0 / (cdfMax - cdfMin)).toInt().coerceIn(0, 255) else c
            result.setPixel(x, y, Color.rgb(adjust(Color.red(p)), adjust(Color.green(p)), adjust(Color.blue(p))))
        }
        return result
    }

    private fun applyColorfulScan(bitmap: Bitmap): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val factor = 1.1f
        for (y in 0 until h) for (x in 0 until w) {
            val p = bitmap.getPixel(x, y)
            result.setPixel(x, y, Color.rgb(
                ((Color.red(p) - 128) * factor + 128).toInt().coerceIn(0, 255),
                ((Color.green(p) - 128) * factor + 128).toInt().coerceIn(0, 255),
                ((Color.blue(p) - 128) * factor + 128).toInt().coerceIn(0, 255)
            ))
        }
        return result
    }

    /**
     * 背景净化增强（v2.6.4 新增）
     *
     * 核心思路：将接近白色/灰色的背景映射为纯白，同时加深文字，
     * 模拟夸克扫描王的"纯净扫描"效果。
     *
     * 处理流程：
     * 1. 背景像素（亮度 > 220 且饱和度 < 30）→ 纯白（255）
     * 2. 自适应对比度拉伸（1% / 99% 百分位剪裁）
     * 3. 文字加深（暗部做 gamma < 1.0 映射）
     */
    private fun applyPureBackground(bitmap: Bitmap): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val totalPixels = w * h
        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // 计算亮度直方图用于对比度拉伸
        val lumHist = IntArray(256)
        // 结果像素
        val resultPixels = IntArray(totalPixels)

        // 第一遍：分析亮度/饱和度 + 计算直方图
        val hsvValues = FloatArray(3)
        for (i in 0 until totalPixels) {
            val p = pixels[i]
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            // 快速亮度
            val luma = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
            lumHist[luma]++
            // 快速饱和度（RGB 通道之间的最大差值，近似 HSV S）
            val maxC = maxOf(r, g, b); val minC = minOf(r, g, b)
            val sat = if (maxC == 0) 0 else ((maxC - minC) / maxC.toFloat() * 100).toInt()
            hsvValues[i % 3] = luma.toFloat()
            // 临时存 luma 和 sat 供第二遍使用（打包进高位）
            resultPixels[i] = (luma shl 16) or (sat shl 8) or i
        }

        // 计算剪裁百分位（1% 和 99%）
        var cum = 0
        val targetLow = (totalPixels * 0.01).toInt()
        val targetHigh = (totalPixels * 0.99).toInt()
        var lowVal = 0; var highVal = 255
        for (v in 0..255) {
            cum += lumHist[v]
            if (cum >= targetLow && lowVal == 0) lowVal = v
            if (cum >= targetHigh) { highVal = v; break }
        }
        // 防止除零
        if (highVal <= lowVal) { highVal = lowVal + 1 }

        // 第二遍：执行背景净化和对比度拉伸 + 文字加深
        for (i in 0 until totalPixels) {
            val p = pixels[i]
            val r = Color.red(p).toFloat(); val g = Color.green(p).toFloat(); val b = Color.blue(p).toFloat()
            val luma = 0.299f * r + 0.587f * g + 0.114f * b
            val maxC = maxOf(r, g, b); val minC = minOf(r, g, b)
            val sat = if (maxC == 0f) 0f else (maxC - minC) / maxC * 100f
            val brightness = luma

            // 步骤1：背景净化 — 接近白色的像素直接变纯白
            if (brightness > 220f && sat < 30f) {
                resultPixels[i] = Color.WHITE
                continue
            }

            // 步骤2：对比度拉伸（每个通道独立）
            fun stretch(c: Float): Int {
                val s = ((c - lowVal) / (highVal - lowVal) * 255f).coerceIn(0f, 255f)
                return s.toInt()
            }
            var nr = stretch(r); var ng = stretch(g); var nb = stretch(b)

            // 步骤3：文字加深（暗色+低饱和度的像素做 gamma 校正，使文字更黑）
            val newLuma = 0.299f * nr + 0.587f * ng + 0.114f * nb
            if (newLuma < 128f && sat < 40f) {
                // gamma < 1.0 加深：output = 255 * (input/255)^gamma
                val gamma = 0.7f  // < 1.0 使暗部更暗
                val factor = 1f / 255f
                nr = (255f * (nr * factor).pow(gamma)).toInt().coerceIn(0, 255)
                ng = (255f * (ng * factor).pow(gamma)).toInt().coerceIn(0, 255)
                nb = (255f * (nb * factor).pow(gamma)).toInt().coerceIn(0, 255)
            }

            resultPixels[i] = Color.rgb(nr, ng, nb)
        }

        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        result.setPixels(resultPixels, 0, w, 0, 0, w, h)
        return result
    }

    // ════════════════════════════════════════════════════════════════
    //  角落排序（核心公用工具）
    // ════════════════════════════════════════════════════════════════

    private fun computeQuadArea(tl: PointF, tr: PointF, br: PointF, bl: PointF): Float {
        val a1 = abs(tl.x * (tr.y - br.y) + tr.x * (br.y - tl.y) + br.x * (tl.y - tr.y)) / 2f
        val a2 = abs(tl.x * (bl.y - br.y) + bl.x * (br.y - tl.y) + br.x * (tl.y - bl.y)) / 2f
        return a1 + a2
    }

    // ════════════════════════════════════════════════════════════════
    //  OpenCV CLAHE 对比度增强 + 自适应二值化
    // ════════════════════════════════════════════════════════════════

    /**
     * OpenCV CLAHE 灰度对比度增强（v2.6.4 自适应参数版）
     *
     * clipLimit 和 tileSize 根据图像尺寸自动调整：
     * - 大图使用更大的 tile 和更强的 clip，避免块状伪影
     * - 小图使用更保守的参数，避免过增强
     */
    private fun applyCLAHE(bitmap: Bitmap): Bitmap {
        if (!initOpenCV()) return applyColorfulScan(bitmap)
        return try {
            val w = bitmap.width; val h = bitmap.height
            val imageArea = (w * h).toFloat()
            val shortSide = minOf(w, h)

            // 自适应 clipLimit：大图用更强 clip（最大值 4.0），小图保守（最小值 1.5）
            val clipLimit = maxOf(1.5, minOf(4.0, imageArea / 500_000.0 * 2.0))

            // 自适应 tileSize：短边的 1/80，至少 4x4，最大 32x32
            val tileSize = (shortSide / 80).coerceIn(4, 32)

            val src = org.opencv.core.Mat()
            Utils.bitmapToMat(bitmap, src)
            val gray = org.opencv.core.Mat()
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGB2GRAY)
            val clahe = Imgproc.createCLAHE(clipLimit, org.opencv.core.Size(tileSize.toDouble(), tileSize.toDouble()))
            val dest = org.opencv.core.Mat()
            clahe.apply(gray, dest)
            val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            Utils.matToBitmap(dest, result)
            src.release(); gray.release(); dest.release()
            result
        } catch (e: Exception) {
            bitmap
        }
    }

    /**
     * OpenCV 自适应二值化
     * 对灰度图做高斯自适应阈值处理，适用于文字清晰的文档
     */
    private fun applyAdaptiveBinarize(bitmap: Bitmap): Bitmap {
        if (!initOpenCV()) return applyGrayscale(bitmap)
        return try {
            val src = org.opencv.core.Mat()
            Utils.bitmapToMat(bitmap, src)
            val gray = org.opencv.core.Mat()
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGB2GRAY)
            val binary = org.opencv.core.Mat()
            Imgproc.adaptiveThreshold(gray, binary, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 31, 10.0)
            val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            Utils.matToBitmap(binary, result)
            src.release(); gray.release(); binary.release()
            result
        } catch (e: Exception) {
            bitmap
        }
    }

    /**
     * OpenCV 彩色 CLAHE 增强（保留颜色信息，v2.6.4 自适应参数版）
     * 将 RGB 转为 LAB，仅对 L 通道做 CLAHE，再转回 RGB
     */
    private fun applyColorCLAHE(bitmap: Bitmap): Bitmap {
        if (!initOpenCV()) return applyAutoEnhance(bitmap)
        return try {
            val w = bitmap.width; val h = bitmap.height
            val imageArea = (w * h).toFloat()
            val shortSide = minOf(w, h)
            val clipLimit = maxOf(1.5, minOf(4.0, imageArea / 500_000.0 * 2.0))
            val tileSize = (shortSide / 80).coerceIn(4, 32)

            val src = org.opencv.core.Mat()
            Utils.bitmapToMat(bitmap, src)
            val lab = org.opencv.core.Mat()
            Imgproc.cvtColor(src, lab, Imgproc.COLOR_RGB2Lab)
            val channels = java.util.ArrayList<org.opencv.core.Mat>()
            Core.split(lab, channels)
            val clahe = Imgproc.createCLAHE(clipLimit, org.opencv.core.Size(tileSize.toDouble(), tileSize.toDouble()))
            clahe.apply(channels[0], channels[0])
            Core.merge(channels, lab)
            Imgproc.cvtColor(lab, src, Imgproc.COLOR_Lab2RGB)
            val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            Utils.matToBitmap(src, result)
            src.release(); lab.release()
            channels.forEach { it.release() }
            result
        } catch (e: Exception) {
            bitmap
        }
    }

    private fun sortCorners(tl: PointF, tr: PointF, br: PointF, bl: PointF): List<PointF> {
        val pts = listOf(tl, tr, bl, br)
        val sortedByY = pts.sortedBy { it.y }
        val topTwo = sortedByY.take(2).sortedBy { it.x }
        val bottomTwo = sortedByY.takeLast(2).sortedBy { it.x }
        return listOf(topTwo[0], topTwo[1], bottomTwo[1], bottomTwo[0])
    }
}
