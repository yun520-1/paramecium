package com.heartflow.tool.builtin

import com.heartflow.tool.ToolContext
import java.io.File
import java.io.IOException

/**
 * 文件工具路径安全策略
 * - 防止路径遍历攻击
 * - 验证路径在允许范围内
 */
object FileToolPathPolicy {
    @Throws(IOException::class)
    fun resolve(context: ToolContext, inputPath: String): File {
        if (context == null) {
            throw IOException("工具上下文为空")
        }
        return resolve(context.homePath, context.extraWriteRoots, inputPath)
    }

    @Throws(IOException::class)
    private fun resolve(homePath: String, extraRoots: List<String>, inputPath: String): File {
        if (homePath == null || homePath.trim().isEmpty()) {
            throw IOException("工作区路径为空")
        }
        val rawPath = inputPath?.trim() ?: ""
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
                throw IOException("路径超出当前工作区和已授权目录: " + rawPath)
            }
        }
        return canonical
    }

    @Throws(IOException::class)
    fun displayPath(homePath: String, file: File): String {
        val root = File(homePath).canonicalFile
        val target = file.canonicalFile
        val rootPath = root.path
        val targetPath = target.path
        return when {
            targetPath == rootPath -> "."
            targetPath.startsWith(rootPath + File.separator) -> targetPath.substring(rootPath.length + 1)
            else -> targetPath
        }
    }

    private fun isInside(root: File, target: File): Boolean {
        val rootPath = root.path
        val targetPath = target.path
        return targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
    }

    @Throws(IOException::class)
    private fun matchingExtraRoot(extraRoots: List<String>, target: File): File? {
        if (extraRoots.isNullOrEmpty()) {
            return null
        }
        for (rootPath in extraRoots) {
            if (rootPath == null || rootPath.trim().isEmpty()) {
                continue
            }
            val root = File(rootPath.trim()).canonicalFile
            if (isInside(root, target)) {
                return root
            }
        }
        return null
    }
}
