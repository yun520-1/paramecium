package com.heartflow.scanner

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 文档扫描器核心类
 * 实现边缘检测、透视变换和扫描效果处理
 *
 * 核心算法基于 Otsu 自适应阈值 + 形态学操作 + 边界扫描，
 * 自动找到文档四边形的四个角点，实现精准自动裁切。
 *
 * 算法流程：
 * 1. Sobel 边缘检测 → 梯度幅值图
 * 2. Otsu 自适应阈值二值化（替代固定阈值）
 * 3. 形态学膨胀连接断边
 * 4. 四边扫描找到文档边界轮廓点
 * 5. 最小二乘法拟合四条直线
 * 6. 直线求交得到四个角点
 * 7. 透视变换矫正为矩形
 */
class DocumentScanner {

    companion object {
        // 最小面积阈值（相对于图像面积的比例）
        private const val MIN_AREA_RATIO = 0.05f

        // 边界扫描步长（跳过部分像素加速）
        private const val SCAN_STEP = 3

        // 最小边界像素点数才能拟合直线
        private const val MIN_BOUNDARY_POINTS = 5
    }

    /**
     * 检测图像中的文档边缘
     * 返回四个角的坐标点（顺序：左上、右上、右下、左下）
     */
    fun detectCorners(bitmap: Bitmap): List<PointF> {
        val width = bitmap.width
        val height = bitmap.height

        // 缩小图像以加快处理速度
        val scale = minOf(1f, 800f / maxOf(width, height))
        val scaledWidth = (width * scale).toInt().coerceAtLeast(200)
        val scaledHeight = (height * scale).toInt().coerceAtLeast(200)

        val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)

        // 1. 边缘检测 + 自适应阈值
        val edgeMap = detectEdgesAdaptive(scaled)

        // 2. 形态学膨胀连接边缘
        val dilated = morphologicalDilate(edgeMap, scaledWidth, scaledHeight)

        // 3. 四边边界扫描
        val corners = findQuadByBoundaryScanning(dilated, scaledWidth, scaledHeight)

        // 4. 映射回原图坐标
        val ratioX = width.toFloat() / scaledWidth
        val ratioY = height.toFloat() / scaledHeight

        scaled.recycle()

        return corners.map { PointF(it.x * ratioX, it.y * ratioY) }
    }

    /**
     * Sobel 边缘检测 + Otsu 自适应阈值二值化
     * 返回二值化边缘图（0 = 非边缘, 255 = 边缘）
     */
    private fun detectEdgesAdaptive(bitmap: Bitmap): Array<IntArray> {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 转换为灰度并计算梯度幅值
        val gray = IntArray(width * height)
        val magnitudes = IntArray(width * height)
        var magMax = 0

        val sobelX = arrayOf(
            intArrayOf(-1, 0, 1),
            intArrayOf(-2, 0, 2),
            intArrayOf(-1, 0, 1)
        )
        val sobelY = arrayOf(
            intArrayOf(-1, -2, -1),
            intArrayOf(0, 0, 0),
            intArrayOf(1, 2, 1)
        )

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var gx = 0
                var gy = 0

                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val r = Color.red(pixels[(y + ky) * width + (x + kx)])
                        val g = Color.green(pixels[(y + ky) * width + (x + kx)])
                        val b = Color.blue(pixels[(y + ky) * width + (x + kx)])
                        val grayVal = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                        gray[(y + ky) * width + (x + kx)] = grayVal
                        gx += sobelX[ky + 1][kx + 1] * grayVal
                        gy += sobelY[ky + 1][kx + 1] * grayVal
                    }
                }

                val magnitude = sqrt((gx * gx + gy * gy).toDouble()).toInt()
                magnitudes[y * width + x] = magnitude
                if (magnitude > magMax) magMax = magnitude
            }
        }

        // Otsu 自适应阈值
        val threshold = if (magMax > 0) {
            computeOtsuThreshold(magnitudes, magMax)
        } else {
            30
        }

        // 二值化
        val edges = Array(height) { IntArray(width) }
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                edges[y][x] = if (magnitudes[y * width + x] > threshold) 255 else 0
            }
        }

        return edges
    }

    /**
     * Otsu 自适应阈值计算
     * 分析梯度幅值直方图，找到使类间方差最大的阈值
     */
    private fun computeOtsuThreshold(magnitudes: IntArray, maxVal: Int): Int {
        val histSize = minOf(maxVal + 1, 256)
        val histogram = IntArray(histSize)
        for (m in magnitudes) {
            val idx = m.coerceIn(0, histSize - 1)
            histogram[idx]++
        }

        val total = magnitudes.size
        var sum = 0.0
        for (i in 0 until histSize) {
            sum += i.toDouble() * histogram[i]
        }

        var sumB = 0.0
        var wB = 0
        var maxVariance = 0.0
        var threshold = 15 // 保底阈值，避免全黑

        for (t in 0 until histSize) {
            wB += histogram[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break

            sumB += t.toDouble() * histogram[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val diff = mB - mF
            val between = wB.toDouble() * wF.toDouble() * diff * diff

            if (between > maxVariance) {
                maxVariance = between
                threshold = t
            }
        }

        return threshold.coerceIn(10, 200)
    }

    /**
     * 形态学膨胀操作
     * 使用 3x3 结构元素连接断裂的边缘
     */
    private fun morphologicalDilate(
        edges: Array<IntArray>,
        width: Int,
        height: Int
    ): Array<IntArray> {
        val result = Array(height) { IntArray(width) }
        val kernelSize = 3
        val halfK = kernelSize / 2

        for (y in halfK until height - halfK) {
            for (x in halfK until width - halfK) {
                var maxVal = 0
                for (ky in -halfK..halfK) {
                    for (kx in -halfK..halfK) {
                        if (edges[y + ky][x + kx] > maxVal) {
                            maxVal = edges[y + ky][x + kx]
                        }
                    }
                }
                result[y][x] = maxVal
            }
        }

        return result
    }

    /**
     * 通过四边边界扫描找到文档四边形
     *
     * 算法：
     * 1. 从图像四边向内扫描，找到第一个密集边缘像素带
     * 2. 收集各边的边界点
     * 3. 用最小二乘法拟合直线
     * 4. 直线求交得到四个角点
     */
    private fun findQuadByBoundaryScanning(
        edges: Array<IntArray>,
        width: Int,
        height: Int
    ): List<PointF> {
        // 从四边扫描收集边界点
        val topPoints = scanTopDown(edges, width, height)
        val bottomPoints = scanBottomUp(edges, width, height)
        val leftPoints = scanLeftRight(edges, width, height)
        val rightPoints = scanRightLeft(edges, width, height)

        // 如果没有找到足够的点，退化到使用图像边缘
        if (topPoints.size < MIN_BOUNDARY_POINTS ||
            bottomPoints.size < MIN_BOUNDARY_POINTS ||
            leftPoints.size < MIN_BOUNDARY_POINTS ||
            rightPoints.size < MIN_BOUNDARY_POINTS) {
            return useFallbackCorners(width, height)
        }

        // 过滤离群点（用中位数+标准差过滤）
        val filteredTop = filterOutliers(topPoints, width / 4f)
        val filteredBottom = filterOutliers(bottomPoints, width / 4f)
        val filteredLeft = filterOutliers(leftPoints, height / 4f)
        val filteredRight = filterOutliers(rightPoints, height / 4f)

        if (filteredTop.size < MIN_BOUNDARY_POINTS ||
            filteredBottom.size < MIN_BOUNDARY_POINTS ||
            filteredLeft.size < MIN_BOUNDARY_POINTS ||
            filteredRight.size < MIN_BOUNDARY_POINTS) {
            return useFallbackCorners(width, height)
        }

        // 最小二乘法拟合直线
        val topLine = fitLineLeastSquares(filteredTop)
        val bottomLine = fitLineLeastSquares(filteredBottom)
        val leftLine = fitLineLeastSquares(filteredLeft)
        val rightLine = fitLineLeastSquares(filteredRight)

        if (topLine == null || bottomLine == null || leftLine == null || rightLine == null) {
            return useFallbackCorners(width, height)
        }

        // 四条直线求交得到四个角点
        val tl = intersectLines(topLine, leftLine)
        val tr = intersectLines(topLine, rightLine)
        val bl = intersectLines(bottomLine, leftLine)
        val br = intersectLines(bottomLine, rightLine)

        // 规范化角点顺序（左上、右上、右下、左下）
        val corners = listOf(tl, tr, bl, br)
        if (corners.any { it.x.isNaN() || it.y.isNaN() }) {
            return useFallbackCorners(width, height)
        }

        // 检查四边形面积是否合理
        val quadArea = computeQuadArea(tl, tr, br, bl)
        val imageArea = (width * height).toFloat()
        if (quadArea < imageArea * MIN_AREA_RATIO) {
            return useFallbackCorners(width, height)
        }

        return sortCorners(tl, tr, br, bl)
    }

    /**
     * 从上向下扫描，找到上边界
     * 对每个扫描列，从上到下找第一个边缘像素密集点
     */
    private fun scanTopDown(
        edges: Array<IntArray>,
        width: Int,
        height: Int
    ): List<PointF> {
        val points = mutableListOf<PointF>()
        val scanWidth = width / 4  // 跳过边缘区域，减少干扰
        val margin = width / 8

        for (x in margin until width - margin step SCAN_STEP) {
            var foundY = -1
            var maxEdges = 0
            val windowSize = 5

            // 从上往下扫描
            for (y in 0 until height * 3 / 4) {
                var edgeCount = 0
                for (wy in y until (y + windowSize).coerceAtMost(height)) {
                    for (wx in (x - 1).coerceAtLeast(0)..(x + 1).coerceAtMost(width - 1)) {
                        if (edges[wy][wx] > 0) edgeCount++
                    }
                }
                if (edgeCount > maxEdges) {
                    maxEdges = edgeCount
                    foundY = y
                }
                // 找到足够强的边缘后提前停止
                if (edgeCount >= 4) {
                    foundY = y
                    break
                }
            }

            if (foundY > 0 && maxEdges > 1) {
                points.add(PointF(x.toFloat(), foundY.toFloat()))
            }
        }

        return points
    }

    /**
     * 从下向上扫描，找到下边界
     */
    private fun scanBottomUp(
        edges: Array<IntArray>,
        width: Int,
        height: Int
    ): List<PointF> {
        val points = mutableListOf<PointF>()
        val margin = width / 8

        for (x in margin until width - margin step SCAN_STEP) {
            var foundY = -1
            var maxEdges = 0
            val windowSize = 5

            for (y in height - 1 downTo height / 4) {
                var edgeCount = 0
                for (wy in (y - windowSize).coerceAtLeast(0)..y) {
                    for (wx in (x - 1).coerceAtLeast(0)..(x + 1).coerceAtMost(width - 1)) {
                        if (edges[wy][wx] > 0) edgeCount++
                    }
                }
                if (edgeCount > maxEdges) {
                    maxEdges = edgeCount
                    foundY = y
                }
                if (edgeCount >= 4) {
                    foundY = y
                    break
                }
            }

            if (foundY > 0 && foundY < height - 1 && maxEdges > 1) {
                points.add(PointF(x.toFloat(), foundY.toFloat()))
            }
        }

        return points
    }

    /**
     * 从左向右扫描，找到左边界
     */
    private fun scanLeftRight(
        edges: Array<IntArray>,
        width: Int,
        height: Int
    ): List<PointF> {
        val points = mutableListOf<PointF>()
        val margin = height / 8

        for (y in margin until height - margin step SCAN_STEP) {
            var foundX = -1
            var maxEdges = 0
            val windowSize = 5

            for (x in 0 until width * 3 / 4) {
                var edgeCount = 0
                for (wy in (y - 1).coerceAtLeast(0)..(y + 1).coerceAtMost(height - 1)) {
                    for (wx in x until (x + windowSize).coerceAtMost(width)) {
                        if (edges[wy][wx] > 0) edgeCount++
                    }
                }
                if (edgeCount > maxEdges) {
                    maxEdges = edgeCount
                    foundX = x
                }
                if (edgeCount >= 4) {
                    foundX = x
                    break
                }
            }

            if (foundX > 0 && maxEdges > 1) {
                points.add(PointF(foundX.toFloat(), y.toFloat()))
            }
        }

        return points
    }

    /**
     * 从右向左扫描，找到右边界
     */
    private fun scanRightLeft(
        edges: Array<IntArray>,
        width: Int,
        height: Int
    ): List<PointF> {
        val points = mutableListOf<PointF>()
        val margin = height / 8

        for (y in margin until height - margin step SCAN_STEP) {
            var foundX = -1
            var maxEdges = 0
            val windowSize = 5

            for (x in width - 1 downTo width / 4) {
                var edgeCount = 0
                for (wy in (y - 1).coerceAtLeast(0)..(y + 1).coerceAtMost(height - 1)) {
                    for (wx in (x - windowSize).coerceAtLeast(0)..x) {
                        if (edges[wy][wx] > 0) edgeCount++
                    }
                }
                if (edgeCount > maxEdges) {
                    maxEdges = edgeCount
                    foundX = x
                }
                if (edgeCount >= 4) {
                    foundX = x
                    break
                }
            }

            if (foundX > 0 && foundX < width - 1 && maxEdges > 1) {
                points.add(PointF(foundX.toFloat(), y.toFloat()))
            }
        }

        return points
    }

    /**
     * 过滤离群点
     * 使用中位数 + MAD（中位数绝对偏差）
     */
    private fun filterOutliers(points: List<PointF>, maxDeviation: Float): List<PointF> {
        if (points.size < 4) return points

        // 判断是水平方向的点集（上下边界）还是垂直方向的点集（左右边界）
        val isHorizontal = points.all { p ->
            points.count { it.y == p.y } < points.size / 2
        }

        val values = if (isHorizontal) {
            points.map { it.y }
        } else {
            points.map { it.x }
        }

        val sorted = values.sorted()
        val median = sorted[sorted.size / 2]

        // 计算 MAD
        val absDeviations = values.map { abs(it - median) }.sorted()
        val mad = absDeviations[absDeviations.size / 2].coerceAtLeast(1f)

        // 使用 3 * MAD 作为阈值
        val threshold = (3.0 * mad).toFloat().coerceAtLeast(maxDeviation * 0.3f)

        return points.filterIndexed { index, _ ->
            abs(values[index] - median) <= threshold
        }
    }

    /**
     * 最小二乘法拟合直线 y = a*x + b
     * 返回 (a, b) 或 null（点太少或垂直线）
     */
    private data class Line(val a: Float, val b: Float, val isVertical: Boolean, val verticalX: Float)

    private fun fitLineLeastSquares(points: List<PointF>): Line? {
        if (points.size < 2) return null

        // 检查是否为垂直线（x方差过小）
        val xMean = points.map { it.x }.average().toFloat()
        val yMean = points.map { it.y }.average().toFloat()

        val xVariance = points.map { (it.x - xMean).let { dx -> dx * dx } }.sum()
        if (xVariance < 1f) {
            // 垂直线
            return Line(0f, 0f, isVertical = true, verticalX = xMean)
        }

        // 最小二乘：a = Σ((x-x̄)(y-ȳ)) / Σ((x-x̄)²)
        var num = 0f
        var den = 0f
        for (p in points) {
            num += (p.x - xMean) * (p.y - yMean)
            den += (p.x - xMean) * (p.x - xMean)
        }

        if (den == 0f) return null

        val a = num / den
        val b = yMean - a * xMean

        return Line(a, b, isVertical = false, verticalX = 0f)
    }

    /**
     * 两条直线求交点
     */
    private fun intersectLines(l1: Line, l2: Line): PointF {
        return when {
            l1.isVertical && l2.isVertical -> PointF(l1.verticalX, 0f)
            l1.isVertical -> {
                val x = l1.verticalX
                val y = l2.a * x + l2.b
                PointF(x, y)
            }
            l2.isVertical -> {
                val x = l2.verticalX
                val y = l1.a * x + l1.b
                PointF(x, y)
            }
            else -> {
                // y = a1*x + b1, y = a2*x + b2
                // a1*x + b1 = a2*x + b2
                // x*(a1 - a2) = b2 - b1
                val aDiff = l1.a - l2.a
                if (abs(aDiff) < 1e-6f) {
                    // 平行线，取中点
                    val x = (0f) // fallback
                    val y = l1.a * x + l1.b
                    PointF(x, y)
                } else {
                    val x = (l2.b - l1.b) / aDiff
                    val y = l1.a * x + l1.b
                    PointF(x, y)
                }
            }
        }
    }

    /**
     * 计算四边形面积
     */
    private fun computeQuadArea(tl: PointF, tr: PointF, br: PointF, bl: PointF): Float {
        // 分割为两个三角形计算面积
        val area1 = abs(
            tl.x * (tr.y - br.y) + tr.x * (br.y - tl.y) + br.x * (tl.y - tr.y)
        ) / 2f
        val area2 = abs(
            tl.x * (bl.y - br.y) + bl.x * (br.y - tl.y) + br.x * (tl.y - bl.y)
        ) / 2f
        return area1 + area2
    }

    /**
     * 角点排序：按左上、右上、右下、左下排列
     */
    private fun sortCorners(
        tl: PointF, tr: PointF, br: PointF, bl: PointF
    ): List<PointF> {
        val pts = listOf(tl, tr, bl, br)

        // 按 y 坐标排序获取上下两组
        val sortedByY = pts.sortedBy { it.y }
        val topTwo = sortedByY.take(2).sortedBy { it.x }
        val bottomTwo = sortedByY.takeLast(2).sortedBy { it.x }

        return listOf(topTwo[0], topTwo[1], bottomTwo[1], bottomTwo[0])
    }

    /**
     * 回退方案：检测到背景与文档边界的大致位置
     * 使用图像边缘偏移策略
     */
    private fun useFallbackCorners(width: Int, height: Int): List<PointF> {
        val margin = minOf(width, height) / 20
        return listOf(
            PointF(margin.toFloat(), margin.toFloat()),
            PointF((width - margin).toFloat(), margin.toFloat()),
            PointF((width - margin).toFloat(), (height - margin).toFloat()),
            PointF(margin.toFloat(), (height - margin).toFloat())
        )
    }

    // ── 以下为原有方法保持兼容 ──────────────────────────────────────────────

    /**
     * 透视变换：将四边形区域变换为矩形
     */
    fun perspectiveTransform(bitmap: Bitmap, corners: List<PointF>): Bitmap {
        if (corners.size != 4) return bitmap

        val tl = corners[0]
        val tr = corners[1]
        val br = corners[2]
        val bl = corners[3]

        // 计算目标矩形的尺寸
        val widthTop = distance(tl, tr)
        val widthBottom = distance(bl, br)
        val heightLeft = distance(tl, bl)
        val heightRight = distance(tr, br)

        val maxWidth = maxOf(widthTop, widthBottom).toInt().coerceAtLeast(1)
        val maxHeight = maxOf(heightLeft, heightRight).toInt().coerceAtLeast(1)

        if (maxWidth <= 0 || maxHeight <= 0) return bitmap

        // 创建目标位图
        val result = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888)

        // 双线性插值透视变换
        for (y in 0 until maxHeight) {
            for (x in 0 until maxWidth) {
                val u = x.toFloat() / maxWidth
                val v = y.toFloat() / maxHeight

                // 双线性插值计算源坐标
                val srcX = bilinearInterpolate(tl.x, tr.x, bl.x, br.x, u, v)
                val srcY = bilinearInterpolate(tl.y, tr.y, bl.y, br.y, u, v)

                // 采样源图像
                val srcXInt = srcX.toInt().coerceIn(0, bitmap.width - 1)
                val srcYInt = srcY.toInt().coerceIn(0, bitmap.height - 1)

                result.setPixel(x, y, bitmap.getPixel(srcXInt, srcYInt))
            }
        }

        return result
    }

    /**
     * 双线性插值
     */
    private fun bilinearInterpolate(v00: Float, v10: Float, v01: Float, v11: Float, u: Float, v: Float): Float {
        val top = v00 + (v10 - v00) * u
        val bottom = v01 + (v11 - v01) * u
        return top + (bottom - top) * v
    }

    /**
     * 计算两点间距离
     */
    private fun distance(p1: PointF, p2: PointF): Float {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * 应用扫描效果
     * @param bitmap 输入图像
     * @param effect 效果类型: original, gray, colorful
     */
    fun applyScanEffect(bitmap: Bitmap, effect: String = "colorful"): Bitmap {
        return when (effect.lowercase()) {
            "gray" -> applyGrayscale(bitmap)
            "original" -> applyAutoEnhance(bitmap)
            else -> applyColorfulScan(bitmap)
        }
    }

    /**
     * 灰度扫描效果（经典扫描件风格）
     */
    private fun applyGrayscale(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // 转换为灰度
                var gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()

                // 增加对比度
                gray = ((gray - 128) * 1.5 + 128).toInt().coerceIn(0, 255)

                result.setPixel(x, y, Color.rgb(gray, gray, gray))
            }
        }

        return result
    }

    /**
     * 自动增强效果（直方图均衡化）
     */
    private fun applyAutoEnhance(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        val histogram = IntArray(256)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val gray = ((0.299 * r + 0.587 * g + 0.114 * b) * 0.7).toInt().coerceIn(0, 255)
                histogram[gray]++
            }
        }

        // 计算累积分布函数 (CDF)
        val cdf = IntArray(256)
        cdf[0] = histogram[0]
        for (i in 1..255) {
            cdf[i] = cdf[i - 1] + histogram[i]
        }

        // 归一化 CDF
        val cdfMin = cdf.firstOrNull { it > 0 } ?: 0
        val cdfMax = cdf.last()
        val totalPixels = width * height

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                val newR = if (cdfMax != cdfMin) {
                    ((cdf[r.coerceIn(0, 255)] - cdfMin) * 255.0 / (cdfMax - cdfMin)).toInt().coerceIn(0, 255)
                } else r
                val newG = if (cdfMax != cdfMin) {
                    ((cdf[g.coerceIn(0, 255)] - cdfMin) * 255.0 / (cdfMax - cdfMin)).toInt().coerceIn(0, 255)
                } else g
                val newB = if (cdfMax != cdfMin) {
                    ((cdf[b.coerceIn(0, 255)] - cdfMin) * 255.0 / (cdfMax - cdfMin)).toInt().coerceIn(0, 255)
                } else b

                result.setPixel(x, y, Color.rgb(newR, newG, newB))
            }
        }

        return result
    }

    /**
     * 彩色扫描效果（保留颜色，增强清晰度）
     */
    private fun applyColorfulScan(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                var r = Color.red(pixel)
                var g = Color.green(pixel)
                var b = Color.blue(pixel)

                // 轻微增强对比度
                val factor = 1.1f
                r = ((r - 128) * factor + 128).toInt().coerceIn(0, 255)
                g = ((g - 128) * factor + 128).toInt().coerceIn(0, 255)
                b = ((b - 128) * factor + 128).toInt().coerceIn(0, 255)

                result.setPixel(x, y, Color.rgb(r, g, b))
            }
        }

        return result
    }
}
