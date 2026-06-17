package com.heartflow.app

import java.io.File

// ==================== 数据类型 ====================

data class TerminalLine(
    val content: String,
    val isCommand: Boolean = false,
    val isError: Boolean = false,
    val isLink: Boolean = false,
    val isWarning: Boolean = false,
    val isSuccess: Boolean = false,
    val isHeader: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

const val MAX_LINES = 2000
const val FOLD_THRESHOLD = 50
const val MAX_HISTORY = 200
const val DEFAULT_DIR = "/sdcard"

// ==================== 命令补全引擎 ====================

object CommandCompleter {
    private val knownCommands = listOf(
        "cd", "ls", "cat", "echo", "pwd", "clear", "help", "exit",
        "cp", "mv", "rm", "mkdir", "touch", "chmod", "chown",
        "grep", "find", "sort", "head", "tail", "wc", "diff",
        "ps", "top", "kill", "df", "du", "free", "uptime", "dmesg",
        "date", "whoami", "id", "uname", "hostname", "env",
        "tar", "gzip", "zip", "unzip",
        "ping", "curl", "wget", "netstat", "ifconfig", "ip",
        "mount", "umount", "dd",
        "sh", "bash", "busybox",
        "su", "am", "pm", "input", "wm", "settings", "cmd",
        "git", "python", "python3", "node", "npm", "pip",
        "hermes", "vivid",
        "install", "fetch", "search", "github", "time", "info",
        "export", "which", "jobs",
        "git add", "git branch", "git checkout", "git clone", "git commit",
        "git diff", "git fetch", "git init", "git log", "git merge",
        "git pull", "git push", "git remote", "git reset", "git status", "git tag"
    )

    fun complete(input: String, currentDir: String): CompletionResult {
        val parts = input.trimStart().split(" ")
        val lastWordStart = input.lastIndexOf(' ') + 1
        val prefix = input.substring(lastWordStart)

        // 多参数时补全文件路径
        if (parts.size > 1 || (parts.size == 1 && input.trimEnd().endsWith(" ") && input.isNotBlank())) {
            return completeFilePath(prefix, currentDir, parts.firstOrNull() ?: "")
        }

        // 首参数：命令+文件
        return completeFirstArg(prefix, currentDir)
    }

    private fun completeFirstArg(prefix: String, currentDir: String): CompletionResult {
        val prefixLower = prefix.lowercase()
        val cmdMatches = knownCommands.filter { it.startsWith(prefixLower) }.take(20)
        val fileMatches = try {
            File(currentDir).listFiles()
                ?.filter { !it.isDirectory && it.name.lowercase().startsWith(prefixLower) }
                ?.map { it.name }?.take(15) ?: emptyList()
        } catch (_: Exception) { emptyList() }

        val allMatches = (cmdMatches + fileMatches).distinct().take(30)
        return if (allMatches.isEmpty()) {
            completeFilePath(prefix, currentDir, "")
        } else if (allMatches.size == 1) {
            CompletionResult(allMatches[0].drop(prefix.length) + " ", emptyList())
        } else {
            CompletionResult("", allMatches)
        }
    }

    private fun completeFilePath(prefix: String, currentDir: String, command: String): CompletionResult {
        val dirPath: File
        val baseName: String
        if (prefix.startsWith("/")) {
            dirPath = File(prefix).parentFile ?: File("/")
            baseName = File(prefix).name
        } else {
            val parent = if (prefix.contains("/")) File(prefix).parent else null
            dirPath = if (parent != null) File(currentDir, parent) else File(currentDir)
            baseName = File(prefix).name
        }
        return try {
            if (!dirPath.isDirectory) return CompletionResult("", emptyList())
            val matches = dirPath.listFiles()
                ?.filter { it.name.lowercase().startsWith(baseName.lowercase()) }
                ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })
                ?.take(30) ?: emptyList()
            if (matches.isEmpty()) return CompletionResult("", emptyList())
            if (matches.size == 1) {
                val suffix = if (matches[0].isDirectory) "/" else " "
                val rel = if (prefix.startsWith("/")) matches[0].absolutePath
                else matches[0].absolutePath.removePrefix(currentDir + "/")
                CompletionResult(rel.drop(prefix.length) + suffix, emptyList())
            } else {
                CompletionResult("", matches.map { it.name + if (it.isDirectory) "/" else "" })
            }
        } catch (_: Exception) { CompletionResult("", emptyList()) }
    }
}

data class CompletionResult(val insert: String, val candidates: List<String>)
