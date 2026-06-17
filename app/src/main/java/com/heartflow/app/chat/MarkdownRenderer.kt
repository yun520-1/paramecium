package com.heartflow.app

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.AnnotatedString
import com.heartflow.data.*

// ─── 代码块渲染（含复制按钮 + 语法高亮） ────────────

@Composable
fun RichTextContent(content: String, isUser: Boolean) {
    val parts = content.split("```")
    Column {
        for ((index, part) in parts.withIndex()) {
            if (index % 2 == 0) {
                if (part.isNotBlank()) {
                    CompositionLocalProvider(LocalContentColor provides Color.White) {
                        MarkdownText(
                            content = part.trim(),
                            isUser = isUser,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                    }
                }
            } else {
                val lines = part.lines()
                val lang = if (lines.isNotEmpty() && !lines[0].contains(" ") && lines[0].length < 20) lines[0] else ""
                val code = if (lang.isNotEmpty()) lines.drop(1).joinToString("\n") else part

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E1E1E))
                ) {
                    Column {
                        if (lang.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF2D2D2D))
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(lang.uppercase(),
                                    color = Color(0xFF808080), fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = FontFamily.Monospace)
                                Spacer(Modifier.weight(1f))
                                val context = LocalContext.current
                                Text(
                                    "复制",
                                    color = Color(0xFF4CAF50), fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            val clip = android.content.ClipData.newPlainText("code", code.trim())
                                            clipboard.setPrimaryClip(clip)
                                            Toast.makeText(context, "代码已复制", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }

                        val trimmedCode = code.trim()
                        val highlighted = syntaxHighlight(trimmedCode, lang)
                        Text(
                            text = buildAnnotatedString {
                                for ((text, color) in highlighted) {
                                    withStyle(SpanStyle(color = color, fontFamily = FontFamily.Monospace)) {
                                        append(text)
                                    }
                                }
                            },
                            fontSize = 12.sp, lineHeight = 18.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

// 简易语法高亮
internal fun syntaxHighlight(code: String, lang: String): List<Pair<String, Color>> {
    val tokens = mutableListOf<Pair<String, Color>>()
    val commentColor = Color(0xFF6A9955)
    val keywordColor = Color(0xFF569CD6)
    val stringColor = Color(0xFFCE9178)
    val numberColor = Color(0xFFB5CEA8)
    val defaultColor = Color(0xFFD4D4D4)

    val keywords = setOf(
        "if", "else", "for", "while", "do", "return", "fun", "val", "var",
        "class", "object", "interface", "enum", "data", "sealed", "abstract",
        "override", "private", "public", "protected", "internal", "open",
        "import", "package", "companion", "init", "constructor", "by",
        "when", "try", "catch", "finally", "throw", "let", "apply", "run",
        "with", "also", "takeIf", "takeUnless", "null", "true", "false",
        "suspend", "inline", "tailrec", "operator", "infix", "crossinline",
        "reified", "annotation", "typealias", "const", "lateinit", "sealed"
    )

    var i = 0
    while (i < code.length) {
        if (i + 1 < code.length && code[i] == '/' && code[i + 1] == '/') {
            val end = code.indexOf('\n', i)
            val comment = if (end == -1) code.substring(i) else code.substring(i, end)
            tokens.add(comment to commentColor)
            i = if (end == -1) code.length else end
            continue
        }
        if (i + 1 < code.length && code[i] == '/' && code[i + 1] == '*') {
            val end = code.indexOf("*/", i)
            val comment = if (end == -1) code.substring(i) else code.substring(i, end + 2)
            tokens.add(comment to commentColor)
            i = if (end == -1) code.length else end + 2
            continue
        }
        if (code[i] == '"' || code[i] == '\'') {
            val quote = code[i]
            val start = i
            i++
            while (i < code.length && code[i] != quote) {
                if (code[i] == '\\') i++
                i++
            }
            if (i < code.length) i++
            tokens.add(code.substring(start, i) to stringColor)
            continue
        }
        if (i + 2 < code.length && code[i] == '"' && code[i + 1] == '"' && code[i + 2] == '"') {
            val start = i
            i += 3
            while (i + 2 < code.length && !(code[i] == '"' && code[i + 1] == '"' && code[i + 2] == '"')) i++
            if (i + 2 < code.length) i += 3
            tokens.add(code.substring(start, i) to stringColor)
            continue
        }
        if (code[i].isDigit() || (code[i] == '.' && i + 1 < code.length && code[i + 1].isDigit())) {
            val start = i
            while (i < code.length && (code[i].isLetterOrDigit() || code[i] == '.' || code[i] == 'x' || code[i] == 'X' || code[i] == 'L' || code[i] == 'f')) i++
            tokens.add(code.substring(start, i) to numberColor)
            continue
        }
        if (code[i].isLetter() || code[i] == '_') {
            val start = i
            while (i < code.length && (code[i].isLetterOrDigit() || code[i] == '_')) i++
            val word = code.substring(start, i)
            tokens.add(word to if (word in keywords) keywordColor else defaultColor)
            continue
        }
        tokens.add(code[i].toString() to defaultColor)
        i++
    }
    return tokens
}

// ─── Markdown 渲染（内联解析 + 块级布局） ──────────

/** 判断文本是否包含 Markdown 格式标记 */
internal val markdownRegex = Regex("""(\*\*|__|\*|_|`|\[|~~|^#|^>|^- |^\d+\.)""", RegexOption.MULTILINE)

/** 块级 Markdown 元素 */
internal data class MdBlock(val type: Int, val text: String, val subItems: List<String> = emptyList())

/** 将文本解析为块级元素列表（标题、段落、引用、列表、分割线） */
internal fun parseBlockMarkdown(content: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = content.split("\n")
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val t = raw.trim()

        when {
            t.isEmpty() -> { i++; continue }
            t.startsWith("### ") -> blocks.add(MdBlock(3, t.removePrefix("### ").trim()))
            t.startsWith("## ") -> blocks.add(MdBlock(2, t.removePrefix("## ").trim()))
            t.startsWith("# ") -> blocks.add(MdBlock(1, t.removePrefix("# ").trim()))
            t.matches(Regex("^-{3,}$")) || t.matches(Regex("^\\*{3,}$")) || t.matches(Regex("^_{3,}$")) ->
                blocks.add(MdBlock(0, ""))
            t.startsWith("> ") || t.startsWith(">") -> {
                val q = mutableListOf(t.trimStart().removePrefix(">").trim())
                while (i + 1 < lines.size && lines[i + 1].trimStart().startsWith(">")) {
                    i++; q.add(lines[i].trimStart().removePrefix(">").trim())
                }
                blocks.add(MdBlock(5, q.joinToString("\n")))
            }
            (t.startsWith("- ") || t.startsWith("* ")) -> {
                val items = mutableListOf(t.drop(2).trim())
                while (i + 1 < lines.size) {
                    val n = lines[i + 1].trim()
                    if (n.startsWith("- ") || n.startsWith("* ")) {
                        i++; items.add(n.drop(2).trim())
                    } else break
                }
                blocks.add(MdBlock(6, "", items))
            }
            t.matches(Regex("^\\d+\\.\\s.*")) -> {
                val items = mutableListOf(t.replaceFirst(Regex("^\\d+\\.\\s"), "").trim())
                while (i + 1 < lines.size) {
                    val n = lines[i + 1].trim()
                    if (n.matches(Regex("^\\d+\\.\\s.*"))) {
                        i++; items.add(n.replaceFirst(Regex("^\\d+\\.\\s"), "").trim())
                    } else break
                }
                blocks.add(MdBlock(7, "", items))
            }
            else -> {
                val para = mutableListOf(t)
                while (i + 1 < lines.size) {
                    val n = lines[i + 1].trim()
                    if (n.isBlank() || n.startsWith("#") || n.startsWith("> ") ||
                        n.startsWith("- ") || n.startsWith("* ") || n.matches(Regex("^\\d+\\.\\s.*"))) break
                    i++; para.add(n)
                }
                blocks.add(MdBlock(4, para.joinToString(" ")))
            }
        }
        i++
    }
    return blocks
}

/** 将内联 Markdown 格式（**加粗** *斜体* `代码` ~~删除线~~ [链接](url)）构建为 AnnotatedString */
internal fun buildInlineMarkdown(
    text: String,
    baseColor: Color,
    accentColor: Color,
    codeFg: Color,
    codeBg: Color
): AnnotatedString = buildAnnotatedString {
    var pos = 0
    while (pos < text.length) {
        val tripleBacktick = text.indexOf("```", pos)
        val doubleTilde  = indexOfPattern(text, "~~", pos)
        val doubleStar   = indexOfPattern(text, "**", pos)
        val inlineCode   = indexOfPattern(text, "`", pos)
        val singleStar   = indexOfNotDouble(text, '*', doubleStar, pos)
        val linkBracket  = text.indexOf('[', pos)

        val earliest = listOfNotNull(
            if (tripleBacktick >= 0) tripleBacktick to "tick3" else null,
            if (doubleTilde >= 0) doubleTilde to "strike" else null,
            if (doubleStar >= 0) doubleStar to "bold" else null,
            if (inlineCode >= 0 && inlineCode != tripleBacktick) inlineCode to "code" else null,
            if (singleStar >= 0) singleStar to "italic" else null,
            if (linkBracket >= 0) linkBracket to "link" else null
        ).filter { it.first >= pos }.minByOrNull { it.first }

        if (earliest == null || earliest.first == Int.MAX_VALUE) {
            withStyle(SpanStyle(color = baseColor)) { append(text.substring(pos)) }
            break
        }

        if (earliest.first > pos) {
            withStyle(SpanStyle(color = baseColor)) { append(text.substring(pos, earliest.first)) }
        }

        when (earliest.second) {
            "tick3" -> {
                val end = text.indexOf("```", earliest.first + 3)
                if (end > earliest.first) {
                    withStyle(SpanStyle(color = codeFg, fontFamily = FontFamily.Monospace, background = codeBg)) {
                        append(text.substring(earliest.first + 3, end))
                    }
                    pos = end + 3
                } else { append("```"); pos = earliest.first + 3 }
            }
            "strike" -> {
                val end = text.indexOf("~~", earliest.first + 2)
                if (end > earliest.first) {
                    withStyle(SpanStyle(color = baseColor, textDecoration = TextDecoration.LineThrough)) {
                        append(text.substring(earliest.first + 2, end))
                    }
                    pos = end + 2
                } else { append("~~"); pos = earliest.first + 2 }
            }
            "bold" -> {
                val end = text.indexOf("**", earliest.first + 2)
                if (end > earliest.first) {
                    withStyle(SpanStyle(color = baseColor, fontWeight = FontWeight.Bold)) {
                        append(text.substring(earliest.first + 2, end))
                    }
                    pos = end + 2
                } else { append("**"); pos = earliest.first + 2 }
            }
            "code" -> {
                val end = text.indexOf('`', earliest.first + 1)
                if (end > earliest.first) {
                    withStyle(SpanStyle(color = codeFg, fontFamily = FontFamily.Monospace, background = codeBg)) {
                        append(text.substring(earliest.first + 1, end))
                    }
                    pos = end + 1
                } else { append("`"); pos = earliest.first + 1 }
            }
            "italic" -> {
                val end = text.indexOf('*', earliest.first + 1)
                if (end > earliest.first && (end + 1 >= text.length || text[end + 1] != '*')) {
                    withStyle(SpanStyle(color = baseColor, fontStyle = FontStyle.Italic)) {
                        append(text.substring(earliest.first + 1, end))
                    }
                    pos = end + 1
                } else { append("*"); pos = earliest.first + 1 }
            }
            "link" -> {
                val closeBracket = text.indexOf("](", earliest.first + 1)
                if (closeBracket > earliest.first) {
                    val closeParen = text.indexOf(")", closeBracket + 2)
                    if (closeParen > closeBracket) {
                        withStyle(SpanStyle(color = accentColor, textDecoration = TextDecoration.Underline)) {
                            append(text.substring(earliest.first + 1, closeBracket))
                        }
                        pos = closeParen + 1
                    } else { append("["); pos = earliest.first + 1 }
                } else { append("["); pos = earliest.first + 1 }
            }
        }
    }
}

/** 查找普通字符串模式（非重叠） */
internal fun indexOfPattern(text: String, pattern: String, start: Int): Int {
    return text.indexOf(pattern, start)
}

/** 查找单个星号（但不是 ** 的一部分） */
internal fun indexOfNotDouble(text: String, ch: Char, doubleIdx: Int, start: Int): Int {
    var i = start
    while (i < text.length) {
        if (text[i] == ch) {
            if (doubleIdx >= 0 && i == doubleIdx) {
                i += 2; continue
            }
            if (i + 1 < text.length && text[i + 1] == ch) {
                i += 2; continue
            }
            return i
        }
        i++
    }
    return -1
}

/**
 * MarkdownText - 支持内联格式的 Markdown 文本渲染组合函数
 *
 * 处理：**加粗** *斜体* `代码` ~~删除线~~ [链接](url)
 * 以及块级：标题、引用、列表、分割线
 */
@Composable
fun MarkdownText(
    content: String,
    isUser: Boolean = false,
    isError: Boolean = false,
    isStreaming: Boolean = false,
    fontSize: TextUnit = 14.sp,
    lineHeight: TextUnit = 20.sp
) {
    val isDark = isSystemInDarkTheme()
    val baseColor = when {
        isUser -> Color.White
        isError -> Color(0xFFD32F2F)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val accentColor = if (isUser) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.primary
    val codeFg = if (isUser) Color(0xFFFF80AB) else if (isDark) Color(0xFFE83E8C) else Color(0xFFD63384)
    val codeBg = if (isUser) Color.White.copy(alpha = 0.12f) else if (isDark) Color(0xFF2D2D2D) else Color(0xFFF0F0F0)

    val hasBlockMarks = content.contains("#") || content.contains(">") ||
            content.startsWith("- ") || content.startsWith("* ") ||
            content.matches(Regex("^\\d+\\.\\s.*")) || content.contains("---")

    if (!hasBlockMarks && !content.contains("**") && !content.contains("*") &&
        !content.contains("`") && !content.contains("[") && !content.contains("~~")) {
        Text(content, color = baseColor, fontSize = fontSize, lineHeight = lineHeight)
        return
    }

    if (hasBlockMarks) {
        val blocks = remember(content) { parseBlockMarkdown(content) }
        Column {
            blocks.forEach { block ->
                when (block.type) {
                    0 -> {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 6.dp),
                            thickness = 1.dp,
                            color = baseColor.copy(alpha = 0.15f)
                        )
                    }
                    1, 2, 3 -> {
                        val size = when (block.type) { 1 -> 20.sp; 2 -> 17.sp; else -> 15.sp }
                        Text(
                            text = buildInlineMarkdown(block.text, baseColor, accentColor, codeFg, codeBg),
                            color = baseColor,
                            fontSize = size,
                            fontWeight = FontWeight.Bold,
                            lineHeight = size * 1.3f,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                    5 -> {
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(36.dp)
                                    .background(accentColor.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = buildInlineMarkdown(block.text, baseColor.copy(alpha = 0.8f), accentColor, codeFg, codeBg),
                                color = baseColor.copy(alpha = 0.8f),
                                fontStyle = FontStyle.Italic,
                                fontSize = fontSize,
                                lineHeight = lineHeight
                            )
                        }
                    }
                    6 -> {
                        Column(Modifier.padding(vertical = 1.dp)) {
                            block.subItems.forEach { item ->
                                Row(Modifier.padding(vertical = 1.dp)) {
                                    Text("  •  ", color = accentColor, fontSize = fontSize)
                                    Text(
                                        text = buildInlineMarkdown(item, baseColor, accentColor, codeFg, codeBg),
                                        color = baseColor, fontSize = fontSize, lineHeight = lineHeight
                                    )
                                }
                            }
                        }
                    }
                    7 -> {
                        Column(Modifier.padding(vertical = 1.dp)) {
                            block.subItems.forEachIndexed { idx, item ->
                                Row(Modifier.padding(vertical = 1.dp)) {
                                    Text("  ${idx + 1}.  ", color = baseColor.copy(alpha = 0.7f), fontSize = fontSize)
                                    Text(
                                        text = buildInlineMarkdown(item, baseColor, accentColor, codeFg, codeBg),
                                        color = baseColor, fontSize = fontSize, lineHeight = lineHeight
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        Text(
                            text = buildInlineMarkdown(block.text, baseColor, accentColor, codeFg, codeBg),
                            color = baseColor,
                            fontSize = fontSize,
                            lineHeight = lineHeight,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    } else {
        val annotated: AnnotatedString = remember(content) { buildInlineMarkdown(content, baseColor, accentColor, codeFg, codeBg) }
        Text(text = annotated, color = baseColor, fontSize = fontSize, lineHeight = lineHeight)
    }
}
