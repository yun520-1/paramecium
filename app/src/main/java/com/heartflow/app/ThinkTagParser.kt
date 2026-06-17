package com.heartflow.app

/**
 * 思考标签解析器 - 用于解析流式输出中的 <thinking>...</thinking> 标签
 *
 * 某些模型（如 DeepSeek）会在输出中包含 <thinking> 标签包裹的推理过程，
 * 这个解析器可以将这些标签过滤掉，分别返回可见文本和隐藏的思考内容。
 *
 * 使用方式：
 * 1. 对每个 delta 调用 [append(delta)]，会增量解析并返回结果
 * 2. 流结束时调用 [flush()] 获取剩余的未解析内容
 *
 * 注意：这个解析器处理的是 **已解码** 的文本中的 XML 风格标签，
 * 不是 SSE 数据中的 thinking_content 字段。
 */
class ThinkTagParser {
    companion object {
        private const val START = "<thinking>"
        private const val END = "</thinking>"
    }

    private val pending = StringBuilder()
    private var inThinking = false

    /**
     * 追加新的 delta 文本并解析
     * @param delta 新收到的文本片段
     * @return 解析结果（可见文本 + 思考内容）
     */
    fun append(delta: String?): ThinkResult {
        pending.append(delta ?: "")
        val text = StringBuilder()
        val thinking = StringBuilder()

        while (pending.length > 0) {
            val tag = if (inThinking) END else START
            val tagIndex = indexOf(pending, tag)
            if (tagIndex >= 0) {
                // 找到标签：追加标签前的内容到对应缓冲区
                if (inThinking) {
                    thinking.append(pending.substring(0, tagIndex))
                } else {
                    text.append(pending.substring(0, tagIndex))
                }
                // 删除标签及之前的内容
                pending.delete(0, tagIndex + tag.length)
                // 切换状态
                inThinking = !inThinking
                continue
            }

            // 未找到完整标签，检查是否有尾随前缀需要保留
            val keep = trailingPrefixLength(pending, tag)
            val emitEnd = pending.length - keep
            if (emitEnd <= 0) {
                break
            }
            // 追加可确定的内容
            if (inThinking) {
                thinking.append(pending.substring(0, emitEnd))
            } else {
                text.append(pending.substring(0, emitEnd))
            }
            pending.delete(0, emitEnd)
        }

        return ThinkResult(text.toString(), thinking.toString())
    }

    /**
     * 刷新解析器，获取所有剩余内容
     * 用于流结束时处理最后可能残留的未解析内容
     * @return 剩余内容
     */
    fun flush(): ThinkResult {
        val remaining = pending.toString()
        pending.setLength(0)
        return if (inThinking) {
            // 如果还在 thinking 标签内，剩余内容全部是思考
            ThinkResult("", remaining)
        } else {
            ThinkResult(remaining, "")
        }
    }

    /**
     * 在 StringBuilder 中查找子串位置
     */
    private fun indexOf(builder: StringBuilder, needle: String): Int {
        val max = builder.length - needle.length
        for (i in 0..max) {
            var j = 0
            while (j < needle.length && builder[i + j] == needle[j]) {
                j++
            }
            if (j == needle.length) {
                return i
            }
        }
        return -1
    }

    /**
     * 计算字符串末尾与目标前缀匹配的长度
     * 用于判断是否有部分标签被截断需要保留
     */
    private fun trailingPrefixLength(builder: StringBuilder, tag: String): Int {
        val max = minOf(builder.length, tag.length - 1)
        for (length in max downTo 1) {
            var matches = true
            val start = builder.length - length
            for (i in 0 until length) {
                if (builder[start + i] != tag[i]) {
                    matches = false
                    break
                }
            }
            if (matches) {
                return length
            }
        }
        return 0
    }

    /**
     * 解析结果数据类
     */
    data class ThinkResult(
        val text: String,       // 可见文本（标签之间的内容）
        val thinking: String    // 思考内容（thinking 标签内的内容）
    )
}
