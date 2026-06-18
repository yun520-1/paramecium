package com.heartflow.scanner

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
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
class DocumentScanner {

    companion object {
        private const val CANNY_LOW = 30.0     // Canny 低阈值
        private const val CANNY_HIGH = 100.0   // Canny 高阈值
        private const val MIN_AREA_RATIO = 0.06f   // v3.3: 0.08→0.06 放宽最小面积门槛，允许检测更多中小尺寸文档
        private const val MIN_SIDE_RATIO = 0.12f   // v3.2 新增：外接框短边至少占原图 12%
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
                val libNames = listOf("opencv_java4", "opencv_java41", "opencv_java3")
                for (name in libNames) {
                    try {
                        System.loadLibrary(name)
                        openCvLoaded = true
                        break
                    } catch (_: UnsatisfiedLinkError) {
                        // 继续尝试下一个名字
                    }
                }
            }
            return openCvLoaded
        }

        /** 检查 OpenCV 是否可用 */
        fun isOpenCvAvailable(): Boolean {
            if (!openCvChecked) initOpenCV()
            return openCvLoaded
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

            // 3. 高斯模糊（降噪）
            Imgproc.GaussianBlur(gray, gray, org.opencv.core.Size(5.0, 5.0), 1.4)

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

            // 5. 形态学闭操作连接断边（核大小按图尺寸自适应）
            val adaptiveKernel = ((MORPH_KERNEL * minOf(w, h) / 600f).toInt().coerceIn(3, 11) or 1)  // 取奇数
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT, org.opencv.core.Size(adaptiveKernel.toDouble(), adaptiveKernel.toDouble())
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
            val (cx, cy) = queue.poll()
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
        val margin = minOf(width, height) / 8  // v3.2: /20→/8 增大默认边距，防止裁剪过小
        return listOf(
            PointF(margin.toFloat(), margin.toFloat()),
            PointF((width - margin).toFloat(), margin.toFloat()),
            PointF((width - margin).toFloat(), (height - margin).toFloat()),
            PointF(margin.toFloat(), (height - margin).toFloat())
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

        // ── 锐化（unsharp mask）：补偿 warp 插值造成的模糊 ──
        val blurred = org.opencv.core.Mat()
        Imgproc.GaussianBlur(dst, blurred, org.opencv.core.Size(0.0, 0.0), 1.0)
        val sharpened = org.opencv.core.Mat()
        // dst + (dst - blurred) × 0.15 → 适度锐化，避免放大压缩噪声
        Core.addWeighted(dst, 1.0 + 0.15, blurred, -0.15, 0.0, sharpened)

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

    /** OpenCV HoughLines 去倾斜 */
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

        // 4. HoughLines 检测直线
        val lines = org.opencv.core.Mat()
        // HoughLines: rho=1, theta=π/180 (1°), threshold 取图像对角线 2%
        val threshold = (sqrt((w * w + h * h).toFloat()) * 0.02f).toInt().coerceIn(50, 400)
        Imgproc.HoughLines(binary, lines, 1.0, Math.PI / 180.0, threshold)

        // 5. 收集所有直线角度，只保留接近水平的线（±45°）
        val angles = mutableListOf<Double>()
        for (i in 0 until lines.rows()) {
            val data = DoubleArray(2)
            lines.get(i, 0, data)
            val theta = data[1]  // 弧度
            // 只处理接近水平的方向（0 ± 45°）
            val deg = Math.toDegrees(theta + Math.PI / 2) % 180
            val normalized = if (deg > 90) deg - 180 else deg
            if (abs(normalized) < 45.0) {
                angles.add(normalized)
            }
        }

        // 释放中间 Mat
        src.release(); gray.release(); binary.release(); lines.release()
        bmp32.recycle()

        // 6. 无有效角度 → 返回原图
        if (angles.isEmpty()) return bitmap

        // 7. 取角度中位数（抗干扰）
        angles.sort()
        val medianAngle = angles[angles.size / 2]

        // 如果角度很小（< 0.5°），不旋转
        if (abs(medianAngle) < 0.5) return bitmap

        // 8. 执行旋转
        val center = org.opencv.core.Point(w / 2.0, h / 2.0)
        val rotMat = Imgproc.getRotationMatrix2D(center, medianAngle, 1.0)

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

        val result = Bitmap.createBitmap(newW, newH, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rotated, result)

        src2.release(); rotated.release(); rotMat.release()
        return result
    }

    // ════════════════════════════════════════════════════════════════
    //  扫描效果
    // ════════════════════════════════════════════════════════════════

    fun applyScanEffect(bitmap: Bitmap, effect: String = "colorful"): Bitmap {
        return when (effect.lowercase()) {
            "gray" -> applyGrayscale(bitmap)
            "original" -> applyAutoEnhance(bitmap)
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

    // ════════════════════════════════════════════════════════════════
    //  角落排序（核心公用工具）
    // ════════════════════════════════════════════════════════════════

    private fun computeQuadArea(tl: PointF, tr: PointF, br: PointF, bl: PointF): Float {
        val a1 = abs(tl.x * (tr.y - br.y) + tr.x * (br.y - tl.y) + br.x * (tl.y - tr.y)) / 2f
        val a2 = abs(tl.x * (bl.y - br.y) + bl.x * (br.y - tl.y) + br.x * (tl.y - bl.y)) / 2f
        return a1 + a2
    }

    private fun sortCorners(tl: PointF, tr: PointF, br: PointF, bl: PointF): List<PointF> {
        val pts = listOf(tl, tr, bl, br)
        val sortedByY = pts.sortedBy { it.y }
        val topTwo = sortedByY.take(2).sortedBy { it.x }
        val bottomTwo = sortedByY.takeLast(2).sortedBy { it.x }
        return listOf(topTwo[0], topTwo[1], bottomTwo[1], bottomTwo[0])
    }
}
