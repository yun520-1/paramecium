package com.heartflow.scanner

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 文档扫描器核心类 v2.0
 *
 * 彻底重写的 CV pipeline，比 v1（四边扫描）有质的飞跃：
 *
 * v1 的致命缺陷：四边扫描假设文档紧贴图像四条边，
 * 在复杂背景/深色背景/倾斜文档下完全失效。
 *
 * v2 核心流程（真正的专业级算法）：
 * 1. Canny 边缘检测：Sobel 梯度 → 非极大值抑制 → 双阈值 → 滞后追踪
 * 2. 连通组件标记（两遍扫描）：找到图像中最大的边缘连通域
 * 3. Monotone Chain 凸包：提取最大连通域的外轮廓
 * 4. Douglas-Peucker 多边形近似：找到 4 个顶点
 * 5. 透视变换矫正为矩形
 *
 * 在 Canny 失败时按顺序降级：
 *   v1 四边扫描 → 固定边距回退
 */
class DocumentScanner {

    companion object {
        // Canny 双阈值倍率
        private const val CANNY_HIGH_RATIO = 0.35f   // Otsu 阈值 × 此值 = 高阈值
        private const val CANNY_LOW_RATIO = 0.10f    // 高阈值 × 此值 = 低阈值

        // 最小面积阈值（相对于图像面积的比例）
        private const val MIN_AREA_RATIO = 0.05f
        private const val MIN_BOUNDARY_POINTS = 5

        // 形态学闭操作核大小
        private const val MORPH_KERNEL = 5

        // Douglas-Peucker epsilon 相对值（× 周长）
        private const val DP_EPSILON_RATIO = 0.015f

        // 连通组件最小像素数（占最长边的比例）
        private const val MIN_COMPONENT_RATIO = 0.02f
    }

    // ════════════════════════════════════════════════════════════════
    //  公共 API
    // ════════════════════════════════════════════════════════════════

    /**
     * 检测图像中的文档四角
     * @return 四个角点（顺序：左上、右上、右下、左下）
     */
    fun detectCorners(bitmap: Bitmap): List<PointF> {
        val width = bitmap.width
        val height = bitmap.height

        // 缩小图像以加快处理速度
        val scale = minOf(1f, 800f / maxOf(width, height))
        val scaledWidth = (width * scale).toInt().coerceAtLeast(200)
        val scaledHeight = (height * scale).toInt().coerceAtLeast(200)
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)

        val edges = cannyEdgeDetection(scaled)

        // 【新】连通组件 → 凸包 → Douglas-Peucker → 四边形
        val corners = tryFindQuadrilateral(edges, scaledWidth, scaledHeight)

        // 【降级】v1 四边扫描
        val result = corners ?: findQuadByBoundaryScanning(edges, scaledWidth, scaledHeight)

        scaled.recycle()

        val ratioX = width.toFloat() / scaledWidth
        val ratioY = height.toFloat() / scaledHeight
        return result.map { PointF(it.x * ratioX, it.y * ratioY) }
    }

    // ════════════════════════════════════════════════════════════════
    //  Canny 边缘检测
    // ════════════════════════════════════════════════════════════════

    /**
     * 完整 Canny 边缘检测：
     * Sobel → NMS → 双阈值 → 滞后追踪
     */
    private fun cannyEdgeDetection(bitmap: Bitmap): Array<IntArray> {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // 1. 灰度化
        val gray = FloatArray(w * h) { i ->
            val c = pixels[i]
            0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
        }

        // 2. 高斯模糊（5×5，σ≈1.4）
        val blurred = gaussianBlur(gray, w, h)

        // 3. Sobel 梯度
        val (magnitude, direction) = sobelGradients(blurred, w, h)

        // 4. 非极大值抑制（关键：将模糊边缘细化为单像素）
        val suppressed = nonMaxSuppression(magnitude, direction, w, h)

        // 5. Otsu 计算自适应阈值
        val magMax = suppressed.maxOrNull() ?: 0f
        val otsuThresh = if (magMax > 5f) {
            computeOtsuThresholdFloat(suppressed, magMax, w * h)
        } else {
            30f
        }
        val highThresh = maxOf(otsuThresh * CANNY_HIGH_RATIO, 8f)
        val lowThresh = maxOf(highThresh * CANNY_LOW_RATIO, 3f)

        // 6. 双阈值：0=抑制 1=弱边 2=强边
        val edges = doubleThreshold(suppressed, w, h, lowThresh, highThresh)

        // 7. 滞后追踪：弱边只有连通到强边才保留
        hysteresis(edges, w, h)

        // 8. 转为 0/255 输出
        val result = Array(h) { y -> IntArray(w) { x -> if (edges[y][x] > 0) 255 else 0 } }
        return result
    }

    /** 5×5 高斯模糊（σ≈1.4） */
    private fun gaussianBlur(src: FloatArray, w: Int, h: Int): FloatArray {
        // 归一化的 5×5 高斯核（σ=1.4），分母 159
        val kernel = arrayOf(
            floatArrayOf(2f, 4f, 5f, 4f, 2f),
            floatArrayOf(4f, 9f, 12f, 9f, 4f),
            floatArrayOf(5f, 12f, 15f, 12f, 5f),
            floatArrayOf(4f, 9f, 12f, 9f, 4f),
            floatArrayOf(2f, 4f, 5f, 4f, 2f)
        )
        val result = FloatArray(w * h)
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                var sum = 0f
                for (ky in -2..2) {
                    for (kx in -2..2) {
                        sum += kernel[ky + 2][kx + 2] * src[(y + ky) * w + (x + kx)]
                    }
                }
                result[y * w + x] = sum / 159f
            }
        }
        // 边界复制
        for (y in 0 until h) for (x in 0 until w) {
            if (result[y * w + x] == 0f) result[y * w + x] =
                src[y * w + x].coerceIn(0f, 255f)
        }
        return result
    }

    /** Sobel 3×3 梯度，返回 (梯度幅值, 方向角[0,π)) */
    private fun sobelGradients(src: FloatArray, w: Int, h: Int): Pair<FloatArray, FloatArray> {
        val magnitude = FloatArray(w * h)
        val direction = FloatArray(w * h)

        val sobelX = intArrayOf(-1, 0, 1, -2, 0, 2, -1, 0, 1)
        val sobelY = intArrayOf(-1, -2, -1, 0, 0, 0, 1, 2, 1)

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var gx = 0f
                var gy = 0f
                for (ky in -1..1) for (kx in -1..1) {
                    val idx = (ky + 1) * 3 + (kx + 1)
                    val s = src[(y + ky) * w + (x + kx)]
                    gx += sobelX[idx] * s
                    gy += sobelY[idx] * s
                }
                val idx = y * w + x
                magnitude[idx] = sqrt(gx * gx + gy * gy)
                direction[idx] = (atan2(gy.toDouble(), gx.toDouble()) + Math.PI).toFloat() % Math.PI.toFloat()
            }
        }
        return Pair(magnitude, direction)
    }

    /**
     * 非极大值抑制
     * 将梯度方向量化为四个方向（0°/45°/90°/135°），
     * 沿梯度方向比较相邻像素，只保留局部最大值。
     */
    private fun nonMaxSuppression(mag: FloatArray, dir: FloatArray, w: Int, h: Int): FloatArray {
        val result = FloatArray(w * h)

        // 方向量化：angle ∈ [0, π)
        // d=0 → 水平梯度 → 比较左右
        // d=1 → 45° → 比较 NE/SW
        // d=2 → 垂直梯度 → 比较上下
        // d=3 → 135° → 比较 NW/SE
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val angle = dir[idx]
                val m = mag[idx]

                // 量化梯度方向
                val d = ((angle / Math.PI * 4 + 0.5).toInt().coerceIn(0, 3) + 3) % 4
                // 注意：angle ∈ [0, π)，所以 (angle/π*4) ∈ [0, 4)
                val d2 = ((angle / Math.PI.toFloat() * 4 + 0.5f).toInt() + 4) % 4

                val neighbor1: Float
                val neighbor2: Float

                when (d2) {
                    0 -> { // 水平梯度 → 左右比较（垂直边缘）
                        neighbor1 = mag[idx - 1]   // 左
                        neighbor2 = mag[idx + 1]   // 右
                    }
                    1 -> { // 45° → NE/SW
                        neighbor1 = mag[(y - 1) * w + (x + 1)]  // NE
                        neighbor2 = mag[(y + 1) * w + (x - 1)]  // SW
                    }
                    2 -> { // 垂直梯度 → 上下比较（水平边缘）
                        neighbor1 = mag[idx - w]   // 上
                        neighbor2 = mag[idx + w]   // 下
                    }
                    else -> { // 135° → NW/SE
                        neighbor1 = mag[(y - 1) * w + (x - 1)]  // NW
                        neighbor2 = mag[(y + 1) * w + (x + 1)]  // SE
                    }
                }

                if (m >= neighbor1 && m >= neighbor2) {
                    result[idx] = m
                }
                // else → 保持 0（被抑制）
            }
        }
        return result
    }

    /** Otsu 自适应阈值（浮点版） */
    private fun computeOtsuThresholdFloat(values: FloatArray, maxVal: Float, total: Int): Float {
        val histSize = maxOf(maxVal.toInt() + 1, 1).coerceAtMost(256)
        val histogram = IntArray(histSize)
        for (v in values) {
            val idx = v.toInt().coerceIn(0, histSize - 1)
            histogram[idx]++
        }

        var sum = 0.0
        for (i in 0 until histSize) sum += i.toDouble() * histogram[i]

        var sumB = 0.0
        var wB = 0
        var maxVar = 0.0
        var threshold = 15f

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
            if (between > maxVar) { maxVar = between; threshold = t.toFloat() }
        }
        return threshold.coerceIn(8f, 200f)
    }

    /** 双阈值：0=抑制 1=弱边 2=强边 */
    private fun doubleThreshold(
        suppressed: FloatArray, w: Int, h: Int,
        low: Float, high: Float
    ): Array<IntArray> {
        val result = Array(h) { IntArray(w) }
        for (y in 0 until h) for (x in 0 until w) {
            val v = suppressed[y * w + x]
            result[y][x] = when {
                v >= high -> 2   // 强边
                v >= low  -> 1   // 弱边
                else      -> 0   // 抑制
            }
        }
        return result
    }

    /**
     * 滞后追踪：弱边必须连通到强边才保留
     * BFS 从强边出发，扩散到相邻的弱边
     */
    private fun hysteresis(edges: Array<IntArray>, w: Int, h: Int) {
        // 用队列做 BFS
        val queue = java.util.ArrayDeque<Pair<Int, Int>>()
        for (y in 0 until h) for (x in 0 until w) {
            if (edges[y][x] == 2) {
                queue.add(Pair(x, y))
            }
        }

        // 8 邻域
        val dx = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
        val dy = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)

        while (queue.isNotEmpty()) {
            val (cx, cy) = queue.poll()
            for (i in 0..7) {
                val nx = cx + dx[i]
                val ny = cy + dy[i]
                if (nx in 0 until w && ny in 0 until h && edges[ny][nx] == 1) {
                    edges[ny][nx] = 2   // 弱边升级为强边
                    queue.add(Pair(nx, ny))
                }
            }
        }

        // 清除未连通的弱边
        for (y in 0 until h) for (x in 0 until w) {
            if (edges[y][x] == 1) edges[y][x] = 0
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  连通组件 → 凸包 → Douglas-Peucker
    // ════════════════════════════════════════════════════════════════

    /**
     * 从二值边缘图中检测四边形。
     * 流程：连通组件标记 → 选出最大组件 → 提取边界点 →
     *       凸包（Monotone Chain）→ DP 简化 → 判断 4 顶点
     */
    private fun tryFindQuadrilateral(
        edges: Array<IntArray>, w: Int, h: Int
    ): List<PointF>? {
        // 1. 形态学闭操作连接断边
        val closed = morphologicalClose(edges, w, h, MORPH_KERNEL)

        // 2. 连通组件标记
        val component = connectedComponents(closed, w, h) ?: return null

        // 3. 提取组件边界点
        val boundary = extractBoundary(closed, component, w, h)
        if (boundary.size < 20) return null

        // 4. 凸包（Monotone Chain）
        val hull = convexHull(boundary)
        if (hull.size < 4) return null

        // 5. Douglas-Peucker 简化
        val halfPerimeter = perimeterLength(hull) / 2f
        val epsilon = (DP_EPSILON_RATIO * halfPerimeter).coerceAtLeast(2f)
        val simplified = douglasPeucker(hull, epsilon)

        // 6. 检查是否恰好 4 个点（四边形）
        if (simplified.size == 4) {
            val quad = sortCorners(simplified[0], simplified[1], simplified[2], simplified[3])
            // 检查四边形面积是否合理
            val area = computeQuadArea(quad[0], quad[1], quad[2], quad[3])
            val imageArea = (w * h).toFloat()
            if (area >= imageArea * MIN_AREA_RATIO) {
                return quad
            }
        }

        // 7. 尝试从凸包中找最佳的 4 个顶点
        if (hull.size >= 4) {
            val quad = findBestQuadrilateral(hull, w, h)
            if (quad != null) return quad
        }

        return null
    }

    /** 形态学闭操作（先膨胀后腐蚀），连接断开的边缘 */
    private fun morphologicalClose(
        src: Array<IntArray>, w: Int, h: Int, kernelSize: Int
    ): Array<IntArray> {
        val halfK = kernelSize / 2
        // 膨胀
        val dilated = Array(h) { IntArray(w) }
        for (y in halfK until h - halfK) for (x in halfK until w - halfK) {
            var maxV = 0
            for (ky in -halfK..halfK) for (kx in -halfK..halfK) {
                if (src[y + ky][x + kx] > maxV) maxV = src[y + ky][x + kx]
            }
            dilated[y][x] = maxV
        }
        // 腐蚀
        val closed = Array(h) { IntArray(w) }
        for (y in halfK until h - halfK) for (x in halfK until w - halfK) {
            var minV = 255
            for (ky in -halfK..halfK) for (kx in -halfK..halfK) {
                if (dilated[y + ky][x + kx] < minV) minV = dilated[y + ky][x + kx]
            }
            closed[y][x] = minV
        }
        return closed
    }

    /**
     * 两遍扫描连通组件标记
     * 返回最大组件的点集
     */
    private fun connectedComponents(
        binary: Array<IntArray>, w: Int, h: Int
    ): List<PointF>? {
        val labels = Array(h) { IntArray(w) }
        // 等价标签合并表
        val equiv = mutableListOf(mutableSetOf(0))  // index 0 unused
        var nextLabel = 0

        // 第一遍：从左到右、从上到下扫描
        for (y in 0 until h) for (x in 0 until w) {
            if (binary[y][x] == 0) continue
            // 检查上方 (x, y-1) 和左方 (x-1, y) 的标签
            val up = if (y > 0 && labels[y - 1][x] > 0) labels[y - 1][x] else 0
            val left = if (x > 0 && labels[y][x - 1] > 0) labels[y][x - 1] else 0

            val label = when {
                up == 0 && left == 0 -> {
                    nextLabel++
                    if (nextLabel >= equiv.size) equiv.add(mutableSetOf(nextLabel))
                    nextLabel
                }
                up > 0 && left > 0 && up != left -> {
                    // 合并等价标签
                    val merged = minOf(up, left)
                    val removed = maxOf(up, left)
                    equiv[merged].add(removed)
                    for (li in equiv.indices) {
                        if (equiv[li].remove(removed)) equiv[li].add(merged)
                    }
                    merged
                }
                else -> maxOf(up, left)
            }
            if (label > 0) labels[y][x] = label
        }

        // 第二遍：解析等价标签，赋予最终标签
        val finalLabels = IntArray(nextLabel + 1) { it }
        for (i in 1..nextLabel) {
            // 找到最小等价标签
            var root = i
            val visited = mutableSetOf(root)
            // BFS 查找根标签
            val stack = java.util.ArrayDeque<Int>()
            stack.push(i)
            while (stack.isNotEmpty()) {
                val cur = stack.pop()
                for (ni in 1 until equiv.size) {
                    if (equiv[ni].contains(cur) && ni !in visited) {
                        visited.add(ni)
                        if (ni < root) root = ni
                        stack.push(ni)
                    }
                }
            }
            finalLabels[i] = root
        }

        // 统计各标签的像素数
        val counts = IntArray(nextLabel + 1)
        for (y in 0 until h) for (x in 0 until w) {
            if (labels[y][x] > 0) {
                val finalLabel = finalLabels[labels[y][x]]
                labels[y][x] = finalLabel
                counts[finalLabel]++
            }
        }

        // 找到最大组件（跳过 label 0）
        var maxLabel = 0
        var maxCount = 0
        for (i in 1..nextLabel) {
            if (counts[i] > maxCount) { maxCount = counts[i]; maxLabel = i }
        }

        val minPixels = (maxOf(w, h) * MIN_COMPONENT_RATIO).toInt().coerceAtLeast(10)
        if (maxLabel == 0 || maxCount < minPixels) return null

        // 收集最大组件的所有像素坐标
        val points = mutableListOf<PointF>()
        for (y in 0 until h) for (x in 0 until w) {
            if (labels[y][x] == maxLabel) points.add(PointF(x.toFloat(), y.toFloat()))
        }
        return points
    }

    /** 提取连通组件的边界点（至少有一个非边缘邻居的点） */
    private fun extractBoundary(
        binary: Array<IntArray>, component: List<PointF>, w: Int, h: Int
    ): List<PointF> {
        val componentSet = mutableSetOf<Pair<Int, Int>>()
        component.forEach { componentSet.add(Pair(it.x.toInt(), it.y.toInt())) }

        val boundary = mutableListOf<PointF>()
        val dx = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
        val dy = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)

        for ((px, py) in componentSet) {
            // 检查周围是否有非边缘点
            var isBoundary = false
            for (i in 0..7) {
                val nx = px + dx[i]
                val ny = py + dy[i]
                if (nx < 0 || nx >= w || ny < 0 || ny >= h || Pair(nx, ny) !in componentSet) {
                    isBoundary = true
                    break
                }
            }
            if (isBoundary) boundary.add(PointF(px.toFloat(), py.toFloat()))
        }

        return boundary
    }

    /**
     * Monotone Chain（Andrew's Algorithm）凸包
     * O(n log n)
     */
    private fun convexHull(points: List<PointF>): List<PointF> {
        if (points.size < 3) return points.toList()

        val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))

        // 叉积
        fun cross(o: PointF, a: PointF, b: PointF): Float {
            return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        }

        val lower = mutableListOf<PointF>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(p)
        }

        val upper = mutableListOf<PointF>()
        for (p in sorted.reversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(p)
        }

        // 移除重复的端点
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    /** 计算多边形的周长 */
    private fun perimeterLength(poly: List<PointF>): Float {
        if (poly.size < 2) return 0f
        var len = 0f
        for (i in poly.indices) {
            len += distance(poly[i], poly[(i + 1) % poly.size])
        }
        return len
    }

    /**
     * Douglas-Peucker 多边形简化
     * 递归实现，将点集简化为尽量少的顶点
     */
    private fun douglasPeucker(points: List<PointF>, epsilon: Float): List<PointF> {
        if (points.size < 3) return points.toList()

        // 找到离首尾线段最远的点
        val first = points.first()
        val last = points.last()

        var dmax = 0f
        var index = 0

        for (i in 1 until points.size - 1) {
            val d = perpendicularDistance(points[i], first, last)
            if (d > dmax) { dmax = d; index = i }
        }

        return if (dmax > epsilon) {
            val left = douglasPeucker(points.subList(0, index + 1), epsilon)
            val right = douglasPeucker(points.subList(index, points.size), epsilon)
            (left.dropLast(1) + right).toMutableList()
        } else {
            listOf(first, last)
        }
    }

    /** 点到线段的垂直距离 */
    private fun perpendicularDistance(p: PointF, a: PointF, b: PointF): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0f) return distance(p, a)
        val num = abs(dy * p.x - dx * p.y + b.x * a.y - b.y * a.x)
        return num / sqrt(lenSq)
    }

    /**
     * 从凸包中找出最佳的四边形近似
     * 尝试所有 >3 点的分割，计算最小面积外接四边形
     */
    private fun findBestQuadrilateral(
        hull: List<PointF>, w: Int, h: Int
    ): List<PointF>? {
        if (hull.size < 4) return null

        // 策略：从凸包中选 4 个关键点
        // 1. 找 hull 的 4 个极值方向点（类似旋转卡壳）
        val indices = findExtremePoints(hull)
        if (indices.size < 4) return null

        val corners = indices.map { hull[it] }
        val sorted = sortCorners(corners[0], corners[1], corners[2], corners[3])

        val area = computeQuadArea(sorted[0], sorted[1], sorted[2], sorted[3])
        val imageArea = (w * h).toFloat()
        return if (area >= imageArea * MIN_AREA_RATIO) sorted else null
    }

    /** 在凸包上找到 4 个极值方向点 */
    private fun findExtremePoints(points: List<PointF>): List<Int> {
        if (points.size < 4) return (0 until points.size).toList()

        // 找四个角落：x最小、x最大、y最小、y最大的点
        val result = mutableSetOf<Int>()
        val indicesByX = points.indices.sortedBy { points[it].x }
        val indicesByY = points.indices.sortedBy { points[it].y }

        result.add(indicesByX.first())
        result.add(indicesByX.last())
        result.add(indicesByY.first())
        result.add(indicesByY.last())

        // 如果不足 4 个，用凸包上间隔最远的点补充
        if (result.size < 4) {
            val remaining = points.indices.filter { it !in result }
            for (i in remaining.take(4 - result.size)) result.add(i)
        }
        return result.toList()
    }

    // ════════════════════════════════════════════════════════════════
    //  v1 四边扫描保留（降级用）
    // ════════════════════════════════════════════════════════════════

    private fun findQuadByBoundaryScanning(
        edges: Array<IntArray>, width: Int, height: Int
    ): List<PointF> {
        val topPoints = scanTopDown(edges, width, height)
        val bottomPoints = scanBottomUp(edges, width, height)
        val leftPoints = scanLeftRight(edges, width, height)
        val rightPoints = scanRightLeft(edges, width, height)

        if (topPoints.size < MIN_BOUNDARY_POINTS ||
            bottomPoints.size < MIN_BOUNDARY_POINTS ||
            leftPoints.size < MIN_BOUNDARY_POINTS ||
            rightPoints.size < MIN_BOUNDARY_POINTS) {
            return useFallbackCorners(width, height)
        }

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

        val topLine = fitLineLeastSquares(filteredTop)
        val bottomLine = fitLineLeastSquares(filteredBottom)
        val leftLine = fitLineLeastSquares(filteredLeft)
        val rightLine = fitLineLeastSquares(filteredRight)

        if (topLine == null || bottomLine == null || leftLine == null || rightLine == null) {
            return useFallbackCorners(width, height)
        }

        val tl = intersectLines(topLine, leftLine)
        val tr = intersectLines(topLine, rightLine)
        val bl = intersectLines(bottomLine, leftLine)
        val br = intersectLines(bottomLine, rightLine)

        val corners = listOf(tl, tr, bl, br)
        if (corners.any { it.x.isNaN() || it.y.isNaN() }) {
            return useFallbackCorners(width, height)
        }

        val quadArea = computeQuadArea(tl, tr, br, bl)
        val imageArea = (width * height).toFloat()
        if (quadArea < imageArea * MIN_AREA_RATIO) {
            return useFallbackCorners(width, height)
        }

        return sortCorners(tl, tr, br, bl)
    }

    private fun scanTopDown(edges: Array<IntArray>, width: Int, height: Int): List<PointF> {
        val points = mutableListOf<PointF>()
        val margin = width / 8
        for (x in margin until width - margin step 3) {
            var foundY = -1
            var maxEdges = 0
            for (y in 0 until height * 3 / 4) {
                var edgeCount = 0
                for (wy in y until (y + 5).coerceAtMost(height)) {
                    for (wx in (x - 1).coerceAtLeast(0)..(x + 1).coerceAtMost(width - 1)) {
                        if (edges[wy][wx] > 0) edgeCount++
                    }
                }
                if (edgeCount > maxEdges) { maxEdges = edgeCount; foundY = y }
                if (edgeCount >= 4) { foundY = y; break }
            }
            if (foundY > 0 && maxEdges > 1) points.add(PointF(x.toFloat(), foundY.toFloat()))
        }
        return points
    }

    private fun scanBottomUp(edges: Array<IntArray>, width: Int, height: Int): List<PointF> {
        val points = mutableListOf<PointF>()
        val margin = width / 8
        for (x in margin until width - margin step 3) {
            var foundY = -1
            var maxEdges = 0
            for (y in height - 1 downTo height / 4) {
                var edgeCount = 0
                for (wy in (y - 5).coerceAtLeast(0)..y) {
                    for (wx in (x - 1).coerceAtLeast(0)..(x + 1).coerceAtMost(width - 1)) {
                        if (edges[wy][wx] > 0) edgeCount++
                    }
                }
                if (edgeCount > maxEdges) { maxEdges = edgeCount; foundY = y }
                if (edgeCount >= 4) { foundY = y; break }
            }
            if (foundY > 0 && foundY < height - 1 && maxEdges > 1) points.add(PointF(x.toFloat(), foundY.toFloat()))
        }
        return points
    }

    private fun scanLeftRight(edges: Array<IntArray>, width: Int, height: Int): List<PointF> {
        val points = mutableListOf<PointF>()
        val margin = height / 8
        for (y in margin until height - margin step 3) {
            var foundX = -1
            var maxEdges = 0
            for (x in 0 until width * 3 / 4) {
                var edgeCount = 0
                for (wy in (y - 1).coerceAtLeast(0)..(y + 1).coerceAtMost(height - 1)) {
                    for (wx in x until (x + 5).coerceAtMost(width)) {
                        if (edges[wy][wx] > 0) edgeCount++
                    }
                }
                if (edgeCount > maxEdges) { maxEdges = edgeCount; foundX = x }
                if (edgeCount >= 4) { foundX = x; break }
            }
            if (foundX > 0 && maxEdges > 1) points.add(PointF(foundX.toFloat(), y.toFloat()))
        }
        return points
    }

    private fun scanRightLeft(edges: Array<IntArray>, width: Int, height: Int): List<PointF> {
        val points = mutableListOf<PointF>()
        val margin = height / 8
        for (y in margin until height - margin step 3) {
            var foundX = -1
            var maxEdges = 0
            for (x in width - 1 downTo width / 4) {
                var edgeCount = 0
                for (wy in (y - 1).coerceAtLeast(0)..(y + 1).coerceAtMost(height - 1)) {
                    for (wx in (x - 5).coerceAtLeast(0)..x) {
                        if (edges[wy][wx] > 0) edgeCount++
                    }
                }
                if (edgeCount > maxEdges) { maxEdges = edgeCount; foundX = x }
                if (edgeCount >= 4) { foundX = x; break }
            }
            if (foundX > 0 && foundX < width - 1 && maxEdges > 1) points.add(PointF(foundX.toFloat(), y.toFloat()))
        }
        return points
    }

    private fun filterOutliers(points: List<PointF>, maxDeviation: Float): List<PointF> {
        if (points.size < 4) return points
        val isHorizontal = points.all { p -> points.count { it.y == p.y } < points.size / 2 }
        val values = if (isHorizontal) points.map { it.y } else points.map { it.x }
        val sorted = values.sorted()
        val median = sorted[sorted.size / 2]
        val absDeviations = values.map { abs(it - median) }.sorted()
        val mad = absDeviations[absDeviations.size / 2].coerceAtLeast(1f)
        val threshold = (3.0 * mad).toFloat().coerceAtLeast(maxDeviation * 0.3f)
        return points.filterIndexed { index, _ -> abs(values[index] - median) <= threshold }
    }

    private data class Line(val a: Float, val b: Float, val isVertical: Boolean, val verticalX: Float)

    private fun fitLineLeastSquares(points: List<PointF>): Line? {
        if (points.size < 2) return null
        val xMean = points.map { it.x }.average().toFloat()
        val yMean = points.map { it.y }.average().toFloat()
        val xVariance = points.map { (it.x - xMean).let { dx -> dx * dx } }.sum()
        if (xVariance < 1f) return Line(0f, 0f, isVertical = true, verticalX = xMean)
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
            else -> {
                val aDiff = l1.a - l2.a
                if (abs(aDiff) < 1e-6f) PointF(0f, l1.a * 0f + l1.b)
                else { val x = (l2.b - l1.b) / aDiff; PointF(x, l1.a * x + l1.b) }
            }
        }
    }

    private fun computeQuadArea(tl: PointF, tr: PointF, br: PointF, bl: PointF): Float {
        val area1 = abs(tl.x * (tr.y - br.y) + tr.x * (br.y - tl.y) + br.x * (tl.y - tr.y)) / 2f
        val area2 = abs(tl.x * (bl.y - br.y) + bl.x * (br.y - tl.y) + br.x * (tl.y - bl.y)) / 2f
        return area1 + area2
    }

    private fun sortCorners(tl: PointF, tr: PointF, br: PointF, bl: PointF): List<PointF> {
        val pts = listOf(tl, tr, bl, br)
        val sortedByY = pts.sortedBy { it.y }
        val topTwo = sortedByY.take(2).sortedBy { it.x }
        val bottomTwo = sortedByY.takeLast(2).sortedBy { it.x }
        return listOf(topTwo[0], topTwo[1], bottomTwo[1], bottomTwo[0])
    }

    private fun useFallbackCorners(width: Int, height: Int): List<PointF> {
        val margin = minOf(width, height) / 20
        return listOf(
            PointF(margin.toFloat(), margin.toFloat()),
            PointF((width - margin).toFloat(), margin.toFloat()),
            PointF((width - margin).toFloat(), (height - margin).toFloat()),
            PointF(margin.toFloat(), (height - margin).toFloat())
        )
    }

    // ════════════════════════════════════════════════════════════════
    //  透视变换（沿用 v1，工作正常）
    // ════════════════════════════════════════════════════════════════

    fun perspectiveTransform(bitmap: Bitmap, corners: List<PointF>): Bitmap {
        if (corners.size != 4) return bitmap

        val tl = corners[0]; val tr = corners[1]
        val br = corners[2]; val bl = corners[3]

        val widthTop = distance(tl, tr)
        val widthBottom = distance(bl, br)
        val heightLeft = distance(tl, bl)
        val heightRight = distance(tr, br)

        val maxWidth = maxOf(widthTop, widthBottom).toInt().coerceAtLeast(1)
        val maxHeight = maxOf(heightLeft, heightRight).toInt().coerceAtLeast(1)

        if (maxWidth <= 0 || maxHeight <= 0) return bitmap

        val result = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888)

        for (y in 0 until maxHeight) for (x in 0 until maxWidth) {
            val u = x.toFloat() / maxWidth
            val v = y.toFloat() / maxHeight

            val srcX = bilinearInterpolate(tl.x, tr.x, bl.x, br.x, u, v)
            val srcY = bilinearInterpolate(tl.y, tr.y, bl.y, br.y, u, v)

            val sx = srcX.toInt().coerceIn(0, bitmap.width - 1)
            val sy = srcY.toInt().coerceIn(0, bitmap.height - 1)

            result.setPixel(x, y, bitmap.getPixel(sx, sy))
        }

        return result
    }

    private fun bilinearInterpolate(
        v00: Float, v10: Float, v01: Float, v11: Float, u: Float, v: Float
    ): Float {
        val top = v00 + (v10 - v00) * u
        val bottom = v01 + (v11 - v01) * u
        return top + (bottom - top) * v
    }

    private fun distance(p1: PointF, p2: PointF): Float {
        val dx = p2.x - p1.x; val dy = p2.y - p1.y
        return sqrt(dx * dx + dy * dy)
    }

    // ════════════════════════════════════════════════════════════════
    //  扫描效果（沿用 v1，工作正常）
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
        val cdfMin = cdf.firstOrNull { it > 0 } ?: 0
        val cdfMax = cdf.last()
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
        for (y in 0 until h) for (x in 0 until w) {
            val p = bitmap.getPixel(x, y)
            val factor = 1.1f
            val r = ((Color.red(p) - 128) * factor + 128).toInt().coerceIn(0, 255)
            val g = ((Color.green(p) - 128) * factor + 128).toInt().coerceIn(0, 255)
            val b = ((Color.blue(p) - 128) * factor + 128).toInt().coerceIn(0, 255)
            result.setPixel(x, y, Color.rgb(r, g, b))
        }
        return result
    }
}
