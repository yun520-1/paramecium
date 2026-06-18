package com.heartflow.scanner

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 多策略四边形检测引擎 v1.0
 *
 * 比单次 Canny + contour 检测鲁棒得多的文档四边形检测。
 * 核心策略：
 *
 * 1. 多色彩平面遍历（灰度 + R/G/B 通道）
 * 2. 多 Canny 阈值级别（0.5x ~ 2.0x Otsu 自适应阈值）
 * 3. 轮廓方向角分段检测（替代 Douglas-Peucker 多边形近似）
 * 4. 四边形综合评分（面积 + 凸性 + 边缘对齐度）
 *
 * 设计思路受 mayuce/AndroidDocumentScanner (MIT) 的
 * 多阈值检测方法启发，以及 pynicolas/FairScan (GPL-3.0) 的
 * 轮廓方向角分段算法的思路（独立实现，不复制代码）。
 *
 * @param config 检测配置参数
 */
class QuadDetector(private val config: Config = Config()) {

    /**
     * 检测配置
     */
    data class Config(
        /** 最小文档面积占比（相对于图像总面积） */
        val minAreaRatio: Float = 0.12f,
        /** 最小文档外接边占比（相对于图像宽/高） */
        val minSideRatio: Float = 0.20f,
        /** Douglas-Peucker epsilon 相对值（相对于轮廓周长） */
        val dpEpsilonRatio: Float = 0.015f,
        /** 形态学核大小 */
        val morphKernel: Int = 7,
        /** 是否启用多色彩平面检测 */
        val enableMultiColorPlane: Boolean = true,
        /** 是否启用多阈值检测 */
        val enableMultiThreshold: Boolean = true,
        /** 是否启用轮廓方向角分段检测 */
        val enableContourOrientation: Boolean = true,
        /** 是否需要凸性校验 */
        val requireConvexity: Boolean = true
    )

    /**
     * 检测结果
     *
     * @property quad 四角坐标（按 左上、右上、右下、左下 排序）
     * @property score 综合评分（越高越好）
     * @property method 检测策略名称
     * @property plane 色彩平面名称
     */
    data class DetectionResult(
        val quad: List<PointF>,
        val score: Float,
        val method: String = "unknown",
        val plane: String = "gray"
    )

    /**
     * 返回最佳四边形检测结果
     *
     * @param bitmap 输入位图（RGB 格式）
     * @param w 图像宽度（缩放后的）
     * @param h 图像高度（缩放后的）
     * @return 排序后的四角列表，检测失败返回 null
     */
    fun detect(bitmap: Bitmap, w: Int, h: Int): List<PointF>? {
        return detectScored(bitmap, w, h)?.quad
    }

    /**
     * 返回最佳检测结果（含评分和方法信息）
     */
    fun detectScored(bitmap: Bitmap, w: Int, h: Int): DetectionResult? {
        if (w <= 0 || h <= 0) return null
        val imageArea = (w * h).toFloat()

        // 收集所有候选四边形的检测结果
        val candidates = mutableListOf<DetectionResult>()

        // ── 策略 1：多色彩平面检测 ──
        if (config.enableMultiColorPlane) {
            val planes = extractColorPlanes(bitmap, w, h)
            for ((planeName, planeMat) in planes) {
                val planeCandidates = detectOnPlane(planeMat, w, h, imageArea, planeName)
                candidates.addAll(planeCandidates)
                planeMat.release()
            }
        }

        // ── 策略 2：标准灰度多阈值检测 ──
        if (config.enableMultiThreshold) {
            val grayCandidates = detectWithMultiThreshold(bitmap, w, h, imageArea)
            candidates.addAll(grayCandidates)
        }

        // ── 策略 3：轮廓方向角分段检测 ──
        if (config.enableContourOrientation) {
            val orientationCandidates = detectWithContourOrientation(bitmap, w, h, imageArea)
            candidates.addAll(orientationCandidates)
        }

        // 如果没有候选，返回 null
        if (candidates.isEmpty()) {
            Log.w(TAG, "所有策略均未检测到有效四边形")
            return null
        }

        // 按评分排序，返回最佳
        val best = candidates.maxByOrNull { it.score } ?: return null
        Log.i(TAG, "最佳检测: 方法=${best.method} 平面=${best.plane} 评分=${"%.2f".format(best.score)}")
        return best
    }

    // ════════════════════════════════════════════════════════════════
    //  策略实现
    // ════════════════════════════════════════════════════════════════

    /**
     * 提取多色彩平面 Mat
     *
     * 灰度 + R/G/B 三个通道各有优势：
     * - 灰度：稳定的默认选择
     * - R 通道：对红色系文档/背景敏感
     * - G 通道：对绿色调图像最清晰（通常是 Bayer 阵列采样最密）
     * - B 通道：对蓝色文字/标记有效
     */
    private fun extractColorPlanes(bitmap: Bitmap, w: Int, h: Int): List<Pair<String, Mat>> {
        val planes = mutableListOf<Pair<String, Mat>>()
        val bmp32 = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val src = Mat()
        Utils.bitmapToMat(bmp32, src)

        // 灰度图
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
        planes.add("gray" to gray)

        // 分通道（BGR + Alpha，取前三个通道）
        val channels = mutableListOf<Mat>()
        Core.split(src, channels)
        val channelNames = listOf("blue", "green", "red")
        for (i in 0 until minOf(3, channels.size)) {
            // 通道 Mat 做一次 clone，因为 src 会被 release
            planes.add(channelNames[i] to channels[i].clone())
        }
        channels.forEach { it.release() }

        src.release()
        bmp32.recycle()
        return planes
    }

    /**
     * 在单个色彩平面上执行多阈值 Canny 检测
     */
    private fun detectOnPlane(
        planeMat: Mat, w: Int, h: Int, imageArea: Float, planeName: String
    ): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()

        // 保边去噪（对所有平面都做）
        val denoised = Mat()
        Imgproc.bilateralFilter(planeMat, denoised, 9, 75.0, 75.0)

        // 计算 Otsu 自适应阈值
        val baseHigh = computeOtsuThreshold(denoised, w, h)
        val baseLow = baseHigh * 0.4

        // 多阈值级别遍历（5 个级别覆盖从低到高）
        val thresholdScales = listOf(0.5, 0.75, 1.0, 1.3, 1.7)
        // 形态学核多尺寸（小核连接细纹，大核连接粗轮廓）
        val morphKernels = listOf(
            maxOf(3, config.morphKernel - 2) or 1,
            config.morphKernel,
            config.morphKernel + 2 or 1
        ).distinct()

        for (scale in thresholdScales) {
            val high = maxOf(baseHigh * scale, 15.0)
            val low = maxOf(high * 0.3, 5.0)

            val edges = Mat()
            Imgproc.Canny(denoised, edges, low, high)

            // 对每个核尺寸尝试形态学闭操作
            for (ks in morphKernels) {
                val morphed = edges.clone()
                if (ks > 1) {
                    val kernel = Imgproc.getStructuringElement(
                        Imgproc.MORPH_ELLIPSE, org.opencv.core.Size(ks.toDouble(), ks.toDouble())
                    )
                    Imgproc.morphologyEx(morphed, morphed, Imgproc.MORPH_CLOSE, kernel)
                    kernel.release()
                }

                // 检测四边形
                val quads = findQuadsFromEdges(morphed, w, h, imageArea, planeName)
                for ((quad, score) in quads) {
                    results.add(
                        DetectionResult(
                            quad = quad,
                            score = score,
                            method = "canny_multiscale",
                            plane = planeName
                        )
                    )
                }
                morphed.release()
            }
            edges.release()
        }

        denoised.release()
        return results
    }

    /**
     * 标准灰度多阈值检测（主入口的备用策略）
     */
    private fun detectWithMultiThreshold(
        bitmap: Bitmap, w: Int, h: Int, imageArea: Float
    ): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()

        // 直接用原始灰度（不做 bilateral，保持锐利边缘）
        val bmp32 = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val src = Mat()
        Utils.bitmapToMat(bmp32, src)
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

        val baseHigh = computeOtsuThreshold(gray, w, h)

        // 补充一些非平面的检测级别
        val extraScales = listOf(0.6, 1.0, 1.5, 2.0)
        val extraKernels = listOf(5, 9).distinct()

        for (scale in extraScales) {
            val high = maxOf(baseHigh * scale, 20.0)
            val low = maxOf(high * 0.25, 8.0)

            val edges = Mat()
            Imgproc.Canny(gray, edges, low, high)

            for (ks in extraKernels) {
                val morphed = edges.clone()
                val kernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_ELLIPSE, org.opencv.core.Size(ks.toDouble(), ks.toDouble())
                )
                Imgproc.morphologyEx(morphed, morphed, Imgproc.MORPH_CLOSE, kernel)
                kernel.release()

                val quads = findQuadsFromEdges(morphed, w, h, imageArea, "gray")
                for ((quad, score) in quads) {
                    results.add(
                        DetectionResult(
                            quad = quad,
                            score = score * 0.95f,  // 轻微降低此处结果评分，优先多平面结果
                            method = "canny_extra",
                            plane = "gray"
                        )
                    )
                }
                morphed.release()
            }
            edges.release()
        }

        src.release(); gray.release(); bmp32.recycle()
        return results
    }

    /**
     * 轮廓方向角分段检测
     *
     * 受 FairScan 的 findQuadFromContourOrientation 算法思路启发。
     * 与传统 Douglas-Peucker 多边形近似的区别：
     * - Douglas-Peucker：从粗到细迭代简化，适合常规轮廓
     * - 方向角分段：通过轮廓上每点的平滑切线方向来分段，适合文档边缘被遮挡的情况
     *
     * 核心思路：
     * 1. 计算轮廓上每个点的方向角（滑动窗口平滑）
     * 2. 按角度稳定性分段
     * 3. 聚类成 4 个主导方向
     * 4. 拟合直线求交点
     */
    private fun detectWithContourOrientation(
        bitmap: Bitmap, w: Int, h: Int, imageArea: Float
    ): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()

        val bmp32 = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val src = Mat()
        Utils.bitmapToMat(bmp32, src)
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

        // 多种预处理方式尝试
        val preprocesses = listOf<(Mat) -> Mat>(
            // 方法 A：bilateral 保边 + Canny 中阈值
            { m ->
                val b = Mat()
                Imgproc.bilateralFilter(m, b, 9, 75.0, 75.0)
                val e = Mat()
                Imgproc.Canny(b, e, 40.0, 120.0)
                b.release()
                e
            },
            // 方法 B：GaussianBlur + Canny 低阈值（更多边缘）
            { m ->
                val b = Mat()
                Imgproc.GaussianBlur(m, b, org.opencv.core.Size(5.0, 5.0), 2.0)
                val e = Mat()
                Imgproc.Canny(b, e, 20.0, 80.0)
                b.release()
                e
            },
            // 方法 C：亮度自适应阈值（暗图用低阈值，亮图用高阈值）
            { m ->
                val mean = Core.mean(m).`val`[0]
                val high = maxOf(30.0, mean * 0.6)
                val low = maxOf(10.0, high * 0.3)
                val e = Mat()
                Imgproc.Canny(m, e, low, high)
                e
            }
        )

        for ((preIdx, preprocess) in preprocesses.withIndex()) {
            val edges = preprocess(gray)
            val morphed = edges.clone()
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE, org.opencv.core.Size(7.0, 7.0)
            )
            Imgproc.morphologyEx(morphed, morphed, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()

            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(morphed, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            contours.sortByDescending { Imgproc.contourArea(it) }

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < imageArea * config.minAreaRatio * 0.5f) continue

                // 用方向角方法从轮廓中提取四边形
                val quad = findQuadFromContourOrientation(contour, w, h, imageArea)
                if (quad != null) {
                    val score = scoreQuad(quad, w, h, imageArea, contour)

                    // 调用者已经在 findQuadFromContourOrientation 中做了排序
                    val sorted = sortCorners(quad[0], quad[1], quad[2], quad[3])
                    val valid = validateQuad(sorted, w, h, imageArea)

                    if (valid && score > 0.2f) {
                        results.add(
                            DetectionResult(
                                quad = sorted,
                                score = score * 1.05f,  // 略微提高评分——这种方法对复杂场景更好
                                method = "contour_orientation_$preIdx",
                                plane = "gray"
                            )
                        )
                    }
                }
            }

            contours.forEach { it.release() }
            hierarchy.release()
            morphed.release()
            edges.release()
        }

        src.release(); gray.release(); bmp32.recycle()
        return results
    }

    // ════════════════════════════════════════════════════════════════
    //  轮廓方向角四边形提取
    // ════════════════════════════════════════════════════════════════

    /**
     * 从轮廓中通过方向角分段提取四边形
     *
     * 算法步骤：
     * 1. 对轮廓点进行降采样（保持约 200 个均匀分布点）
     * 2. 计算每点的切线方向角（滑动窗口平滑）
     * 3. 从方向角变化最大的点开始分段
     * 4. 合并角度相近的相邻段
     * 5. 选 4 个主段 → 最小二乘直线拟合 → 求交点
     *
     * @param contour OpenCV 轮廓
     * @param w 图像宽度
     * @param h 图像高度
     * @param imageArea 图像面积
     * @return 排序后的四角列表，失败返回 null
     */
    private fun findQuadFromContourOrientation(
        contour: MatOfPoint, w: Int, h: Int, imageArea: Float
    ): List<PointF>? {
        val pts = contour.toArray()
        if (pts.size < 20) return null

        // 降采样到约 200 个均匀分布的点
        val step = maxOf(1, pts.size / 200)
        val sampled = pts.filterIndexed { idx, _ -> idx % step == 0 }
        if (sampled.size < 12) return null
        val n = sampled.size

        // 计算方向角：用滑动窗口平滑切线角度
        val windowSize = minOf(7, maxOf(3, n / 20))
        val angles = DoubleArray(n)

        for (i in 0 until n) {
            val prev = sampled[(i - windowSize + n) % n]
            val next = sampled[(i + windowSize) % n]
            val dx = next.x - prev.x
            val dy = next.y - prev.y
            angles[i] = atan2(dy.toDouble(), dx.toDouble())
        }

        // 平滑角度：小窗口滑动平均
        val smoothWindow = 3
        val smoothAngles = DoubleArray(n)
        for (i in 0 until n) {
            var sum = 0.0
            for (j in -smoothWindow..smoothWindow) {
                sum += angles[(i + j + n) % n]
            }
            smoothAngles[i] = sum / (2 * smoothWindow + 1)
        }

        // 计算角度变化率（找到变化最大的点作为起始）
        val angleDiffs = DoubleArray(n)
        for (i in 0 until n) {
            val prevAngle = smoothAngles[(i - 1 + n) % n]
            val currAngle = smoothAngles[i]
            var diff = currAngle - prevAngle
            // 角度归一化到 [-π, π]
            if (diff > Math.PI) diff -= 2 * Math.PI
            if (diff < -Math.PI) diff += 2 * Math.PI
            angleDiffs[i] = abs(diff)
        }

        // 找角度变化最大的前几个点作为分段候选起点
        val diffIndices = (0 until n).sortedByDescending { angleDiffs[it] }
        // 起点不应该过于接近
        val startIndices = mutableListOf<Int>()
        for (idx in diffIndices) {
            if (startIndices.isEmpty() ||
                startIndices.all { minOf(abs(it - idx), n - abs(it - idx)) > n / 6 }) {
                startIndices.add(idx)
                if (startIndices.size >= 4) break
            }
        }
        if (startIndices.size < 4) return null

        // 从每个起点开始，按照角度稳定度聚合成段
        val segments = mutableListOf<Segment>()
        val maxAngleVar = Math.toRadians(8.0)  // 8° 内视为同方向

        for (startIdx in startIndices.sorted()) {
            var endIdx = startIdx
            val baseAngle = smoothAngles[startIdx]
            for (i in 1 until n) {
                val nextIdx = (startIdx + i) % n
                var angleDiff = smoothAngles[nextIdx] - baseAngle
                if (angleDiff > Math.PI) angleDiff -= 2 * Math.PI
                if (angleDiff < -Math.PI) angleDiff += 2 * Math.PI
                if (abs(angleDiff) > maxAngleVar || abs(angleDiff) > 0.5) break
                endIdx = nextIdx
            }
            val segPoints = if (endIdx >= startIdx) {
                sampled.subList(startIdx, endIdx + 1)
            } else {
                sampled.subList(startIdx, sampled.size) + sampled.subList(0, endIdx + 1)
            }
            if (segPoints.size >= 3) {
                segments.add(Segment(points = segPoints, angle = baseAngle))
            }
        }

        // 合并角度相近的相邻段
        if (segments.size < 4) return null
        segments.sortBy { it.angle }
        val mergedSegments = mutableListOf(segments[0])
        for (i in 1 until segments.size) {
            val last = mergedSegments.last()
            if (abs(segments[i].angle - last.angle) < Math.toRadians(7.0)) {
                // 合并
                val merged = Segment(
                    points = last.points + segments[i].points,
                    angle = (last.angle + segments[i].angle) / 2.0
                )
                mergedSegments[mergedSegments.size - 1] = merged
            } else {
                mergedSegments.add(segments[i])
            }
        }

        // 按段内点数量排序，取最长的 4 条段
        if (mergedSegments.size < 4) return null
        val sortedSegments = mergedSegments.sortedByDescending { it.points.size }
        val top4 = sortedSegments.take(4)

        // 检查角度分布：4 段应分布在约 180° 范围内（四边形两两平行）
        val sortedAngles = top4.map { it.angle }.sorted()
        val angleSpan = sortedAngles.last() - sortedAngles.first()
        // 如果角度分布太集中（< 45°），说明检测到的不是四边形
        if (abs(angleSpan) < Math.toRadians(45.0)) return null

        // 对每段拟合直线（最小二乘法）
        val lines = mutableListOf<FittedLine>()
        for (seg in top4) {
            val fitted = fitLineLeastSquares(seg.points.map { PointF(it.x.toFloat(), it.y.toFloat()) })
            if (fitted != null) lines.add(fitted)
        }
        if (lines.size < 4) return null

        // 将 4 条线分为两组：水平（y 主导）和垂直（x 主导）
        // 通过直线角度判断
        val hLines = mutableListOf<FittedLine>()
        val vLines = mutableListOf<FittedLine>()
        for (line in lines) {
            // 用角度判断方向（约 -45° ~ 45° 为水平，其他为垂直）
            // 这里用 x 变化 vs y 变化的比例
            if (abs(line.directionX) > abs(line.directionY)) {
                hLines.add(line)
            } else {
                vLines.add(line)
            }
        }

        // 如果分组不均衡，用角度大小排序后取两组
        if (hLines.size != 2 || vLines.size != 2) {
            // 回退：按角度排序，取首尾配对
            val angleSorted = lines.sortedBy { it.angle }
            // 取第 1、3 条为水平，第 2、4 条为垂直
            hLines.clear(); vLines.clear()
            hLines.add(angleSorted[0])
            hLines.add(angleSorted[2])
            vLines.add(angleSorted[1])
            vLines.add(angleSorted[3])
        }

        // 排序 hLines：按截距排序（上 > 下）
        hLines.sortBy { it.intercept }
        val topLine = hLines[1]  // 上边
        val bottomLine = hLines[0]  // 下边

        // 排序 vLines：同样按截距
        vLines.sortBy { it.intercept }
        val leftLine = vLines[0]  // 左边
        val rightLine = vLines[1]  // 右边

        // 计算四个交点
        val tl = intersectTwoLines(topLine, leftLine)
        val tr = intersectTwoLines(topLine, rightLine)
        val bl = intersectTwoLines(bottomLine, leftLine)
        val br = intersectTwoLines(bottomLine, rightLine)

        if (tl == null || tr == null || bl == null || br == null) return null
        if (tl.x.isNaN() || tl.y.isNaN() || tr.x.isNaN() || tr.y.isNaN() ||
            bl.x.isNaN() || bl.y.isNaN() || br.x.isNaN() || br.y.isNaN()) return null

        // 检查交点是否在合理范围内
        val quad = listOf(tl, tr, br, bl)
        val sorted = sortCorners(tl, tr, br, bl)
        val qArea = computeQuadArea(sorted[0], sorted[1], sorted[2], sorted[3])
        if (qArea < imageArea * config.minAreaRatio) return null

        return sorted
    }

    // ════════════════════════════════════════════════════════════════
    //  四边形评分与验证
    // ════════════════════════════════════════════════════════════════

    /**
     * 从边缘图中提取四边形候选
     *
     * @return 列表，每个元素为 (四角, 评分)
     */
    private fun findQuadsFromEdges(
        edges: Mat, w: Int, h: Int, imageArea: Float, planeName: String
    ): List<Pair<List<PointF>, Float>> {
        val results = mutableListOf<Pair<List<PointF>, Float>>()
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()

        try {
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            contours.sortByDescending { Imgproc.contourArea(it) }

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < imageArea * config.minAreaRatio) continue

                // 多边形近似
                val perimeter = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
                val epsilon = (perimeter * config.dpEpsilonRatio).coerceAtLeast(3.0)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(MatOfPoint2f(*contour.toArray()), approx, epsilon, true)

                val cornersArray = approx.toArray()
                if (cornersArray.size == 4) {
                    val pts = cornersArray.map { PointF(it.x.toFloat(), it.y.toFloat()) }
                    val sorted = sortCorners(pts[0], pts[1], pts[2], pts[3])

                    if (validateQuad(sorted, w, h, imageArea)) {
                        // 凸性校验
                        if (config.requireConvexity) {
                            val convHull = MatOfInt()
                            Imgproc.convexHull(contour, convHull)
                            val hullPoints = convHull.toArray().map { contour.toArray()[it] }
                            val hullMat = MatOfPoint(*hullPoints.toTypedArray())
                            val hullArea = Imgproc.contourArea(hullMat)
                            hullMat.release(); convHull.release()
                            if (hullArea > area * 1.15f) {
                                approx.release()
                                continue  // 凸包显著大于轮廓，可能是凹四边形
                            }
                        }

                        val score = scoreQuad(sorted, w, h, imageArea, contour)
                        results.add(sorted to score)
                    }
                }

                // 凸包降级：如果多边形近似 > 4 边，尝试用凸包 + 最小矩形近似
                if (cornersArray.size > 4) {
                    val hullQuad = tryConvexHullQuad(contour, imageArea)
                    if (hullQuad != null && validateQuad(hullQuad, w, h, imageArea)) {
                        val score = scoreQuad(hullQuad, w, h, imageArea, contour) * 0.9f
                        results.add(hullQuad to score)
                    }
                }

                approx.release()
            }
        } finally {
            hierarchy.release()
        }

        return results
    }

    /**
     * 凸包退化为四边形（通过最小外接矩形）
     */
    private fun tryConvexHullQuad(contour: MatOfPoint, imageArea: Float): List<PointF>? {
        return try {
            val hull = MatOfInt()
            Imgproc.convexHull(contour, hull)
            val hullPoints = hull.toArray().map { contour.toArray()[it] }
            val hullMat = MatOfPoint(*hullPoints.toTypedArray())
            val rect = Imgproc.minAreaRect(MatOfPoint2f(*hullMat.toArray()))
            val boxPts = MatOfPoint2f()
            Imgproc.boxPoints(rect, boxPts)
            val corners = boxPts.toArray()
            hullMat.release(); hull.release(); boxPts.release()

            if (corners.size == 4) {
                val points = corners.map { PointF(it.x.toFloat(), it.y.toFloat()) }
                val sorted = sortCorners(points[0], points[1], points[2], points[3])
                val qArea = computeQuadArea(sorted[0], sorted[1], sorted[2], sorted[3])
                if (qArea >= imageArea * config.minAreaRatio * 0.6f) sorted else null
            } else null
        } catch (_: Exception) { null }
    }

    /**
     * 四边形验证：面积、边长、外接框
     */
    private fun validateQuad(quad: List<PointF>, w: Int, h: Int, imageArea: Float): Boolean {
        if (quad.size != 4) return false
        val (tl, tr, br, bl) = listOf(quad[0], quad[1], quad[2], quad[3])

        // 面积校验
        val qArea = computeQuadArea(tl, tr, br, bl)
        if (qArea < imageArea * config.minAreaRatio) return false

        // 外接框校验（防止细长/极小区域）
        val minX = quad.minOf { it.x }
        val maxX = quad.maxOf { it.x }
        val minY = quad.minOf { it.y }
        val maxY = quad.maxOf { it.y }
        val qw = maxX - minX
        val qh = maxY - minY
        if (qw < w * config.minSideRatio || qh < h * config.minSideRatio) return false

        // 角度校验：四边形内角应接近 90°（允许 ±45° 偏差）
        val angles = computeQuadAngles(tl, tr, br, bl)
        for (angle in angles) {
            // 角度应在 45° ~ 135° 之间
            if (angle < 45.0 || angle > 135.0) return false
        }

        return true
    }

    /**
     * 四边形综合评分（0 ~ 1+）
     *
     * 评分因素：
     * - 面积得分：覆盖越大越好
     * - 凸性得分：越凸越好
     * - 边缘对齐度：四边边缘梯度强度
     * - 角度得分：接近 90° 越好
     */
    private fun scoreQuad(
        quad: List<PointF>, w: Int, h: Int, imageArea: Float, contour: MatOfPoint? = null
    ): Float {
        if (quad.size != 4) return 0f
        val (tl, tr, br, bl) = listOf(quad[0], quad[1], quad[2], quad[3])

        // 1. 面积得分（越高越好）
        val qArea = computeQuadArea(tl, tr, br, bl)
        val areaScore = (qArea / imageArea).coerceIn(0f, 1f)

        // 2. 角度得分（接近 90° 越好）
        val angles = computeQuadAngles(tl, tr, br, bl)
        val angleScore = angles.map { angle ->
            val deviation = abs(angle - 90.0)
            maxOf(0.0, 1.0 - deviation / 45.0)
        }.average().toFloat()

        // 3. 边长度均衡性（防止畸形四边形）
        val sides = listOf(
            distance(tl, tr), distance(tr, br),
            distance(br, bl), distance(bl, tl)
        )
        val avgSide = sides.average().toFloat()
        val sideBalance = if (avgSide > 0f) {
            sides.map { 1f - abs(it - avgSide) / avgSide }
                .filter { it > 0f }
                .average()
                .toFloat()
        } else 0f

        // 4. 凸包比（轮廓面积接近凸包面积 → 凸四边形）
        var convexScore = 1.0f
        if (contour != null) {
            try {
                val hull = MatOfInt()
                Imgproc.convexHull(contour, hull)
                val hullPts = hull.toArray().map { contour.toArray()[it] }
                val hullMat = MatOfPoint(*hullPts.toTypedArray())
                val hullArea = Imgproc.contourArea(hullMat)
                val contourArea = Imgproc.contourArea(contour)
                hullMat.release(); hull.release()
                if (contourArea > 0f) {
                    convexScore = (contourArea / hullArea).coerceIn(0.0, 1.0).toFloat()
                }
            } catch (_: Exception) {}
        }

        // 综合评分
        return areaScore * 0.35f + angleScore * 0.25f +
                sideBalance * 0.20f + convexScore * 0.20f
    }

    // ════════════════════════════════════════════════════════════════
    //  辅助工具
    // ════════════════════════════════════════════════════════════════

    /**
     * 计算 Otsu 自适应阈值（基于 Sobel 梯度幅值）
     */
    private fun computeOtsuThreshold(gray: Mat, w: Int, h: Int): Double {
        return try {
            val sobelX = Mat()
            val sobelY = Mat()
            val sobelMag = Mat()
            Imgproc.Sobel(gray, sobelX, CvType.CV_32F, 1, 0)
            Imgproc.Sobel(gray, sobelY, CvType.CV_32F, 0, 1)
            Core.magnitude(sobelX, sobelY, sobelMag)

            val mag8u = Mat()
            sobelMag.convertTo(mag8u, CvType.CV_8U)
            val otsuThresh = Imgproc.threshold(mag8u, Mat(), 0.0, 255.0, Imgproc.THRESH_OTSU or Imgproc.THRESH_BINARY)

            sobelX.release(); sobelY.release(); sobelMag.release(); mag8u.release()
            maxOf(otsuThresh * 0.5, 30.0)
        } catch (_: Exception) {
            80.0  // 降级
        }
    }

    /**
     * 计算四边形四个内角
     */
    private fun computeQuadAngles(tl: PointF, tr: PointF, br: PointF, bl: PointF): List<Double> {
        fun angleBetween(a: PointF, center: PointF, b: PointF): Double {
            val v1x = a.x - center.x; val v1y = a.y - center.y
            val v2x = b.x - center.x; val v2y = b.y - center.y
            val dot = v1x * v2x + v1y * v2y
            val mag1 = sqrt(v1x * v1x + v1y * v1y)
            val mag2 = sqrt(v2x * v2x + v2y * v2y)
            if (mag1 == 0f || mag2 == 0f) return 90.0
            val cosAngle = (dot / (mag1 * mag2)).toDouble().coerceIn(-1.0, 1.0)
            return Math.toDegrees(acos(cosAngle))
        }
        return listOf(
            angleBetween(tl, tr, br),   // 右上角
            angleBetween(bl, br, tr),   // 右下角
            angleBetween(br, bl, tl),   // 左下角
            angleBetween(tr, tl, bl)    // 左上角
        )
    }

    /**
     * 拟合直线数据结构
     */
    private data class FittedLine(
        val directionX: Double,
        val directionY: Double,
        val pointX: Double,
        val pointY: Double,
        val angle: Double,
        val intercept: Double  // 截距（用于排序）
    )

    /**
     * 对点集做最小二乘直线拟合
     */
    private fun fitLineLeastSquares(points: List<PointF>): FittedLine? {
        if (points.size < 2) return null
        val n = points.size

        // 计算中心
        val meanX = points.map { it.x.toDouble() }.average()
        val meanY = points.map { it.y.toDouble() }.average()

        // 协方差矩阵
        var xx = 0.0; var xy = 0.0; var yy = 0.0
        for (p in points) {
            val dx = p.x - meanX; val dy = p.y - meanY
            xx += dx * dx; xy += dx * dy; yy += dy * dy
        }

        // 通过 PCA 找到主方向
        val theta = 0.5 * atan2(2.0 * xy, xx - yy)
        val dirX = cos(theta)
        val dirY = sin(theta)

        // 拟合线通过中心点 (meanX, meanY)
        // 截距：用隐式方程计算，通过中心点 (meanX, meanY)
        // 对于直线 ax + by + c = 0，a = dirY, b = -dirX
        // 截距在 x 轴方向
        val intercept = if (abs(dirX) > abs(dirY)) {
            meanY - (dirY / dirX) * meanX
        } else {
            meanX - (dirX / dirY) * meanY
        }

        return FittedLine(
            directionX = dirX,
            directionY = dirY,
            pointX = meanX,
            pointY = meanY,
            angle = theta,
            intercept = intercept
        )
    }

    /**
     * 求两条拟合直线的交点
     */
    private fun intersectTwoLines(l1: FittedLine, l2: FittedLine): PointF? {
        // 将直线表示为 ax + by + c = 0
        // 方向向量 (dx, dy)，通过中心点 (px, py)
        // 法向量: (-dy, dx)，所以直线方程为 -dy(x - px) + dx(y - py) = 0
        // a = -dy, b = dx, c = dy*px - dx*py

        val a1 = -l1.directionY; val b1 = l1.directionX
        val c1 = -(a1 * l1.pointX + b1 * l1.pointY)
        val a2 = -l2.directionY; val b2 = l2.directionX
        val c2 = -(a2 * l2.pointX + b2 * l2.pointY)

        val det = a1 * b2 - a2 * b1
        if (abs(det) < 1e-10) return null  // 平行

        val x = (b1 * c2 - b2 * c1) / det
        val y = (c1 * a2 - c2 * a1) / det
        return PointF((-x).toFloat(), (-y).toFloat())
    }

    /**
     * 分段数据结构（轮廓方向检测用）
     */
    private data class Segment(
        val points: List<org.opencv.core.Point>,
        val angle: Double
    )

    companion object {
        private const val TAG = "QuadDetector"

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

        private fun distance(p1: PointF, p2: PointF): Float {
            val dx = p2.x - p1.x; val dy = p2.y - p1.y
            return sqrt(dx * dx + dy * dy)
        }
    }
}
