package com.heartflow.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

// ==================== ANSI 解析器 ====================

object AnsiParser {
    private val ansiPattern = Regex("\\[[0-9;]*[a-zA-Z]")
    private val sgrPattern = Regex("\\[([0-9;]*)m")

    private val ansiFg = mapOf(
        30 to Color(0xFF000000), 31 to Color(0xFFCC0000),
        32 to Color(0xFF4E9A06), 33 to Color(0xFFC4A000),
        34 to Color(0xFF3465A4), 35 to Color(0xFF75507B),
        36 to Color(0xFF06989A), 37 to Color(0xFFD3D7CF),
        90 to Color(0xFF555555), 91 to Color(0xFFEF2929),
        92 to Color(0xFF8AE234), 93 to Color(0xFFFCE94F),
        94 to Color(0xFF729FCF), 95 to Color(0xFFAD7FA8),
        96 to Color(0xFF34E2E2), 97 to Color(0xFFEEEEEE)
    )

    private val ansiBg = mapOf(
        40 to Color(0xFF000000), 41 to Color(0xFFCC0000),
        42 to Color(0xFF4E9A06), 43 to Color(0xFFC4A000),
        44 to Color(0xFF3465A4), 45 to Color(0xFF75507B),
        46 to Color(0xFF06989A), 47 to Color(0xFFD3D7CF),
        100 to Color(0xFF555555), 101 to Color(0xFFEF2929),
        102 to Color(0xFF8AE234), 103 to Color(0xFFFCE94F),
        104 to Color(0xFF729FCF), 105 to Color(0xFFAD7FA8),
        106 to Color(0xFF34E2E2), 107 to Color(0xFFEEEEEE)
    )

    /** 剥离所有 ANSI 转义码，返回纯文本 */
    fun strip(input: String): String = input.replace(ansiPattern, "")

    /** 将含 ANSI 码的文本转为 Compose AnnotatedString */
    fun toAnnotatedString(input: String, defaultFg: Color = Color(0xFFCCCCCC)): AnnotatedString {
        return buildAnnotatedString {
            var fg: Color = defaultFg; var bg: Color = Color.Transparent; var bold = false
            val pattern = Regex("\\[([0-9;]*)m")
            var lastEnd = 0

            for (match in pattern.findAll(input)) {
                if (match.range.first > lastEnd) {
                    val text = input.substring(lastEnd, match.range.first)
                    val style = SpanStyle(
                        color = fg,
                        background = bg,
                        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
                    )
                    withStyle(style) { append(text) }
                }

                val params = match.groupValues[1]
                    .split(";").mapNotNull { it.toIntOrNull() }

                if (params.isEmpty() || params[0] == 0) {
                    fg = defaultFg; bg = Color.Transparent; bold = false // 重置
                } else {
                    var i = 0
                    while (i < params.size) {
                        val p = params[i]
                        when {
                            p == 1 -> bold = true
                            p == 22 -> bold = false
                            p in 30..37 -> fg = ansiFg[p] ?: defaultFg
                            p == 38 -> { // 扩展色 — 跳过
                                if (i + 1 < params.size && params[i + 1] == 5) i += 3
                                else if (i + 4 < params.size && params[i + 1] == 2) i += 5
                            }
                            p == 39 -> fg = defaultFg
                            p in 40..47 -> bg = ansiBg[p] ?: Color.Transparent
                            p == 48 -> {
                                if (i + 1 < params.size && params[i + 1] == 5) i += 3
                                else if (i + 4 < params.size && params[i + 1] == 2) i += 5
                            }
                            p == 49 -> bg = Color.Transparent
                            p in 90..97 -> fg = ansiFg[p] ?: defaultFg
                            p in 100..107 -> bg = ansiBg[p] ?: Color.Transparent
                        }
                        i++
                    }
                }
                lastEnd = match.range.last + 1
            }
            if (lastEnd < input.length) {
                withStyle(SpanStyle(color = fg)) {
                    append(input.substring(lastEnd))
                }
            }
        }
    }
}
