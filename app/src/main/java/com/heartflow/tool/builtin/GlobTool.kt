package com.heartflow.tool.builtin

import com.heartflow.tool.BaseTool
import com.heartflow.tool.ToolCategory
import java.io.File
import java.util.regex.Pattern

/**
 * 文件搜索工具
 * - 支持 * ** ? 通配符
 * - 递归搜索目录
 * - 最多返回 1000 个结果
 */
class GlobTool : BaseTool() {
    companion object {
        private const val MAX_RESULTS = 1000
    }

    override fun getName(): String = "glob"

    override fun getDescription(): String = "搜索匹配的文件。支持 * ** ? 通配符。"

    override fun getCategory(): ToolCategory = ToolCategory.READ

    override fun getParameters(): org.json.JSONObject = org.json.JSONObject()
        .put("type", "object")
        .put("properties", org.json.JSONObject()
            .put("pattern", org.json.JSONObject().put("type", "string").put("description", "文件匹配模式，如 *.java, app/src/**/*.java"))
            .put("path", org.json.JSONObject().put("type", "string").put("description", "搜索根目录，可选，默认为 home 目录")))
        .put("required", org.json.JSONArray().put("pattern"))

    override fun execute(input: org.json.JSONObject, context: com.heartflow.tool.ToolContext): com.heartflow.tool.ToolResult {
        return try {
            val pattern = input.optString("pattern")
            if (pattern.trim().isEmpty()) {
                return error("pattern 不能为空")
            }
            val root = FileToolPathPolicy.resolve(context, input.optString("path"))
            if (!root.exists() || !root.isDirectory) {
                return error("搜索根目录不存在或不是目录: " + input.optString("path", "."))
            }
            val results = mutableListOf<String>()
            val compiled = Pattern.compile(globToRegex(pattern))
            search(root, "", pattern, compiled, results)
            val displayRoot = FileToolPathPolicy.displayPath(context.homePath, root)
            if (results.isEmpty()) {
                return ok("在 " + displayRoot + " 目录下未找到匹配 \"" + pattern + "\" 的文件。")
            }
            val builder = StringBuilder()
            builder.append("在 ").append(displayRoot).append(" 目录下找到 ").append(results.size).append(" 个匹配文件:\n")
            for (result in results) {
                builder.append(result).append('\n')
            }
            if (results.size >= MAX_RESULTS) {
                builder.append("... (结果过多，已截断)\n")
            }
            ok(builder.toString().trim())
        } catch (e: Exception) {
            error("搜索失败: " + e.message)
        }
    }

    private fun search(dir: File, parentPath: String, pattern: String, compiled: java.util.regex.Pattern, results: MutableList<String>) {
        if (results.size >= MAX_RESULTS) {
            return
        }
        val items = dir.listFiles() ?: return
        items.sortWith(Comparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) })
        for (item in items) {
            if (results.size >= MAX_RESULTS) {
                return
            }
            val name = item.name
            val relative = if (parentPath.isEmpty()) name else "$parentPath/$name"
            if (item.isDirectory) {
                if (!name.startsWith(".") && name != "node_modules") {
                    search(item, relative, pattern, compiled, results)
                }
            } else if (compiled.matcher(relative).matches()
                || (pattern.indexOf('/') < 0 && compiled.matcher(name).matches())) {
                results.add(relative)
            }
        }
    }

    private fun globToRegex(glob: String): String {
        val regex = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when (c) {
                '*' -> {
                    if (i + 1 < glob.length && glob[i + 1] == '*') {
                        regex.append(".*")
                        i++
                    } else {
                        regex.append("[^/]*")
                    }
                }
                '?' -> regex.append("[^/]")
                in "\\.[]{}()+-^$|".toSet() -> regex.append('\\').append(c)
                else -> regex.append(c)
            }
            i++
        }
        regex.append('$')
        return regex.toString()
    }

    private fun ok(content: String): com.heartflow.tool.ToolResult = com.heartflow.tool.ToolResult("", getName(), content, false)

    private fun error(content: String): com.heartflow.tool.ToolResult = com.heartflow.tool.ToolResult("", getName(), content, true)
}
