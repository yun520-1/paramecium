package com.heartflow.app.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Image
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 图像增强滤镜类型
 *
 * 基于 iOS Core Image 滤镜图实现的专业文档扫描效果：
 * - AUTO: 自动增强（直方图均衡化 + 饱和度提升）
 * - WHITE: 白纸文档（提亮阴影、高对比度）
 * - B&W: 黑白（灰度 + 高对比度）
 * - DENOISE: 去噪（降噪 + 高光保护）
 * - BRIGHT: 提亮（曝光补偿）
 * - SHARPEN: 锐化文字（USM锐化）
 * - RECEIPT: 小票（灰度 + 高对比 + 锐化）
 *
 * 超越夸克扫描王的核心算法库
 */
enum class ScanFilterType(
    val raw: String,
    val displayName: String,
    val description: String
) {
    ORIGINAL("original", "原图", "保持原始图像"),
    AUTO_ENHANCE("autoEnhance", "自动增强", "AI智能优化色彩和对比度"),
    WHITE_DOCUMENT("whiteDocument", "文档增强", "适合白纸/打印文档"),
    BLACK_AND_WHITE("blackAndWhite", "黑白模式", "高对比度黑白扫描"),
    REMOVE_NOISE("removeNoise", "去噪模式", "去除照片噪点"),
    BRIGHTEN("brighten", "提亮模式", "增强暗部细节"),
    SHARPEN_TEXT("sharpenText", "文字锐化", "突出文字边缘"),
    RECEIPT("receipt", "小票模式", "优化收据/发票");

    val icon: ImageVector
        get() = when (this) {
            ORIGINAL -> Icons.Filled.Image
            AUTO_ENHANCE -> Icons.Filled.AutoFixHigh
            WHITE_DOCUMENT -> Icons.Filled.Description
            BLACK_AND_WHITE -> Icons.Filled.Contrast
            REMOVE_NOISE -> Icons.Filled.AutoAwesome
            BRIGHTEN -> Icons.Filled.Brightness6
            SHARPEN_TEXT -> Icons.Filled.TextFields
            RECEIPT -> Icons.Filled.ReceiptLong
        }

    companion object {
        fun fromRaw(raw: String?): ScanFilterType =
            entries.firstOrNull { it.raw == raw } ?: AUTO_ENHANCE
    }
}
