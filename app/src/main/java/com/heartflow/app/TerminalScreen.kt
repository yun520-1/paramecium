package com.heartflow.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import com.heartflow.data.ToolRegistry
import com.heartflow.data.TermuxBridge

// ==================== 已提取到独立文件 ====================
// CommandCompleter.kt: TerminalLine, MAX_LINES, FOLD_THRESHOLD, MAX_HISTORY, DEFAULT_DIR, CommandCompleter, CompletionResult
// AnsiParser.kt:        AnsiParser
// TerminalTheme.kt:     TerminalTheme

// ==================== 主 Composable ====================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalScreen(onBack: () -> Unit = {}) {
    var command by remember { mutableStateOf("") }
    val terminalLines = remember { mutableStateListOf<TerminalLine>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var isExecuting by remember { mutableStateOf(false) }
    var currentDir by remember { mutableStateOf(DEFAULT_DIR) }
    var currentProcess by remember { mutableStateOf<Process?>(null) }
    val commandHistory = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableIntStateOf(-1) }
    val envVars = remember { mutableStateMapOf("PATH" to "/sbin:/system/bin:/system/xbin:/data/bin") }
    val context = LocalContext.current
    // Termux 集成状态
    val termuxBridge = remember { TermuxBridge(context) }
    var useTermux by remember { mutableStateOf(false) }
    var termuxAvailable by remember { mutableStateOf<Boolean?>(null) }
    var termuxVersion by remember { mutableStateOf("") }
    var showTermuxInfo by remember { mutableStateOf(false) }
    // Tab 补全
    var tabCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var showCandidates by remember { mutableStateOf(false) }
    var lastTabPress by remember { mutableLongStateOf(0L) }
    // 清空确认
    var showClearConfirm by remember { mutableStateOf(false) }
    var skipClearConfirm by remember { mutableStateOf(false) }
    // 危险命令确认
    var showDangerConfirm by remember { mutableStateOf(false) }
    var dangerCmdPending by remember { mutableStateOf("") }
    var skipDangerConfirm by remember { mutableStateOf(false) }
    // 终端配色
    var terminalTheme by remember { mutableStateOf(TerminalTheme.default) }
    val theme = terminalTheme
    // 保存当前目录
    fun persistDir(ctx: Context, dir: String) {
        saveDir(ctx, dir)
    }

    // 持久 shell
    val shellManager = remember { PersistentShellManager() }
    DisposableEffect(Unit) {
        onDispose {
            shellManager.destroy()
            persistDir(context, currentDir)
            saveSession(context, terminalLines)
            saveHistory(context, commandHistory)
        }
    }

    // 初始化
    LaunchedEffect(Unit) {
        loadHistory(context, commandHistory)
        val saved = loadSavedDir(context)
        if (File(saved).isDirectory) currentDir = saved

        // 恢复上次会话
        val savedSession = loadSession(context)
        if (savedSession != null && savedSession.size > 1) {
            terminalLines.addAll(savedSession)
            terminalLines.add(TerminalLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", isHeader = true))
            terminalLines.add(TerminalLine("↻ 恢复上次会话 (${savedSession.size} 行)", isWarning = true))
            terminalLines.add(TerminalLine(""))
            return@LaunchedEffect
        }

        terminalLines.add(TerminalLine("📱 心虫系统终端 v2.2.5", isHeader = true))
        terminalLines.add(TerminalLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", isHeader = true))
        terminalLines.add(TerminalLine("完整 Shell 终端 | 支持管道/重定向/后台/多命令"))
        terminalLines.add(TerminalLine("↑↓ 历史 | Tab 补全 | Ctrl+C 中断 | 长按复制"))
        terminalLines.add(TerminalLine("输入 help 查看详细帮助"))
        terminalLines.add(TerminalLine("当前: $currentDir", isWarning = true))
        terminalLines.add(TerminalLine(""))

        // 检测 Termux 可用性（后台检测，不阻塞初始化）
        scope.launch {
            termuxAvailable = termuxBridge.isInstalled()
            if (termuxAvailable == true) {
                termuxVersion = termuxBridge.getVersion()
                val avail = termuxBridge.isAvailable()
                termuxAvailable = avail
                if (avail) {
                    useTermux = true
                    terminalLines.add(TerminalLine(""))
                    terminalLines.add(TerminalLine("🔬 Termux $termuxVersion 检测可用，已自动启用", isSuccess = true))
                    terminalLines.add(TerminalLine("输入 /termux 切换回系统 sh 环境", isWarning = true))
                    terminalLines.add(TerminalLine(""))
                } else {
                    terminalLines.add(TerminalLine(""))
                    terminalLines.add(TerminalLine("⚠ Termux $termuxVersion 已安装但未启用，请检查 allow-external-apps 设置", isWarning = true))
                    terminalLines.add(TerminalLine(""))
                }
            }
        }
    }

    // 自动滚底
    LaunchedEffect(terminalLines.size) {
        if (terminalLines.isNotEmpty()) {
            kotlinx.coroutines.delay(80)
            listState.animateScrollToItem(terminalLines.lastIndex)
        }
    }

    // 输出渲染（保留 ANSI 码，让 Text 渲染时着色）
    var lastCommandWasCat by remember { mutableStateOf(false) }

    fun isBinaryContent(text: String): Boolean {
        if (text.isEmpty()) return false
        val nonPrintable = text.count { it.code in 1..8 || it.code in 14..31 || it.code == 127 }
        return nonPrintable > text.length * 0.3
    }

    fun renderOutput(output: String, isError: Boolean) {
        val lines = output.split("\n")
        if (lines.size == 1 && lines[0].isBlank()) {
            terminalLines.add(TerminalLine("(空输出)", isWarning = true))
        } else if (lines.size > FOLD_THRESHOLD) {
            terminalLines.add(TerminalLine(
                "⚠ 输出共 ${lines.size} 行（显示前 $FOLD_THRESHOLD 行）",
                isWarning = true
            ))
            terminalLines.addAll(lines.take(FOLD_THRESHOLD).map { TerminalLine(it, isError = isError) })
            terminalLines.add(TerminalLine(
                "... 还有 ${lines.size - FOLD_THRESHOLD} 行 | 已在底部完整输出",
                isLink = true
            ))
        } else {
            lines.forEach { terminalLines.add(TerminalLine(it, isError = isError)) }
        }
        // cat 二进制文件检测
        if (lastCommandWasCat && output.length in 20..50000 && isBinaryContent(output)) {
            terminalLines.add(TerminalLine(
                "⚠ 检测到二进制内容，建议用 file/strings 命令查看",
                isWarning = true
            ))
        }
        lastCommandWasCat = false
    }

    // 执行命令
    fun executeCmd(cmd: String, confirmed: Boolean = false) {
        if (cmd.isBlank() || isExecuting) return

        val trimmed = cmd.trim()
        when {
            trimmed == "clear" || trimmed == "cls" -> {
                terminalLines.clear()
                terminalLines.add(TerminalLine("终端已清空"))
                command = ""
                return
            }
            trimmed == "history" -> {
                terminalLines.add(TerminalLine("命令历史 (${commandHistory.size} 条):", isHeader = true))
                commandHistory.forEachIndexed { i, h ->
                    terminalLines.add(TerminalLine("  ${i + 1}  $h"))
                }
                command = ""
                return
            }
            trimmed == "/termux" -> {
                if (termuxAvailable != true) {
                    terminalLines.add(TerminalLine("❌ Termux 不可用", isError = true))
                } else {
                    useTermux = !useTermux
                    if (useTermux) {
                        terminalLines.add(TerminalLine("🔬 已切换到 Termux 环境 ($termuxVersion)", isSuccess = true))
                    } else {
                        terminalLines.add(TerminalLine("已切换到系统 sh 环境"))
                    }
                }
                command = ""
                return
            }
            (trimmed.startsWith("cd ") || trimmed == "cd") && useTermux -> {
                // Termux 模式下 cd 由本地处理，后续命令通过 workdir 传递
                val target = if (trimmed == "cd") "~" else trimmed.removePrefix("cd ").trim()
                val newDir = if (target.startsWith("/")) target
                             else if (target == "~") "/data/data/com.termux/files/home"
                             else File(currentDir, target).canonicalPath
                if (File(newDir).isDirectory) {
                    currentDir = newDir
                    persistDir(context, currentDir)
                    terminalLines.add(TerminalLine(""))  // 空行提示已切换
                } else {
                    terminalLines.add(TerminalLine("cd: $target: 目录不存在", isError = true))
                }
                command = ""
                return
            }
        }

        terminalLines.add(TerminalLine("${currentDir}$ $cmd", isCommand = true))
        // 标记 cat 命令用于后续二进制检测
        val baseCmd = trimmed.split(" ").firstOrNull() ?: ""
        lastCommandWasCat = (baseCmd == "cat")

        // 危险命令拦截
        val dangerPatterns = listOf(
            Regex("^rm\\s+-[rR]?f?\\s+"),   // rm -rf, rm -r
            Regex("^rmdir\\s+"),             // rmdir
            Regex("^mkfs\\."),               // mkfs.ext4, mkfs.f2fs
            Regex("^dd\\s+if="),             // dd
            Regex("^fdisk\\s+"),             // fdisk
            Regex("^format\\s+"),            // format
            Regex("^mv\\s+/"),               // mv system files
            Regex("^chmod\\s+-?R?\\s+[0-7]{3,4}\\s+/") // chmod system
        )
        val isDanger = dangerPatterns.any { it.matches(trimmed) }
        if (isDanger && !confirmed && !skipDangerConfirm) {
            dangerCmdPending = cmd
            showDangerConfirm = true
            isExecuting = false
            return
        }
        commandHistory.remove(cmd)
        commandHistory.add(cmd)
        if (commandHistory.size > MAX_HISTORY) commandHistory.removeFirst()
        historyIndex = -1
        command = ""
        isExecuting = true
        showCandidates = false

        saveHistory(context, commandHistory)

        val isBg = cmd.trimEnd().endsWith(" &")
        val actualCmd = if (isBg) cmd.trimEnd().dropLast(1).trimEnd() else cmd

        val skillsDir = File(context.filesDir, "skills").absolutePath
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                // 内置命令优先处理（无论 Termux 还是系统 sh 模式）
                val builtInResult = handleBuiltInCommand(actualCmd, envVars.toMap(), skillsDir)
                if (builtInResult != null) return@withContext builtInResult

                if (useTermux && termuxAvailable == true) {
                    // Termux 模式：通过 RUN_COMMAND Intent 执行
                    val tr = termuxBridge.execute(actualCmd, workdir = currentDir)
                    val output = when {
                        tr.stdout.isNotBlank() -> tr.stdout
                        tr.stderr.isNotBlank() -> tr.stderr
                        else -> "(执行完成，退出码: ${tr.exitCode})"
                    }
                    ShellResult(output = output, isError = !tr.success)
                } else {
                    // 延迟启动持久 shell（首次执行命令时）
                    if (!shellManager.isAlive) {
                        shellManager.start(envVars = envVars.toMap(), dir = currentDir)
                    }
                    executeShellCommandEnhanced(
                        actualCmd, currentDir, envVars.toMap(), skillsDir,
                        onProcessStarted = { proc -> currentProcess = proc },
                        shellManager = shellManager
                    )
                }
            }
            val (output, isError, process, newDir) = result

            if (output == "__CLEAR__") {
                terminalLines.clear()
            } else {
                renderOutput(output, isError)
                if (newDir != null) {
                    currentDir = newDir
                    persistDir(context, currentDir)
                }
                if (actualCmd.startsWith("export ")) {
                    val parts = actualCmd.removePrefix("export ").trim().split("=", limit = 2)
                    if (parts.size == 2) {
                        envVars[parts[0]] = parts[1]
                        terminalLines.add(TerminalLine("  -> ${parts[0]}=${parts[1]}", isSuccess = true))
                    }
                }
            }
            while (terminalLines.size > MAX_LINES) terminalLines.removeFirst()
            isExecuting = false
            currentProcess = null
            // 异步保存会话（每 5 次执行保存一次，或关键操作后保存）
            if (terminalLines.size % 5 == 0 || terminalLines.size < 10) {
                saveSession(context, terminalLines)
            }
        }
    }

    // 按键处理
    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        return when (event.key) {
            Key.DirectionUp -> {
                if (commandHistory.isNotEmpty() && historyIndex < commandHistory.size - 1) {
                    historyIndex++
                    command = commandHistory[commandHistory.size - 1 - historyIndex]
                }
                true
            }
            Key.DirectionDown -> {
                if (historyIndex > 0) {
                    historyIndex--
                    command = commandHistory[commandHistory.size - 1 - historyIndex]
                } else {
                    historyIndex = -1; command = ""
                }
                true
            }
            Key.Tab -> {
                if (command.isNotBlank()) {
                    val now = System.currentTimeMillis()
                    if (now - lastTabPress < 500 && tabCandidates.isNotEmpty()) {
                        val words = command.split(" ")
                        val idx = tabCandidates.indexOf(words.last())
                        val next = tabCandidates[(idx + 1) % tabCandidates.size]
                        command = words.dropLast(1).joinToString(" ") +
                            (if (words.size > 1) " " else "") + next
                    } else {
                        val result = CommandCompleter.complete(command, currentDir)
                        if (result.insert.isNotEmpty()) {
                            command += result.insert
                            tabCandidates = emptyList()
                            showCandidates = false
                        } else if (result.candidates.isNotEmpty()) {
                            tabCandidates = result.candidates
                            showCandidates = true
                        }
                    }
                    lastTabPress = now
                }
                true
            }
            Key.C -> {
                // Ctrl+C
                val modifiers = event.nativeKeyEvent?.modifiers ?: 0
                val isCtrl = (modifiers and 0x1000) != 0
                if (isCtrl) {
                    currentProcess?.destroy()
                    currentProcess = null
                    isExecuting = false
                    terminalLines.add(TerminalLine("^C", isError = true))
                    terminalLines.add(TerminalLine("执行已中断", isError = true))
                    command = ""
                }
                false
            }
            Key.L -> {
                val modifiers = event.nativeKeyEvent?.modifiers ?: 0
                val isCtrl = (modifiers and 0x1000) != 0
                if (isCtrl) {
                    if (skipClearConfirm || terminalLines.size <= 3) {
                        terminalLines.clear()
                        terminalLines.add(TerminalLine("终端已清空 (Ctrl+L)"))
                    } else {
                        showClearConfirm = true
                    }
                }
                false
            }
            Key.Enter -> {
                executeCmd(command)
                true
            }
            else -> false
        }
    }

    // ==================== UI ====================

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.bg)
            .onKeyEvent { handleKeyEvent(it) }
    ) {
        // 顶栏
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = theme.surface,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = theme.fg)
                }
                Icon(Icons.Default.Terminal, "终端", tint = theme.prompt)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("系统终端 v2.2.5" + if (useTermux) " · Termux" else " · sh", color = theme.fg, fontWeight = FontWeight.Bold)
                    Text(currentDir, color = theme.fg.copy(alpha = 0.6f), fontSize = 9.sp)
                }
                if (isExecuting) {
                    IconButton(onClick = {
                        currentProcess?.destroy()
                        currentProcess = null
                        isExecuting = false
                        terminalLines.add(TerminalLine("^C 手动中断", isError = true))
                    }) {
                        Icon(Icons.Default.Cancel, "中断", tint = theme.error)
                    }
                }
                if (termuxAvailable != null) {
                    IconButton(onClick = { showTermuxInfo = !showTermuxInfo }) {
                        Icon(
                            Icons.Default.DeveloperBoard,
                            contentDescription = if (useTermux) "Termux 已启用" else "Termux 未启用",
                            tint = if (useTermux) Color(0xFF00C853) else Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                IconButton(onClick = {
                    if (skipClearConfirm || terminalLines.size <= 3) {
                        terminalLines.clear()
                        terminalLines.add(TerminalLine("终端已清空"))
                    } else {
                        showClearConfirm = true
                    }
                }) {
                    Icon(Icons.Default.DeleteSweep, "清空", tint = Color.Gray)
                }
            }
        }

        // 输出区
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(theme.bg)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            itemsIndexed(terminalLines) { index, line ->
                val textColor = when {
                    line.isError -> theme.error
                    line.isCommand -> theme.cmd
                    line.isLink -> theme.link
                    line.isWarning -> theme.warning
                    line.isSuccess -> theme.success
                    line.isHeader -> theme.header
                    else -> theme.fg
                }
                val hasAnsi = line.content.contains("[")
                if (hasAnsi) {
                    Text(
                        text = AnsiParser.toAnnotatedString(line.content, defaultFg = textColor),
                        fontSize = if (line.isHeader) 13.sp else 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (line.isHeader) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .padding(vertical = 0.5.dp)
                            .combinedClickable(
                                onClick = { /* 展开链接等 */ },
                                onLongClick = {
                                    try {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("terminal",
                                            AnsiParser.strip(line.content)))
                                        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                                    } catch (_: Exception) {}
                                }
                            )
                    )
                } else {
                    Text(
                        text = line.content,
                        color = textColor,
                        fontSize = if (line.isHeader) 13.sp else 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (line.isHeader) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .padding(vertical = 0.5.dp)
                            .combinedClickable(
                                onClick = { /* 展开链接等 */ },
                                onLongClick = {
                                    try {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", line.content))
                                        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                                    } catch (_: Exception) {}
                                }
                            )
                    )
                }
            }

            // Tab 候选提示
            if (showCandidates && tabCandidates.isNotEmpty()) {
                item {
                    Text(
                        text = tabCandidates.joinToString("  "),
                        color = theme.warning,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }

        // 输入区
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = theme.surface
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$ ",
                    color = theme.prompt,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = {
                        command = it
                        if (showCandidates) {
                            showCandidates = false
                            tabCandidates = emptyList()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 36.dp, max = 60.dp)
                        .onKeyEvent { handleKeyEvent(it) },
                    textStyle = TextStyle(
                        color = theme.fg,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = theme.fg,
                        unfocusedTextColor = theme.fg,
                        cursorColor = theme.prompt,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    singleLine = true,
                    placeholder = {
                        Text(
                            if (isExecuting) "执行中..." else "输入命令...",
                            color = theme.fg.copy(alpha = 0.5f),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(onSend = { executeCmd(command) })
                )
                Spacer(Modifier.width(6.dp))
                FilledIconButton(
                    onClick = { executeCmd(command) },
                    enabled = !isExecuting,
                    modifier = Modifier.size(38.dp)
                ) {
                    if (isExecuting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = theme.prompt
                        )
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, "执行", tint = theme.fg)
                    }
                }
            }
        }
    }

    // 清空确认对话框
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空终端") },
            text = { Text("确认清空所有终端输出？") },
            confirmButton = {
                TextButton(onClick = {
                    terminalLines.clear()
                    terminalLines.add(TerminalLine("终端已清空"))
                    showClearConfirm = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }

    // 危险命令确认对话框
    if (showDangerConfirm) {
        AlertDialog(
            onDismissRequest = {
                showDangerConfirm = false
                dangerCmdPending = ""
            },
            title = { Text("⚠ 确认执行危险命令", color = MaterialTheme.colorScheme.error) },
            text = {
                Column {
                    Text("以下命令可能会删除或损坏文件：")
                    Spacer(Modifier.height(8.dp))
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(4.dp)) {
                        Text(
                            dangerCmdPending,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = skipDangerConfirm,
                            onCheckedChange = { skipDangerConfirm = it }
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("不再提示", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDangerConfirm = false
                    executeCmd(dangerCmdPending, confirmed = true)
                }) { Text("执行", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDangerConfirm = false
                    dangerCmdPending = ""
                }) { Text("取消") }
            }
        )
    }

    LaunchedEffect(currentDir) { persistDir(context, currentDir) }
}

// ==================== 持久化 ====================

private const val PREFS_NAME = "terminal_prefs"
private const val KEY_CURRENT_DIR = "current_dir"
private const val KEY_HISTORY = "command_history"
private const val KEY_SESSION = "session_lines"
private const val MAX_SAVED_LINES = 150
private const val LINE_SEP = ""
private const val FIELD_SEP = " "

private fun loadSavedDir(context: Context): String {
    return try {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dir = prefs.getString(KEY_CURRENT_DIR, DEFAULT_DIR) ?: DEFAULT_DIR
        if (File(dir).isDirectory) dir else DEFAULT_DIR
    } catch (_: Exception) { DEFAULT_DIR }
}

private fun saveDir(context: Context, dir: String) {
    try {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CURRENT_DIR, dir).apply()
    } catch (_: Exception) {}
}

private fun loadHistory(context: Context, history: MutableList<String>) {
    try {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_HISTORY, "") ?: return
        saved.split("\n").filter { it.isNotBlank() }.forEach { history.add(it) }
    } catch (_: Exception) {}
}

private fun saveHistory(context: Context, history: MutableList<String>) {
    try {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY, history.takeLast(MAX_HISTORY).joinToString("\n"))
            .apply()
    } catch (_: Exception) {}
}

/** 将会话行保存到 SharedPreferences（最近 MAX_SAVED_LINES 行） */
private fun saveSession(context: Context, lines: List<TerminalLine>) {
    try {
        val recent = lines.takeLast(MAX_SAVED_LINES)
        val data = recent.joinToString(LINE_SEP) { line ->
            val flags = buildString {
                if (line.isCommand) append('C')
                if (line.isError) append('E')
                if (line.isLink) append('L')
                if (line.isWarning) append('W')
                if (line.isSuccess) append('S')
                if (line.isHeader) append('H')
            }
            "${line.content}$FIELD_SEP$flags$FIELD_SEP${line.timestamp}"
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SESSION, data).apply()
    } catch (_: Exception) {}
}

/** 从 SharedPreferences 恢复会话行 */
private fun loadSession(context: Context): List<TerminalLine>? {
    return try {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val data = prefs.getString(KEY_SESSION, null) ?: return null
        val lines = data.split(LINE_SEP).mapNotNull { entry ->
            val parts = entry.split(FIELD_SEP, limit = 3)
            if (parts.size < 2) return@mapNotNull null
            val content = parts[0]
            val flags = parts[1]
            val timestamp = if (parts.size >= 3) parts[2].toLongOrNull()
                ?: System.currentTimeMillis() else System.currentTimeMillis()
            TerminalLine(
                content = content,
                isCommand = 'C' in flags,
                isError = 'E' in flags,
                isLink = 'L' in flags,
                isWarning = 'W' in flags,
                isSuccess = 'S' in flags,
                isHeader = 'H' in flags,
                timestamp = timestamp
            )
        }
        lines.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }
}

// ==================== Shell 执行引擎 ====================

data class ShellResult(
    val output: String,
    val isError: Boolean,
    val process: Process? = null,
    val newDir: String? = null
)

private val HELP_TEXT = """
可用命令:
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
系统命令 (标准 shell 命令):
  ls, pwd, cd, cat, echo, cp, mv, rm, mkdir, touch
  grep, find, ps, top, kill, df, du, free, uptime
  date, whoami, id, uname, env, export
  curl, wget, ping, netstat
  tar, gzip, zip, unzip
  sh, busybox
  su, am, pm, settings, cmd (Android 系统工具)
  bash, git, python, node (需额外安装)

  支持管道 | 重定向 > >>  多命令 && || ;  后台 &

心虫增强命令:
  help       - 显示此帮助
  clear      - 清屏
  time       - 当前日期时间
  info       - 系统信息
  fetch <url> - 获取网页内容
  search <q>  - 搜索网络
  github <q>  - 搜索 GitHub
  install <url> - 安装技能
  skills     - 列出已安装技能
  /termux    - 切换 Termux/系统 sh 环境（需安装 Termux）
  history    - 命令历史
  which <cmd> - 查找命令路径

操作指南:
  ↑↓         - 浏览历史
  Tab        - 命令/文件补全
  Ctrl+C     - 中断执行
  Ctrl+L     - 清屏
  长按输出   - 复制文本
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
"""

private fun handleBuiltInCommand(
    command: String,
    envVars: Map<String, String>,
    skillsDir: String = ""
): ShellResult? {
    return when {
        command == "help" -> ShellResult(HELP_TEXT, false)
        command == "time" -> ShellResult(
            "当前时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}", false)
        command == "info" -> ShellResult(buildSystemInfo(), false)
        command.startsWith("fetch ") -> ShellResult(
            ToolRegistry.webFetch(command.removePrefix("fetch ").trim()), false)
        command.startsWith("search ") -> ShellResult(
            ToolRegistry.webSearch(command.removePrefix("search ").trim()), false)
        command.startsWith("github ") -> ShellResult(
            ToolRegistry.gitHubSearch(command.removePrefix("github ").trim()), false)
        command.startsWith("install ") -> ShellResult(
            ToolRegistry.installSkill(command.removePrefix("install ").trim()), false)
        command == "skills" -> ShellResult(ToolRegistry.listInstalledSkills(), false)
        command.startsWith("which ") -> {
            val cmd = command.removePrefix("which ").trim()
            ShellResult(findCommand(cmd, envVars), false)
        }
        else -> null
    }
}

private fun executeShellCommandEnhanced(
    command: String,
    currentDir: String,
    envVars: Map<String, String>,
    skillsDir: String = "",
    onProcessStarted: ((Process) -> Unit)? = null,
    shellManager: PersistentShellManager? = null
): ShellResult {
    return try {
            // 内置命令优先处理
            val builtInResult = handleBuiltInCommand(command, envVars, skillsDir)
            if (builtInResult != null) return builtInResult

        // 危险命令黑名单
        val dangerousPatterns = listOf(
            "rm -rf /", "rm -rf /*", "mkfs", "dd if=", ":(){ :|:& };:",
            "chmod -R 777 /", "chown -R", "> /dev/sda",
            "wget.*|sh", "curl.*|sh", "wget.*|bash", "curl.*|bash"
        )
        val cmdLower = command.lowercase()
        val isDangerous = dangerousPatterns.any { cmdLower.contains(it) }
        if (isDangerous) {
            return ShellResult("❌ 危险命令已被阻止: $command", true)
        }

        if (shellManager != null && shellManager.isAlive) {
            // 使用持久 shell 执行
            onProcessStarted?.invoke(shellManager.process!!)
            val result = shellManager.execute(command)

            if (command.startsWith("cd ")) {
                // cd 后查询实际工作目录
                val newDir = shellManager.queryPwd()
                return ShellResult(result.output, result.isError, result.process, newDir)
            }

            if (command.startsWith("export ") && command.contains("=")) {
                // export 后同步 envVars
                return ShellResult(result.output, result.isError)
            }

            val finalResult = result.output.let { out ->
                when {
                    out.isEmpty() -> "(执行完成)"
                    out.length > 100_000 -> out.take(100_000) + "\n\n...(输出过长，已截断)"
                    else -> out
                }
            }
            return ShellResult(finalResult, result.isError, result.process)
        }

        // 无持久 shell 时的回退：单次 ProcessBuilder
        val env = ProcessBuilder().environment() ?: mutableMapOf()
        env.putAll(envVars)

        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(File(currentDir))
            .redirectErrorStream(true)
            .start()
        onProcessStarted?.invoke(process)

        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = StringBuilder()
        var line: String?

        while (reader.readLine().also { line = it } != null) {
            output.appendLine(line)
            if (output.length > 500_000) {
                output.append("...(输出过长，已截断)")
                process.destroy()
                break
            }
        }

        process.waitFor()
        val result = output.toString().trim()

        when {
            result.isEmpty() -> ShellResult("(执行完成，退出码: ${process.exitValue()})", false, process)
            result.length > 100_000 -> ShellResult(
                result.take(100_000) + "\n\n...(输出过长，已截断)", false, process)
            else -> ShellResult(result, process.exitValue() != 0, process)
        }
    } catch (e: Exception) {
        ShellResult("执行错误: ${e.message}", true)
    }
}

private fun findCommand(cmd: String, envVars: Map<String, String>): String {
    val path = envVars["PATH"] ?: "/sbin:/system/bin:/system/xbin"
    for (dir in path.split(":")) {
        val f = File("$dir/$cmd")
        if (f.exists() && f.canExecute()) return "$f"
    }
    return "$cmd: 未找到"
}

private fun buildSystemInfo(): String {
    val runtime = Runtime.getRuntime()
    return buildString {
        appendLine("📱 系统信息:")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Java: ${System.getProperty("java.version")}")
        appendLine("内存: ${runtime.maxMemory() / 1024 / 1024}MB")
        appendLine("CPU: ${runtime.availableProcessors()} 核")
        appendLine("用户: ${System.getProperty("user.name")}")
        appendLine("PATH: ${System.getenv("PATH")?.take(80)}")
    }
}

// ==================== 持久 Shell 进程管理器 ====================

/**
 * 管理长期存活的 /system/bin/sh 进程，使 cd/export/env 状态跨命令保持。
 * 使用自定义 echo 标记作为输出结束分隔符。
 */
class PersistentShellManager {
    companion object {
        private const val DONE_MARKER = "---HF_DONE---"
    }

    var process: Process? = null
        private set

    val isAlive: Boolean get() = process?.isAlive == true

    /** 启动持久 shell 进程 */
    fun start(envVars: Map<String, String> = emptyMap(), dir: String = "/"): Process {
        destroy()
        val pb = ProcessBuilder("/system/bin/sh")
        pb.redirectErrorStream(true)
        pb.directory(File(dir))
        val env = pb.environment() ?: mutableMapOf()
        env.putAll(envVars)
        val proc = pb.start()
        // 消费启动时的初始输出
        proc.outputStream.flush()
        process = proc
        return proc
    }

    /** 在持久 shell 中执行命令并返回输出 */
    fun execute(command: String, timeoutMs: Long = 30000): ShellResult {
        val proc = process?.takeIf { it.isAlive }
            ?: return ShellResult("错误: shell 进程不可用，请重启终端", true)

        try {
            val writer = proc.outputStream
            writer.write("$command\n".toByteArray(Charsets.UTF_8))
            writer.write("echo '$DONE_MARKER'\n".toByteArray(Charsets.UTF_8))
            writer.flush()
        } catch (e: Exception) {
            process = null
            return ShellResult("写入 shell 错误: ${e.message}", true)
        }

        val output = StringBuilder()
        val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
        val deadline = System.currentTimeMillis() + timeoutMs
        var hasError = false

        try {
            while (System.currentTimeMillis() < deadline) {
                if (reader.ready()) {
                    val line = reader.readLine() ?: break
                    if (line == DONE_MARKER) {
                        val result = output.toString().trimEnd('\n', '\r')
                        return ShellResult(result, hasError, proc)
                    }
                    output.appendLine(line)
                } else {
                    Thread.sleep(20)
                }
            }
        } catch (e: Exception) {
            return ShellResult("读取 shell 输出错误: ${e.message}", true)
        }

        // 超时 — 杀死并重启
        proc.destroy()
        process = null
        return ShellResult("(命令执行超时 ${timeoutMs}ms，shell 已重置)", true)
    }

    /** 发送 pwd 查询当前工作目录 */
    fun queryPwd(): String {
        val result = execute("pwd", timeoutMs = 2000)
        return result.output.lineSequence().lastOrNull()
            ?.takeUnless { it.startsWith("错误") || it.startsWith("读取") }
            ?: "/"
    }

    /** 清理进程资源 */
    fun destroy() {
        process?.destroy()
        process = null
    }
}
