package com.heartflow.app

import androidx.compose.ui.graphics.Color

// ==================== 终端配色主题 ====================

enum class TerminalTheme(
    val label: String,
    val bg: Color,
    val fg: Color,
    val prompt: Color,
    val cmd: Color,
    val error: Color,
    val success: Color,
    val warning: Color,
    val link: Color,
    val header: Color,
    val surface: Color,
    val surfaceVariant: Color
) {
    CLASSIC_GREEN(
        label = "经典绿幕", bg = Color(0xFF0D0D0D), fg = Color(0xFFCCCCCC),
        prompt = Color(0xFF00FF00), cmd = Color(0xFF00FF00),
        error = Color(0xFFFF6B6B), success = Color(0xFF50FA7B),
        warning = Color(0xFFFFD93D), link = Color(0xFF00BFFF),
        header = Color(0xFFBB86FC), surface = Color(0xFF2D2D2D),
        surfaceVariant = Color(0xFF1A1A1A)
    ),
    AMBER(
        label = "琥珀暖光", bg = Color(0xFF0A0A00), fg = Color(0xFFCCBB88),
        prompt = Color(0xFFFFB000), cmd = Color(0xFFFFB000),
        error = Color(0xFFFF4444), success = Color(0xFF88FF88),
        warning = Color(0xFFFFAA00), link = Color(0xFF66BBFF),
        header = Color(0xFFFFD700), surface = Color(0xFF2A2A00),
        surfaceVariant = Color(0xFF1A1A00)
    ),
    WHITE(
        label = "白底经典", bg = Color(0xFFF5F5F5), fg = Color(0xFF222222),
        prompt = Color(0xFF006600), cmd = Color(0xFF006600),
        error = Color(0xFFCC0000), success = Color(0xFF006600),
        warning = Color(0xFFCC8800), link = Color(0xFF0066CC),
        header = Color(0xFF6600CC), surface = Color(0xFFE8E8E8),
        surfaceVariant = Color(0xFFDDDDDD)
    ),
    NIGHT(
        label = "暗夜魅影", bg = Color(0xFF050510), fg = Color(0xFFAAAAAA),
        prompt = Color(0xFF00FFAA), cmd = Color(0xFF00FFAA),
        error = Color(0xFFFF5555), success = Color(0xFF55FF55),
        warning = Color(0xFFFFAA33), link = Color(0xFF33AAFF),
        header = Color(0xFFAA88FF), surface = Color(0xFF151525),
        surfaceVariant = Color(0xFF0A0A18)
    ),
    RETRO(
        label = "复古CRT", bg = Color(0xFF001100), fg = Color(0xFF33AA33),
        prompt = Color(0xFF22DD22), cmd = Color(0xFF22DD22),
        error = Color(0xFFDD2222), success = Color(0xFF22DD22),
        warning = Color(0xFFDDAA22), link = Color(0xFF2299DD),
        header = Color(0xFF88DD88), surface = Color(0xFF002200),
        surfaceVariant = Color(0xFF001800)
    );

    companion object {
        val default: TerminalTheme = CLASSIC_GREEN
    }
}
