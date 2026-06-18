package com.heartflow.tool

import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * 文件工具路径策略
 * 确保文件操作在允许的目录范围内
 */
object FileToolPathPolicy {

    /**
     * 解析路径为规范化的 File 对象
     */
    @Throws(IOException::class)
    fun resolve(homePath: String, inputPath: String): File {
        return resolveWithExtraRoots(homePath, emptyList(), inputPath)
    }

    /**
     * 使用上下文解析路径
     */
    @Throws(IOException::class)
    fun resolve(context: ToolContext, inputPath: String): File {
        return resolveWithExtraRoots(
            context.homePath,
            context.extraWriteRoots,
            inputPath
        )
    }

    /**
     * 带额外根目录的路径解析
     */
    @Throws(IOException::class)
    private fun resolveWithExtraRoots(
        homePath: String,
        extraRoots: List<String>,
        inputPath: String
    ): File {
        if (homePath.isNullOrBlank()) {
            throw IOException("工作区路径为空")
        }

        val rawPath = inputPath.trim()
        val root = File(homePath).canonicalFile

        val target = if (rawPath.isEmpty()) {
            root
        } else if (File(rawPath).isAbsolute) {
            File(rawPath)
        } else {
            File(root, rawPath)
        }

        val canonical = target.canonicalFile

        if (!isInside(root, canonical)) {
            val allowedRoot = matchingExtraRoot(extraRoots, canonical)
            if (allowedRoot == null) {
                throw IOException("路径超出当前工作区和已授权目录: $rawPath")
            }
        }

        return canonical
    }

    /**
     * 获取显示路径（相对于 homePath）
     */
    @Throws(IOException::class)
    fun displayPath(homePath: String, file: File): String {
        val root = File(homePath).canonicalFile
        val target = file.canonicalFile
        val rootPath = root.path
        val targetPath = target.path

        return when {
            targetPath == rootPath -> "."
            targetPath.startsWith(rootPath + File.separator) ->
                targetPath.substring(rootPath.length + 1)
            else -> targetPath
        }
    }

    /**
     * 检查 target 是否在 root 内部
     */
    private fun isInside(root: File, target: File): Boolean {
        val rootPath = root.path
        val targetPath = target.path
        return targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
    }

    /**
     * 查找匹配的额外根目录
     */
    @Throws(IOException::class)
    private fun matchingExtraRoot(extraRoots: List<String>?, target: File): File? {
        if (extraRoots.isNullOrEmpty()) {
            return null
        }

        for (rootPath in extraRoots) {
            if (rootPath.isNullOrBlank()) continue

            val root = File(rootPath.trim()).canonicalFile
            if (isInside(root, target)) {
                return root
            }
        }
        return null
    }
}
