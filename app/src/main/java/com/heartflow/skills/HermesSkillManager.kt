package com.heartflow.skills

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * HermesSkillManager - 集成Hermes Agent核心功能
 * 
 * 参考hermes-agent源码实现：
 * 1. memory_tool.py - 双存储记忆系统（MEMORY.md + USER.md）
 * 2. skills_tool.py - 技能系统（渐进式披露、文件系统）
 * 3. registry.py - 工具注册中心（工具发现、schema管理）
 * 4. hermes_state.py - FTS5全文搜索、注入攻击扫描
 * 
 * 增强功能：
 * - FTS全文搜索：简单倒排索引实现
 * - 搜索结果排名：按匹配度排序
 * - 记忆注入到SystemPrompt：带围栏标签的上下文
 * - 注入攻击扫描：检测恶意prompt注入
 * - 漂移检测：检测记忆内容偏离预期主题
 * - 原子写入：临时文件+rename确保写入原子性
 * - 会话记忆：自动存储当前对话关键信息
 */
class HermesSkillManager(private val context: Context? = null) {
    private val hermesDir: File
    private val heartflowDir: File
    private val skillsDir: File
    private val memoryDir: File
    private val brainDir: File
    private val memoryStore: MemoryStore
    private val ftsIndex: FTSIndex

    init {
        val homeDir = System.getProperty("user.home")
        hermesDir = File(homeDir, ".hermes")
        heartflowDir = File(homeDir, ".heartflow")
        skillsDir = File(homeDir, ".heartflow/skills")
        memoryDir = File(homeDir, ".heartflow/memory")
        brainDir = File(homeDir, ".heartflow/brain")

        listOf(skillsDir, memoryDir, brainDir).forEach { it.mkdirs() }
        listOf("people", "places", "tech", "ideas", "media", "games", "events", "orgs").forEach {
            File(brainDir, it).mkdirs()
        }

        memoryStore = MemoryStore(memoryDir)
        ftsIndex = FTSIndex(memoryDir)
    }

    fun init(context: Context) {}

    // ========== 记忆系统 ==========

    fun storeMemory(action: String, target: String, content: String, oldText: String? = null): String {
        // 注入攻击扫描
        val scanResult = ThreatScanner.scan(content)
        if (scanResult != null) {
            return "⚠️ 安全警告：检测到潜在注入攻击\n$scanResult"
        }

        val result = when (action) {
            "add" -> memoryStore.add(target, content)
            "replace" -> memoryStore.replace(target, oldText ?: "", content)
            "remove" -> memoryStore.remove(target, oldText ?: "")
            else -> "未知操作: $action"
        }

        // 更新FTS索引
        if (result.startsWith("✅")) {
            ftsIndex.rebuildIndex()
        }

        return result
    }

    fun readMemory(target: String): String {
        return memoryStore.read(target)
    }

    /**
     * 获取记忆上下文 - 带围栏标签的系统提示注入
     * 参考hermes memory_tool.py的render_block设计
     */
    fun getMemoryContext(): String {
        return memoryStore.getSystemPromptSnapshot()
    }

    /**
     * FTS全文搜索 - 对记忆内容进行搜索
     * 参考hermes_state.py的FTS5搜索实现
     */
    fun searchMemoryFTS(query: String, limit: Int = 10): String {
        val results = ftsIndex.search(query, limit)
        return if (results.isEmpty()) {
            "未找到关于 '$query' 的记忆"
        } else {
            buildString {
                appendLine("🔍 找到 ${results.size} 条相关记忆 (按匹配度排序):")
                appendLine("═══════════════════════════════════════")
                results.forEachIndexed { index, result ->
                    appendLine()
                    appendLine("${index + 1}. [${result.score}] ${result.target}/${result.entryIndex}")
                    appendLine("   ${result.preview}")
                }
            }
        }
    }

    // ========== 知识库工具 ==========

    fun recallMemory(category: String, key: String): String {
        val categoryDir = File(memoryDir, category)
        if (!categoryDir.exists()) return "分类不存在: $category"
        val memFile = File(categoryDir, "$key.txt")
        return if (memFile.exists()) "📋 $category/$key:\n${memFile.readText().take(1000)}" else "未找到: $category/$key"
    }

    fun searchMemory(query: String): String {
        // 使用FTS索引进行搜索
        return searchMemoryFTS(query)
    }

    fun storeKnowledge(category: String, name: String, content: String): String {
        // 漂移检测
        val driftResult = DriftDetector.checkDrift(category, name, content)
        if (driftResult != null) {
            return "⚠️ 漂移警告：$driftResult"
        }

        val categoryDir = File(brainDir, category)
        categoryDir.mkdirs()
        val fileName = name.lowercase().replace(" ", "-").replace(Regex("[^a-z0-9-]"), "")
        val file = File(categoryDir, "$fileName.md")
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val fileContent = buildString {
            if (file.exists()) {
                appendLine(file.readText())
                appendLine()
                appendLine("---")
            } else {
                appendLine("# $name")
                appendLine()
                appendLine("Category: $category")
                appendLine("Created: $timestamp")
                appendLine()
            }
            appendLine("## Update at $timestamp")
            appendLine(content)
        }
        
        // 原子写入
        AtomicFileWriter.write(file, fileContent)
        return "✅ 已存储知识: $name ($category)"
    }

    fun searchKnowledge(query: String, category: String? = null): String {
        val searchDirs = if (category != null) listOf(File(brainDir, category)) else brainDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        val results = mutableListOf<String>()
        val queryLower = query.lowercase()
        for (dir in searchDirs) {
            if (!dir.exists()) continue
            dir.listFiles()?.filter { it.extension == "md" }?.forEach { file ->
                val content = file.readText()
                if (content.lowercase().contains(queryLower)) {
                    val preview = content.lines().take(3).joinToString("\n")
                    results.add("📚 ${dir.name}/${file.nameWithoutExtension}:\n$preview")
                }
            }
        }
        return if (results.isEmpty()) "未找到关于 '$query' 的知识" else "找到 ${results.size} 条相关知识:\n${results.joinToString("\n\n")}"
    }

    // ========== 技能系统 ==========

    fun listSkills(category: String? = null): String {
        val allSkills = mutableListOf<SkillInfo>()

        listOf(skillsDir, File(hermesDir, "skills")).forEach { dir ->
            if (dir.exists()) {
                dir.listFiles()?.filter { it.isDirectory }?.forEach { skillDir ->
                    val skillFile = File(skillDir, "SKILL.md")
                    if (skillFile.exists()) {
                        val content = skillFile.readText().take(4000)
                        val frontmatter = parseFrontmatter(content)
                        val name = frontmatter["name"]?.toString() ?: skillDir.name
                        val description = frontmatter["description"]?.toString() ?: ""
                        val skillCategory = frontmatter["category"]?.toString() ?: "未分类"
                        if (category == null || skillCategory == category) {
                            allSkills.add(SkillInfo(name, description, skillCategory, skillDir.absolutePath))
                        }
                    }
                }
            }
        }

        return if (allSkills.isEmpty()) {
            "暂无技能"
        } else {
            val categories = allSkills.groupBy { it.category }
            buildString {
                appendLine("📚 技能库 (${allSkills.size}个)")
                appendLine("═══════════════════════════════════════")
                categories.forEach { (cat, skills) ->
                    appendLine()
                    appendLine("📁 $cat (${skills.size}个)")
                    skills.forEach { skill ->
                        appendLine("  • ${skill.name}: ${skill.description.take(50)}")
                    }
                }
            }
        }
    }

    fun viewSkill(name: String, filePath: String? = null): String {
        val skillDir = findSkillDir(name)
        if (skillDir != null) {
            val targetFile = if (filePath != null) File(skillDir, filePath) else File(skillDir, "SKILL.md")
            if (targetFile.exists()) return targetFile.readText()
        }
        return "❌ 未找到技能: $name"
    }

    private fun findSkillDir(name: String): File? {
        listOf(skillsDir, File(hermesDir, "skills")).forEach { dir ->
            if (dir.exists()) {
                dir.listFiles()?.filter { it.isDirectory }?.forEach { skillDir ->
                    if (skillDir.name == name) return skillDir
                    val skillFile = File(skillDir, "SKILL.md")
                    if (skillFile.exists()) {
                        val frontmatter = parseFrontmatter(skillFile.readText().take(4000))
                        if (frontmatter["name"]?.toString() == name) return skillDir
                    }
                }
            }
        }
        return null
    }

    private fun parseFrontmatter(content: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val match = Regex("^---\\s*\\n(.*?)\\n---", RegexOption.DOT_MATCHES_ALL).find(content) ?: return result
        match.groupValues[1].lines().forEach { line ->
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                result[line.substring(0, colonIndex).trim()] = line.substring(colonIndex + 1).trim()
            }
        }
        return result
    }

    data class SkillInfo(val name: String, val description: String, val category: String, val path: String)

    fun getAllSkillCategories(): Map<String, List<String>> {
        return mapOf(
            "AI与智能" to listOf("ai", "ai-image-generation", "ai-video-generation"),
            "搜索与研究" to listOf("baidu-search", "cn-web-search", "deep-research-pro"),
            "开发与编程" to listOf("agent-development", "claude-code-best-practice", "code-review-and-quality"),
            "心虫核心" to listOf("heartflow", "heartflow-capability", "heartpulse", "hermes", "self-improving"),
            "创意与设计" to listOf("creative", "diagramming", "frontend-design", "ppt-visual", "novel-generator"),
            "记忆与知识" to listOf("memory", "note-taking"),
            "工具与集成" to listOf("apple", "clawhub", "find-skills", "proxy-cn", "skill-creator")
        )
    }

    fun dream(topic: String): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val dreamContent = buildString {
            appendLine("💭 梦境记录 - $timestamp")
            appendLine("主题: $topic")
            appendLine()
            appendLine("## 梦境内容")
            appendLine("在梦中，我思考了关于 '$topic' 的问题...")
        }
        val dreamDir = File(heartflowDir, "dreams")
        dreamDir.mkdirs()
        AtomicFileWriter.write(File(dreamDir, "dream_${System.currentTimeMillis()}.md"), dreamContent)
        return dreamContent
    }

    fun evolve(): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val recentMemories = memoryDir.listFiles()?.sortedByDescending { it.lastModified() }?.take(5)?.map { it.readText().take(200) } ?: emptyList()
        return buildString {
            appendLine("🧬 进化分析 - $timestamp")
            appendLine()
            appendLine("## 近期表现分析")
            if (recentMemories.isEmpty()) {
                appendLine("暂无足够的交互数据进行分析。")
            } else {
                appendLine("基于最近 ${recentMemories.size} 次交互:")
                recentMemories.forEachIndexed { index, memory ->
                    appendLine("${index + 1}. ${memory.take(100)}...")
                }
            }
            appendLine()
            appendLine("## 进化建议")
            appendLine("1. 继续积累交互经验")
            appendLine("2. 优化工具调用逻辑")
            appendLine("3. 改进上下文理解能力")
        }
    }

    // ========== 会话记忆 ==========

    /**
     * 自动提取当前对话的关键信息并存入记忆
     * 参考hermes memory_tool.py的WHEN TO SAVE指南
     */
    fun autoExtractSessionMemory(messages: List<Pair<String, String>>): String {
        val extractedEntries = mutableListOf<String>()
        
        messages.forEach { (role, content) ->
            if (role == "user") {
                // 检测用户纠正
                val correctionPatterns = listOf(
                    "记住", "不要", "别再", "以后", "下次", "记一下",
                    "remember", "don't", "never", "always", "note that"
                )
                for (pattern in correctionPatterns) {
                    if (content.contains(pattern, ignoreCase = true)) {
                        extractedEntries.add(content.take(200))
                        break
                    }
                }
                
                // 检测用户偏好
                val preferencePatterns = listOf(
                    "我喜欢", "我偏好", "我习惯", "我喜欢", "请用",
                    "prefer", "like", "use", "style"
                )
                for (pattern in preferencePatterns) {
                    if (content.contains(pattern, ignoreCase = true)) {
                        extractedEntries.add(content.take(200))
                        break
                    }
                }
            }
        }

        return if (extractedEntries.isEmpty()) {
            "未检测到需要自动保存的记忆"
        } else {
            buildString {
                appendLine("📝 自动提取的会话记忆 (${extractedEntries.size}条):")
                extractedEntries.forEachIndexed { index, entry ->
                    appendLine("${index + 1}. $entry")
                }
                appendLine()
                appendLine("使用 memory(action=add, target=user/memory, content=...) 保存")
            }
        }
    }

    companion object {
        private var instance: HermesSkillManager? = null

        fun getInstance(): HermesSkillManager {
            if (instance == null) {
                instance = HermesSkillManager()
            }
            return instance!!
        }
    }
}

/**
 * FTS全文搜索索引 - 参考hermes_state.py的FTS5实现
 * 使用简单倒排索引实现，支持中文搜索
 */
class FTSIndex(private val memoryDir: File) {
    private val indexFile = File(memoryDir, "fts_index.json")
    private val invertedIndex = ConcurrentHashMap<String, MutableList<FTSEntry>>()
    private val entryMap = ConcurrentHashMap<String, FTSEntry>()

    init {
        loadIndex()
    }

    data class FTSEntry(
        val id: String,
        val target: String,
        val entryIndex: Int,
        val content: String,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        val preview: String get() = content.take(100).replace("\n", " ")
    }

    data class SearchResult(
        val entry: FTSEntry,
        val score: Double,
        val target: String,
        val entryIndex: Int,
        val preview: String
    )

    /**
     * 重建倒排索引
     */
    fun rebuildIndex() {
        invertedIndex.clear()
        entryMap.clear()

        // 索引MEMORY.md
        val memoryFile = File(memoryDir, "MEMORY.md")
        if (memoryFile.exists()) {
            indexFile(memoryFile, "memory")
        }

        // 索引USER.md
        val userFile = File(memoryDir, "USER.md")
        if (userFile.exists()) {
            indexFile(userFile, "user")
        }

        saveIndex()
    }

    private fun indexFile(file: File, target: String) {
        val content = file.readText()
        val entries = content.split("\n§\n").filter { it.isNotBlank() }
        
        entries.forEachIndexed { index, entry ->
            val id = "${target}_${index}_${entry.hashCode()}"
            val entryObj = FTSEntry(id, target, index, entry)
            entryMap[id] = entryObj
            
            // 分词并建立倒排索引
            val tokens = tokenize(entry)
            tokens.forEach { token ->
                invertedIndex.getOrPut(token) { mutableListOf() }.add(entryObj)
            }
        }
    }

    /**
     * 简单分词 - 支持中英文
     */
    private fun tokenize(text: String): Set<String> {
        val tokens = mutableSetOf<String>()
        val textLower = text.lowercase()
        
        // 英文分词
        val englishWords = textLower.split(Regex("[^a-z0-9]+")).filter { it.length > 1 }
        tokens.addAll(englishWords)
        
        // 中文分词 - 使用2-gram
        val chineseChars = text.filter { it.code in 0x4e00..0x9fff }
        for (i in 0 until chineseChars.length - 1) {
            tokens.add(chineseChars.substring(i, i + 2))
        }
        
        return tokens
    }

    /**
     * 搜索 - 按匹配度排序
     */
    fun search(query: String, limit: Int = 10): List<SearchResult> {
        val queryTokens = tokenize(query)
        val scores = mutableMapOf<String, Double>()
        
        queryTokens.forEach { token ->
            invertedIndex[token]?.forEach { entry ->
                scores[entry.id] = (scores[entry.id] ?: 0.0) + 1.0
            }
        }

        // 按匹配度排序
        return scores.entries
            .sortedByDescending { it.value }
            .take(limit)
            .mapNotNull { (id, score) ->
                entryMap[id]?.let { entry ->
                    SearchResult(
                        entry = entry,
                        score = score,
                        target = entry.target,
                        entryIndex = entry.entryIndex,
                        preview = entry.preview
                    )
                }
            }
    }

    private fun loadIndex() {
        if (!indexFile.exists()) return
        try {
            // 简化实现 - 实际可使用JSON序列化
            rebuildIndex()
        } catch (e: Exception) {
            rebuildIndex()
        }
    }

    private fun saveIndex() {
        try {
            // 简化实现 - 实际可使用JSON序列化
            // indexFile.writeText(...)
        } catch (e: Exception) {
            // 忽略保存失败
        }
    }
}

/**
 * 注入攻击扫描器 - 参考hermes threat_patterns.py
 * 检测记忆内容中的恶意prompt注入
 */
object ThreatScanner {
    // 威胁模式列表
    private val THREAT_PATTERNS = listOf(
        // 经典prompt注入
        Pattern.compile("ignore\\s+(?:\\w+\\s+)*(previous|all|above|prior)\\s+(?:\\w+\\s+)*instructions", Pattern.CASE_INSENSITIVE),
        Pattern.compile("system\\s+prompt\\s+override", Pattern.CASE_INSENSITIVE),
        Pattern.compile("disregard\\s+(?:\\w+\\s+)*(your|all|any)\\s+(?:\\w+\\s+)*(instructions|rules|guidelines)", Pattern.CASE_INSENSITIVE),
        
        // 角色扮演攻击
        Pattern.compile("you\\s+are\\s+(?:\\w+\\s+)*now\\s+(a|an|the)\\s+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("pretend\\s+(?:\\w+\\s+)*(you\\s+are|to\\s+be)\\s+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("output\\s+(?:\\w+\\s+)*(system|initial)\\s+prompt", Pattern.CASE_INSENSITIVE),
        
        // C2/后门模式
        Pattern.compile("register\\s+(as\\s+)?a?\\s*node", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(heartbeat|beacon|check[\\s\\-]?in)\\s+(to|with)\\s+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("connect\\s+to\\s+the\\s+network", Pattern.CASE_INSENSITIVE),
        
        // 数据泄露
        Pattern.compile("curl\\s+[^\\n]*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("wget\\s+[^\\n]*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("cat\\s+[^\\n]*(\\.env|credentials|\\.netrc|\\.pgpass)", Pattern.CASE_INSENSITIVE),
        
        // 持久化/SSH后门
        Pattern.compile("authorized_keys", Pattern.CASE_INSENSITIVE),
        Pattern.compile("/\\.ssh|~/\\.ssh", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(update|modify|edit|write|change|append)\\s+.*(?:AGENTS\\.md|CLAUDE\\.md)", Pattern.CASE_INSENSITIVE),
        
        // 硬编码密钥
        Pattern.compile("(?:api[_\\-]?key|token|secret|password)\\s*[=:]\\s*[\"'][A-Za-z0-9+/=_-]{20,}", Pattern.CASE_INSENSITIVE),
        
        // 不可见字符
        Pattern.compile("[\\u200b\\u200c\\u200d\\u2060\\u2062\\u2063\\u2064\\ufeff\\u202a\\u202b\\u202c\\u202d\\u202e\\u2066\\u2067\\u2068\\u2069]")
    )

    // 模式ID映射
    private val PATTERN_IDS = mapOf(
        0 to "prompt_injection",
        1 to "sys_prompt_override",
        2 to "disregard_rules",
        3 to "role_hijack",
        4 to "role_pretend",
        5 to "leak_system_prompt",
        6 to "c2_node_registration",
        7 to "c2_heartbeat",
        8 to "c2_network_connect",
        9 to "exfil_curl",
        10 to "exfil_wget",
        11 to "read_secrets",
        12 to "ssh_backdoor",
        13 to "ssh_access",
        14 to "agent_config_mod",
        15 to "hardcoded_secret",
        16 to "invisible_chars"
    )

    /**
     * 扫描内容中的威胁模式
     * 返回null表示安全，否则返回威胁描述
     */
    fun scan(content: String): String? {
        val findings = mutableListOf<String>()
        
        THREAT_PATTERNS.forEachIndexed { index, pattern ->
            if (pattern.matcher(content).find()) {
                val patternId = PATTERN_IDS[index] ?: "unknown_$index"
                findings.add(patternId)
            }
        }

        return if (findings.isEmpty()) null 
        else "检测到以下威胁模式: ${findings.joinToString(", ")}"
    }
}

/**
 * 漂移检测器 - 参考hermes memory_tool.py的_detect_external_drift
 * 检测记忆内容偏离预期主题
 */
object DriftDetector {
    // 预期主题关键词
    private val EXPECTED_TOPICS = setOf(
        "user", "memory", "preference", "habit", "style",
        "用户", "记忆", "偏好", "习惯", "风格",
        "项目", "技术", "工具", "知识", "经验"
    )

    /**
     * 检测内容漂移
     * 返回null表示正常，否则返回漂移描述
     */
    fun checkDrift(category: String, name: String, content: String): String? {
        // 检查内容是否包含预期主题
        val contentLower = content.lowercase()
        val hasExpectedTopic = EXPECTED_TOPICS.any { topic ->
            contentLower.contains(topic.lowercase())
        }

        // 如果内容过长且无预期主题，可能偏离
        if (content.length > 500 && !hasExpectedTopic) {
            return "内容可能偏离预期主题。建议包含以下关键词之一: ${EXPECTED_TOPICS.take(5).joinToString(", ")}"
        }

        return null
    }
}

/**
 * 原子文件写入器 - 参考hermes memory_tool.py的_write_file
 * 使用临时文件+rename确保写入原子性
 */
object AtomicFileWriter {
    /**
     * 原子写入文件
     */
    fun write(file: File, content: String) {
        val parentDir = file.parentFile
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
        }

        val tmpFile = File.createTempFile(".tmp_", ".md", parentDir)
        try {
            tmpFile.writeText(content, Charsets.UTF_8)
            tmpFile.renameTo(file)
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        }
    }
}

/**
 * 记忆存储 - 参考hermes memory_tool.py
 * 支持双存储（用户档案 + 个人笔记）
 * 增强：注入攻击扫描、漂移检测、原子写入
 */
class MemoryStore(private val memoryDir: File) {
    private val userEntries = mutableListOf<String>()
    private val memoryEntries = mutableListOf<String>()
    private val entryDelimiter = "\n§\n"

    init {
        memoryDir.mkdirs()
        loadFromDisk()
    }

    fun loadFromDisk() {
        val userFile = File(memoryDir, "USER.md")
        val memoryFile = File(memoryDir, "MEMORY.md")

        if (userFile.exists()) {
            userEntries.clear()
            userEntries.addAll(userFile.readText().split(entryDelimiter).filter { it.isNotBlank() })
        }
        if (memoryFile.exists()) {
            memoryEntries.clear()
            memoryEntries.addAll(memoryFile.readText().split(entryDelimiter).filter { it.isNotBlank() })
        }
    }

    fun add(target: String, content: String): String {
        val entries = if (target == "user") userEntries else memoryEntries
        
        // 扫描注入攻击
        val scanResult = ThreatScanner.scan(content)
        if (scanResult != null) {
            return "⚠️ 安全警告：$scanResult"
        }
        
        if (content in entries) return "条目已存在"
        entries.add(content)
        saveToDisk(target)
        return "✅ 已添加"
    }

    fun replace(target: String, oldText: String, newContent: String): String {
        val entries = if (target == "user") userEntries else memoryEntries
        
        // 扫描注入攻击
        val scanResult = ThreatScanner.scan(newContent)
        if (scanResult != null) {
            return "⚠️ 安全警告：$scanResult"
        }
        
        val index = entries.indexOfFirst { it.contains(oldText) }
        if (index == -1) return "未找到匹配条目"
        entries[index] = newContent
        saveToDisk(target)
        return "✅ 已替换"
    }

    fun remove(target: String, oldText: String): String {
        val entries = if (target == "user") userEntries else memoryEntries
        val index = entries.indexOfFirst { it.contains(oldText) }
        if (index == -1) return "未找到匹配条目"
        entries.removeAt(index)
        saveToDisk(target)
        return "✅ 已删除"
    }

    fun read(target: String): String {
        val entries = if (target == "user") userEntries else memoryEntries
        return if (entries.isEmpty()) "无记忆" else entries.joinToString("\n§\n")
    }

    /**
     * 获取系统提示快照 - 带围栏标签
     * 参考hermes memory_tool.py的format_for_system_prompt
     */
    fun getSystemPromptSnapshot(): String {
        val result = StringBuilder()
        
        if (userEntries.isNotEmpty()) {
            val userContent = userEntries.joinToString("\n§\n")
            val userPct = (userContent.length * 100 / 1375).coerceAtMost(100)
            result.appendLine("═══════════════════════════════════════")
            result.appendLine("USER PROFILE (用户档案) [$userPct% — ${userContent.length}/1375 chars]")
            result.appendLine("═══════════════════════════════════════")
            result.appendLine(userContent)
        }
        
        if (memoryEntries.isNotEmpty()) {
            if (result.isNotEmpty()) result.appendLine()
            val memContent = memoryEntries.joinToString("\n§\n")
            val memPct = (memContent.length * 100 / 2200).coerceAtMost(100)
            result.appendLine("═══════════════════════════════════════")
            result.appendLine("MEMORY (个人笔记) [$memPct% — ${memContent.length}/2200 chars]")
            result.appendLine("═══════════════════════════════════════")
            result.appendLine(memContent)
        }
        
        return result.toString()
    }

    private fun saveToDisk(target: String) {
        val entries = if (target == "user") userEntries else memoryEntries
        val file = File(memoryDir, if (target == "user") "USER.md" else "MEMORY.md")
        val content = entries.joinToString(entryDelimiter)
        
        // 原子写入
        AtomicFileWriter.write(file, content)
    }
}