package com.heartflow.data

import android.content.Context
import android.util.Log
import com.heartflow.engine.ToolCategory
import com.heartflow.engine.ToolDefinition
import com.heartflow.engine.PermissionLevel
import com.heartflow.skills.HermesSkillManager
import com.heartflow.skills.baiduAISearch
import com.heartflow.skills.apkAudit
import com.heartflow.skills.hermesSkillIntegrator
import com.heartflow.web.WebViewEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

data class AgentTool(
    val name: String,
    val description: String,
    val category: String = "通用",
    val parameters: List<ToolParameter>,
    val execute: (Map<String, Any>) -> String
)

data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true
)

object ToolRegistry {
    private const val TAG = "ToolRegistry"
    private val tools = mutableListOf<AgentTool>()
    private val dynamicTools = mutableListOf<AgentTool>() // 动态注册的技能工具
    private val hermesManager = HermesSkillManager.getInstance()

    private lateinit var appContext: Context
    private val searchCache = SearchCache()

    fun init(context: Context) {
        appContext = context
        hermesManager.init(context)
        copyBundledSkills()    // 复制预装技能到技能目录
        refreshDynamicSkills() // 注册已安装的技能文件
    }

    /** 从 assets/skills/ 复制预装技能到技能目录 */
    private fun copyBundledSkills() {
        try {
            val assetDir = "skills"
            val files = try { appContext.assets.list(assetDir) } catch (e: Exception) { Log.w(TAG, "copyBundledSkills: assets.list failed", e); null } ?: return
            skillsDir.mkdirs()
            files.filter { it.endsWith(".skill") }.forEach { fileName ->
                val target = File(skillsDir, fileName)
                if (!target.exists()) {
                    appContext.assets.open("$assetDir/$fileName").use { input ->
                        target.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "copyBundledSkills: failed", e)
        }
    }

    private val skillsDir: File
        get() = File(appContext.filesDir, "skills")

    init {
        // ========== 浏览器工具 ==========
        register(AgentTool("open_browser", "在内置浏览器中打开网页", "浏览器", listOf(
            ToolParameter("url", "string", "网址或搜索词（自动识别）")
        )) { params ->
            val url = params["url"]?.toString() ?: ""
            if (url.isBlank()) "错误: 缺少URL" else "✅ 已在浏览器中打开: $url"
        })

        // ========== 基础工具 ==========
        register(AgentTool("calculator", "执行数学计算", "基础", listOf(ToolParameter("expression", "string", "数学表达式"))) { params ->
            val expr = params["expression"]?.toString() ?: ""
            try { "计算结果: $expr = ${evaluateExpression(expr)}" } catch (e: Exception) { "计算错误: ${e.message}" }
        })
        
        register(AgentTool("current_time", "获取当前时间", "基础", emptyList()) {
            "当前时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}"
        })

        // ========== 扫描工具 ==========
        register(AgentTool("scan_document", "扫描文档 - 选择图片并处理成扫描件", "文档", listOf(
            ToolParameter("image_uri", "string", "图片URI（可选，默认调起选择器）", false),
            ToolParameter("effect", "string", "效果: original（自动增强）/ gray（灰度）/ colorful（彩色，默认）", false),
            ToolParameter("format", "string", "格式: image（图片，默认）/ pdf", false)
        )) { params ->
            val imageUri = params["image_uri"]?.toString()
            val effect = params["effect"]?.toString() ?: "colorful"
            val format = params["format"]?.toString() ?: "image"
            com.heartflow.scanner.DocumentScannerTool.execute(appContext, imageUri, effect, format)
        })

        // ========== 心虫核心工具 ==========
        register(AgentTool("dream", "心虫梦想引擎 - 深度思考", "心虫", listOf(ToolParameter("topic", "string", "思考主题"))) { params ->
            val topic = params["topic"]?.toString() ?: ""
            if (topic.isBlank()) "错误: 缺少主题" else hermesManager.dream(topic)
        })
        
        register(AgentTool("evolve", "心虫进化引擎 - 自我优化", "心虫", emptyList()) { hermesManager.evolve() })

        // ========== 记忆工具 ==========
        register(AgentTool("store_memory", "存储记忆", "记忆", listOf(
            ToolParameter("category", "string", "分类: working/episodic/core"),
            ToolParameter("key", "string", "关键词"),
            ToolParameter("value", "string", "内容")
        )) { params ->
            val category = params["category"]?.toString() ?: "working"
            val key = params["key"]?.toString() ?: ""
            val value = params["value"]?.toString() ?: ""
            if (key.isBlank()) "错误: 缺少关键词" else hermesManager.storeMemory(category, key, value)
        })
        
        register(AgentTool("recall_memory", "回忆记忆", "记忆", listOf(
            ToolParameter("category", "string", "分类"),
            ToolParameter("key", "string", "关键词")
        )) { params ->
            val category = params["category"]?.toString() ?: "working"
            val key = params["key"]?.toString() ?: ""
            if (key.isBlank()) "错误: 缺少关键词" else hermesManager.recallMemory(category, key)
        })
        
        register(AgentTool("search_memory", "搜索记忆", "记忆", listOf(ToolParameter("query", "string", "搜索关键词"))) { params ->
            val query = params["query"]?.toString() ?: ""
            if (query.isBlank()) "错误: 缺少关键词" else hermesManager.searchMemory(query)
        })

        // ========== 知识库工具 ==========
        register(AgentTool("store_knowledge", "存储知识", "知识库", listOf(
            ToolParameter("category", "string", "分类: people/places/tech/ideas"),
            ToolParameter("name", "string", "名称"),
            ToolParameter("content", "string", "内容")
        )) { params ->
            val category = params["category"]?.toString() ?: "ideas"
            val name = params["name"]?.toString() ?: ""
            val content = params["content"]?.toString() ?: ""
            if (name.isBlank()) "错误: 缺少名称" else hermesManager.storeKnowledge(category, name, content)
        })
        
        register(AgentTool("search_knowledge", "搜索知识库", "知识库", listOf(
            ToolParameter("query", "string", "搜索关键词"),
            ToolParameter("category", "string", "分类(可选)", false)
        )) { params ->
            val query = params["query"]?.toString() ?: ""
            val category = params["category"]?.toString()
            if (query.isBlank()) "错误: 缺少关键词" else hermesManager.searchKnowledge(query, category)
        })

        // ========== 网络工具（纯Kotlin实现） ==========
        register(AgentTool("fetch_url", "获取网页内容", "网络", listOf(ToolParameter("url", "string", "网页URL"))) { params ->
            val url = params["url"]?.toString() ?: ""
            if (url.isBlank()) "错误: 缺少URL" else fetchUrlPure(url)
        })
        
        register(AgentTool("search_web", "搜索网络信息（搜索结果 + 自动展开前 2 条全文，可直接引用来源 URL）", "网络", listOf(
            ToolParameter("query", "string", "搜索关键词"),
            ToolParameter("engine", "string", "引擎: duckduckgo (默认)/baidu/bing/github", false)
        )) { params ->
            val query = params["query"]?.toString() ?: ""
            val engine = params["engine"]?.toString() ?: "duckduckgo"
            if (query.isBlank()) "错误: 缺少关键词" else searchWebPure(query, engine)
        })

        // ========== 百度AI搜索工具 ==========
        register(AgentTool("baidu_search", "百度AI搜索 - 网页搜索", "百度AI", listOf(
            ToolParameter("query", "string", "搜索关键词")
        )) { params ->
            val query = params["query"]?.toString() ?: ""
            if (query.isBlank()) "错误: 缺少关键词" else baiduAISearch.webSearch(query)
        })
        
        register(AgentTool("baidu_baike", "百度百科搜索", "百度AI", listOf(
            ToolParameter("query", "string", "搜索关键词")
        )) { params ->
            val query = params["query"]?.toString() ?: ""
            if (query.isBlank()) "错误: 缺少关键词" else baiduAISearch.baikeSearch(query)
        })
        
        register(AgentTool("baidu_miaodong", "秒懂百科搜索（视频）", "百度AI", listOf(
            ToolParameter("query", "string", "搜索关键词")
        )) { params ->
            val query = params["query"]?.toString() ?: ""
            if (query.isBlank()) "错误: 缺少关键词" else baiduAISearch.miaodongBaike(query)
        })
        
        register(AgentTool("baidu_ai_chat", "百度AI智能问答", "百度AI", listOf(
            ToolParameter("query", "string", "问题")
        )) { params ->
            val query = params["query"]?.toString() ?: ""
            if (query.isBlank()) "错误: 缺少问题" else baiduAISearch.aiChat(query)
        })
        
        register(AgentTool("baidu_smart_search", "百度智能搜索（自动选择模式）", "百度AI", listOf(
            ToolParameter("query", "string", "搜索关键词")
        )) { params ->
            val query = params["query"]?.toString() ?: ""
            if (query.isBlank()) "错误: 缺少关键词" else baiduAISearch.smartSearch(query)
        })

        // ========== GitHub工具（纯Kotlin实现） ==========
        register(AgentTool("github_repo", "获取GitHub仓库信息", "GitHub", listOf(
            ToolParameter("owner", "string", "仓库所有者"),
            ToolParameter("repo", "string", "仓库名称")
        )) { params ->
            val owner = params["owner"]?.toString() ?: ""
            val repo = params["repo"]?.toString() ?: ""
            if (owner.isBlank() || repo.isBlank()) "错误: 缺少参数" else getGitHubRepoPure(owner, repo)
        })
        
        register(AgentTool("github_search", "搜索GitHub仓库", "GitHub", listOf(ToolParameter("query", "string", "搜索关键词"))) { params ->
            val query = params["query"]?.toString() ?: ""
            if (query.isBlank()) "错误: 缺少关键词" else searchGitHubPure(query)
        })

        // ========== Skill工具（纯Kotlin实现） ==========
        register(AgentTool("install_skill", "安装技能", "技能", listOf(ToolParameter("url", "string", "GitHub URL"))) { params ->
            val url = params["url"]?.toString() ?: ""
            if (url.isBlank()) "错误: 缺少URL" else installSkillPure(url)
        })
        
        register(AgentTool("list_skills", "列出已安装技能", "技能", emptyList()) { listSkillsPure() })

        // ========== 代码审查工具 ==========
        register(AgentTool("code_review", "审查代码", "开发", listOf(
            ToolParameter("code", "string", "代码内容"),
            ToolParameter("language", "string", "语言", false)
        )) { params ->
            val code = params["code"]?.toString() ?: ""
            val language = params["language"]?.toString() ?: "kotlin"
            if (code.isBlank()) "错误: 缺少代码" else reviewCodePure(code, language)
        })

        // ========== 系统工具 ==========
        register(AgentTool("system_info", "获取系统信息", "系统", emptyList()) { buildSystemInfo() })

        // ========== APK审计工具 ==========
        register(AgentTool("apk_info", "获取APK文件信息", "APK审计", listOf(ToolParameter("path", "string", "APK文件路径"))) { params ->
            val path = params["path"]?.toString() ?: ""
            if (path.isBlank()) "错误: 缺少文件路径" else apkAudit.getAPKInfo(path)
        })
        
        register(AgentTool("apk_search_tools", "搜索APK审计工具", "APK审计", emptyList()) { apkAudit.searchAuditTools() })
        
        register(AgentTool("apk_recommended", "获取推荐的审计工具", "APK审计", emptyList()) { apkAudit.getRecommendedTools() })
        
        register(AgentTool("apk_analyze_permissions", "分析APK权限", "APK审计", listOf(ToolParameter("path", "string", "APK文件路径"))) { params ->
            val path = params["path"]?.toString() ?: ""
            if (path.isBlank()) "错误: 缺少文件路径" else apkAudit.analyzePermissions(path)
        })
        
        register(AgentTool("apk_check_signature", "检查APK签名", "APK审计", listOf(ToolParameter("path", "string", "APK文件路径"))) { params ->
            val path = params["path"]?.toString() ?: ""
            if (path.isBlank()) "错误: 缺少文件路径" else apkAudit.checkSignature(path)
        })
        
        register(AgentTool("apk_security_report", "生成APK安全报告", "APK审计", listOf(ToolParameter("path", "string", "APK文件路径"))) { params ->
            val path = params["path"]?.toString() ?: ""
            if (path.isBlank()) "错误: 缺少文件路径" else apkAudit.generateSecurityReport(path)
        })

        // ========== Hermes技能管理工具 ==========
        register(AgentTool("hermes_list_skills", "列出所有Hermes技能", "Hermes", emptyList()) { hermesSkillIntegrator.listAllSkills() })
        
        register(AgentTool("hermes_search_skills", "搜索Hermes技能", "Hermes", listOf(ToolParameter("query", "string", "搜索关键词"))) { params ->
            val query = params["query"]?.toString() ?: ""
            if (query.isBlank()) "错误: 缺少关键词" else {
                val results = hermesSkillIntegrator.searchSkills(query)
                if (results.isEmpty()) "未找到相关技能" else {
                    "搜索结果: ${results.size}个技能\n" + results.joinToString("\n") { "• ${it.name}: ${it.description.take(50)}" }
                }
            }
        })
        
        register(AgentTool("hermes_skill_detail", "获取技能详情", "Hermes", listOf(ToolParameter("name", "string", "技能名称"))) { params ->
            val name = params["name"]?.toString() ?: ""
            if (name.isBlank()) "错误: 缺少技能名称" else hermesSkillIntegrator.getSkillDetail(name)
        })
        
        register(AgentTool("hermes_recommended", "获取推荐技能", "Hermes", listOf(ToolParameter("task", "string", "任务类型: 搜索/代码/文档/ai/数据/记忆/工具/安全"))) { params ->
            val task = params["task"]?.toString() ?: ""
            if (task.isBlank()) "错误: 缺少任务类型" else {
                val skills = hermesSkillIntegrator.getRecommendedSkills(task)
                if (skills.isEmpty()) "未找到相关技能" else {
                    "推荐技能: ${skills.size}个\n" + skills.joinToString("\n") { "• ${it.name}: ${it.description.take(50)}" }
                }
            }
        })

        // 全部工具注册完成后，同步到 engine.ToolRegistry
        syncToEngine()
    }

    // ========== 公开的 Skill 操作方法 ==========

    fun installSkill(url: String): String = installSkillPure(url)

    fun listInstalledSkills(): String = listSkillsPure()

    fun webFetch(url: String): String = fetchUrlPure(url)

    fun webSearch(query: String, engine: String = "duckduckgo"): String = searchWebPure(query, engine)

    fun gitHubSearch(query: String): String = searchGitHubPure(query)

    fun getInstalledSkillNames(): List<String> {
        if (!::appContext.isInitialized) return emptyList()
        val dir = skillsDir
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.filter { it.name.endsWith(".skill") }?.map { it.nameWithoutExtension } ?: emptyList()
    }

    // ========== 动态技能注册 ==========

    /**
     * 扫描已安装的 .skill 文件，为每个文件注册一个可调用的 AgentTool。
     * 技能文件名作为工具名，frontmatter 中的 description 作为工具描述。
     */
    fun refreshDynamicSkills() {
        // 清除之前注册的动态技能
        dynamicTools.forEach { tools.remove(it) }
        dynamicTools.clear()

        if (!::appContext.isInitialized) return
        val dir = skillsDir
        if (!dir.exists()) return

        dir.listFiles()?.filter { it.name.endsWith(".skill") }?.forEach { file ->
            try {
                val content = file.readText(Charsets.UTF_8)
                val skillName = parseSkillName(content, file.nameWithoutExtension)
                val skillDesc = parseSkillDescription(content, "自定义技能: ${file.nameWithoutExtension}")

                val tool = AgentTool(
                    name = "skill_$skillName",
                    description = skillDesc,
                    category = "技能",
                    parameters = listOf(
                        ToolParameter("query", "string", "技能执行的具体问题或输入", false)
                    )
                ) { params ->
                    val fullContent = file.readText(Charsets.UTF_8)
                    "📦 技能「$skillName」内容:\n\n$fullContent\n\n---\n请运用该技能的指导方法回答用户。"
                }

                register(tool)
                dynamicTools.add(tool)
            } catch (e: Exception) {
                Log.w(TAG, "refreshDynamicSkills: failed to parse skill file", e)
            }
        }
    }

    /** 从 SKILL.md frontmatter 中解析技能名称 */
    private fun parseSkillName(content: String, fallback: String): String {
        val match = Regex("""^---\s*\n(.*?)\n---""", RegexOption.DOT_MATCHES_ALL).find(content)
        val frontmatter = match?.groupValues?.getOrNull(1) ?: return fallback
        val nameMatch = Regex("""name:\s*["']?(.+?)["']?(\n|$)""").find(frontmatter)
        return nameMatch?.groupValues?.getOrNull(1)?.trim() ?: fallback
    }

    /** 从 SKILL.md frontmatter 中解析技能描述 */
    private fun parseSkillDescription(content: String, fallback: String): String {
        val match = Regex("""^---\s*\n(.*?)\n---""", RegexOption.DOT_MATCHES_ALL).find(content)
        val frontmatter = match?.groupValues?.getOrNull(1) ?: return fallback
        val descMatch = Regex("""description:\s*["']?(.+?)["']?(\n|$)""").find(frontmatter)
        return descMatch?.groupValues?.getOrNull(1)?.trim() ?: fallback
    }

    // ========== 纯Kotlin实现的工具函数 ==========
    
    private fun fetchUrlPure(url: String): String {
        return try {
            val ctx = appContext
            if (ctx != null) {
                val engine = WebViewEngine(ctx)
                engine.fetchPageSync(url, timeoutMs = 15000L)
            } else {
                fetchUrlHttp(url)
            }
        } catch (e: Exception) {
            fetchUrlHttp(url)
        }
    }

    /** HTTP回退方案：纯请求获取HTML */
    private fun fetchUrlHttp(url: String): String {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36")
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")

            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val content = reader.readText()
            reader.close()
            conn.disconnect()

            val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                .find(content)?.groupValues?.get(1)?.trim() ?: "无标题"

            val text = content.replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), "")
                .replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), "")
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            "🌐 网页内容:\n标题: $title\n长度: ${text.length} 字符\n${text.take(3000)}"
        } catch (e: java.net.UnknownHostException) {
            "❌ 获取失败: 域名无法解析（$url），请检查网络连接"
        } catch (e: java.net.SocketTimeoutException) {
            "❌ 获取失败: 连接超时（${url.take(60)}）"
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            "❌ 获取失败: SSL 证书错误 - ${e.message}"
        } catch (e: Exception) {
            "❌ 获取失败: ${e.message}"
        }
    }

    private fun searchWebPure(query: String, engine: String = "baidu"): String {
        val cacheKey = "web:$engine:$query"
        searchCache.get(cacheKey)?.let { return it }

        val rawResult = try {
            when (engine.lowercase()) {
                "github" -> searchGitHubPure(query)
                "bing" -> searchBingPure(query)
                "sogou" -> searchSogouPure(query)
                "360" -> search360Pure(query)
                else -> {
                    // 默认：百度 → 必应 → 搜狗（超时时不再尝试下一个）
                    val r1 = searchBaiduPure(query)
                    if (!r1.startsWith("❌") || r1.contains("超时")) r1
                    else {
                        val r2 = searchBingPure(query)
                        if (!r2.startsWith("❌") || r2.contains("超时")) r2
                        else searchSogouPure(query)
                    }
                }
            }
        } catch (e: Exception) {
            "❌ 搜索失败: ${e.message}"
        }

        searchCache.set(cacheKey, rawResult)
        return rawResult
    }

    /** 百度搜索 - 简单可靠 */
    private fun searchBaiduPure(query: String): String {
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val conn = URL("https://www.baidu.com/s?wd=$encoded&rn=5").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            conn.instanceFollowRedirects = true

            val html = try {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conn.disconnect()
            }

            // 限制处理的数据量，避免正则卡死
            val limitedHtml = if (html.length > 100000) html.substring(0, 100000) else html

            // 解析百度搜索结果
            val results = mutableListOf<String>()
            // 匹配搜索结果标题和链接 - 使用更简单的正则
            val titlePattern = Regex("<h3[^>]*>\\s*<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            val matches = titlePattern.findAll(limitedHtml).toList()

            for (match in matches.take(5)) {
                val url = match.groupValues[1]
                val title = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                if (title.isNotBlank() && url.startsWith("http")) {
                    results.add("• $title")
                    results.add("  $url")
                }
            }

            if (results.isEmpty()) {
                // 回退：提取所有可见文本 - 使用非贪婪匹配避免回溯
                val text = limitedHtml
                    .replace(Regex("<script[^>]*?>[\\s\\S]*?</script>"), "")
                    .replace(Regex("<style[^>]*?>[\\s\\S]*?</style>"), "")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ").trim()
                if (text.length > 200) "🔍 百度: $query\n${text.take(1500)}" else "❌ 百度无结果"
            } else {
                "🔍 百度搜索: $query\n${results.joinToString("\n")}"
            }
        } catch (e: java.net.SocketTimeoutException) {
            "❌ 百度超时: 连接或读取超时"
        } catch (e: java.net.UnknownHostException) {
            "❌ 百度失败: 域名无法解析"
        } catch (e: Exception) {
            "❌ 百度失败: ${e.message?.take(50)}"
        }
    }

    /** 必应搜索 */
    private fun searchBingPure(query: String): String {
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val conn = URL("https://cn.bing.com/search?q=$encoded&count=5").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            conn.instanceFollowRedirects = true

            val html = try {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conn.disconnect()
            }

            // 限制处理的数据量
            val limitedHtml = if (html.length > 100000) html.substring(0, 100000) else html

            val results = mutableListOf<String>()
            val titlePattern = Regex("<h2[^>]*>\\s*<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            val matches = titlePattern.findAll(limitedHtml).toList()

            for (match in matches.take(5)) {
                val url = match.groupValues[1]
                val title = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                if (title.isNotBlank() && url.startsWith("http")) {
                    results.add("• $title")
                    results.add("  $url")
                }
            }

            if (results.isEmpty()) {
                val text = limitedHtml
                    .replace(Regex("<script[^>]*?>[\\s\\S]*?</script>"), "")
                    .replace(Regex("<style[^>]*?>[\\s\\S]*?</style>"), "")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ").trim()
                if (text.length > 200) "🔍 必应: $query\n${text.take(1500)}" else "❌ 必应无结果"
            } else {
                "🔍 必应搜索: $query\n${results.joinToString("\n")}"
            }
        } catch (e: java.net.SocketTimeoutException) {
            "❌ 必应超时: 连接或读取超时"
        } catch (e: java.net.UnknownHostException) {
            "❌ 必应失败: 域名无法解析"
        } catch (e: Exception) {
            "❌ 必应失败: ${e.message?.take(50)}"
        }
    }

    /** 搜狗搜索 */
    private fun searchSogouPure(query: String): String {
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val conn = URL("https://www.sogou.com/web?query=$encoded").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            conn.instanceFollowRedirects = true

            val html = try {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conn.disconnect()
            }

            // 限制处理的数据量
            val limitedHtml = if (html.length > 100000) html.substring(0, 100000) else html

            val results = mutableListOf<String>()
            val titlePattern = Regex("<h3[^>]*>\\s*<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            val matches = titlePattern.findAll(limitedHtml).toList()

            for (match in matches.take(5)) {
                val url = match.groupValues[1]
                val title = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                if (title.isNotBlank() && url.startsWith("http")) {
                    results.add("• $title")
                    results.add("  $url")
                }
            }

            if (results.isEmpty()) {
                val text = limitedHtml
                    .replace(Regex("<script[^>]*?>[\\s\\S]*?</script>"), "")
                    .replace(Regex("<style[^>]*?>[\\s\\S]*?</style>"), "")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ").trim()
                if (text.length > 200) "🔍 搜狗: $query\n${text.take(1500)}" else "❌ 搜狗无结果"
            } else {
                "🔍 搜狗搜索: $query\n${results.joinToString("\n")}"
            }
        } catch (e: java.net.SocketTimeoutException) {
            "❌ 搜狗超时: 连接或读取超时"
        } catch (e: java.net.UnknownHostException) {
            "❌ 搜狗失败: 域名无法解析"
        } catch (e: Exception) {
            "❌ 搜狗失败: ${e.message?.take(50)}"
        }
    }

    /** 360搜索 */
    private fun search360Pure(query: String): String {
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val conn = URL("https://www.so.com/s?q=$encoded").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            conn.instanceFollowRedirects = true

            val html = try {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conn.disconnect()
            }

            // 限制处理的数据量
            val limitedHtml = if (html.length > 100000) html.substring(0, 100000) else html

            val results = mutableListOf<String>()
            val titlePattern = Regex("<h3[^>]*>\\s*<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            val matches = titlePattern.findAll(limitedHtml).toList()

            for (match in matches.take(5)) {
                val url = match.groupValues[1]
                val title = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                if (title.isNotBlank() && url.startsWith("http")) {
                    results.add("• $title")
                    results.add("  $url")
                }
            }

            if (results.isEmpty()) {
                val text = limitedHtml
                    .replace(Regex("<script[^>]*?>[\\s\\S]*?</script>"), "")
                    .replace(Regex("<style[^>]*?>[\\s\\S]*?</style>"), "")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ").trim()
                if (text.length > 200) "🔍 360: $query\n${text.take(1500)}" else "❌ 360无结果"
            } else {
                "🔍 360搜索: $query\n${results.joinToString("\n")}"
            }
        } catch (e: java.net.SocketTimeoutException) {
            "❌ 360超时: 连接或读取超时"
        } catch (e: java.net.UnknownHostException) {
            "❌ 360失败: 域名无法解析"
        } catch (e: Exception) {
            "❌ 360失败: ${e.message?.take(50)}"
        }
    }

    /** 搜索结果内存缓存（最多 50 条，TTL 5 分钟） */
    private class SearchCache(private val maxSize: Int = 50, private val ttlMs: Long = 300_000L) {
        private data class Entry(val result: String, val timestamp: Long)
        private val cache = object : LinkedHashMap<String, Entry>(maxSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > maxSize
        }

        @Synchronized
        fun get(key: String): String? {
            val entry = cache[key] ?: return null
            if (System.currentTimeMillis() - entry.timestamp > ttlMs) {
                cache.remove(key)
                return null
            }
            return entry.result
        }

        @Synchronized
        fun set(key: String, result: String) {
            cache[key] = Entry(result, System.currentTimeMillis())
        }
    }

    private fun getGitHubRepoPure(owner: String, repo: String): String {
        return try {
            val conn = URL("https://api.github.com/repos/$owner/$repo").openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "HeartFlow/2.2.2")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val content = reader.readText()
            reader.close()
            conn.disconnect()
            
            val json = JSONObject(content)
            "📦 ${json.getString("full_name")}\n📝 ${json.optString("description", "无描述")}\n⭐ ${json.optInt("stargazers_count")} stars\n🔗 ${json.getString("html_url")}"
        } catch (e: Exception) {
            "❌ 获取失败: ${e.message}"
        }
    }
    
    private fun searchGitHubPure(query: String): String {
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val conn = URL("https://api.github.com/search/repositories?q=$encoded&sort=stars&per_page=5").openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "HeartFlow/2.2.2")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val content = reader.readText()
            reader.close()
            conn.disconnect()
            
            val json = JSONObject(content)
            val items = json.getJSONArray("items")
            
            buildString {
                appendLine("📦 GitHub搜索: '$query' (${items.length()}个)")
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    appendLine("${i + 1}. ${item.getString("full_name")} (⭐ ${item.optInt("stargazers_count")})")
                    appendLine("   ${item.optString("description", "").take(80)}")
                }
            }
        } catch (e: Exception) {
            "❌ 搜索失败: ${e.message}"
        }
    }
    
    private fun installSkillPure(url: String): String {
        return try {
            if (!::appContext.isInitialized) return "❌ ToolRegistry 未初始化，无法安装技能"
            skillsDir.mkdirs()

            val rawUrl = when {
                url.contains("github.com") -> {
                    url.replace("github.com", "raw.githubusercontent.com")
                        .replace("/blob/", "/")
                        .let { if (!it.contains("/main/") && !it.contains("/master/")) "$it/main" else it }
                        .let { if (!it.endsWith(".md") && !it.endsWith(".json")) "$it/SKILL.md" else it }
                }
                else -> url
            }

            val conn = URL(rawUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 30000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36")
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")

            if (conn.responseCode != 200) return "❌ 下载失败: HTTP ${conn.responseCode}"

            val content = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).readText()
            conn.disconnect()

            val name = url.substringAfterLast("/").substringBeforeLast(".").ifBlank { "skill_${System.currentTimeMillis()}" }
            File(skillsDir, "$name.skill").writeText(content, Charsets.UTF_8)
            refreshDynamicSkills()

            "✅ 安装成功: $name"
        } catch (e: java.net.UnknownHostException) {
            "❌ 安装失败: 域名无法解析，请检查网络连接"
        } catch (e: java.net.SocketTimeoutException) {
            "❌ 安装失败: 连接超时（服务器响应慢）"
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            "❌ 安装失败: SSL 证书错误 - ${e.message}"
        } catch (e: Exception) {
            "❌ 安装失败: ${e.message}"
        }
    }
    
    private fun listSkillsPure(): String {
        if (!::appContext.isInitialized) return "暂未安装技能（未初始化）"
        val dir = skillsDir
        if (!dir.exists()) return "暂未安装技能"
        val files = dir.listFiles()?.filter { it.name.endsWith(".skill") } ?: emptyList()
        return if (files.isEmpty()) "暂未安装技能" else {
            "已安装 (${files.size}个):\n" + files.joinToString("\n") { "📦 ${it.nameWithoutExtension}" }
        }
    }
    
    private fun reviewCodePure(code: String, language: String): String {
        val lines = code.lines()
        val suggestions = mutableListOf<String>()
        if (lines.size > 100) suggestions.add("💡 代码较长，建议拆分")
        if (lines.count { it.isBlank() } > lines.size * 0.3) suggestions.add("💡 空行较多")
        return "📋 代码审查 ($language)\n行数: ${lines.size}\n" + if (suggestions.isEmpty()) "✅ 代码质量良好" else suggestions.joinToString("\n")
    }
    
    private fun buildSystemInfo(): String {
        val runtime = Runtime.getRuntime()
        return buildString {
            appendLine("📱 系统信息:")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Java: ${System.getProperty("java.version")}")
            appendLine("内存: ${runtime.maxMemory() / 1024 / 1024}MB")
            appendLine("CPU: ${runtime.availableProcessors()}核")
        }
    }

    fun register(tool: AgentTool) {
        tools.add(tool)
        // 同步到 engine.ToolRegistry
        val def = agentToolToDefinition(tool)
        com.heartflow.engine.ToolRegistry.register(def)
    }

    /** 将全部已注册工具同步到 engine.ToolRegistry（幂等） */
    private fun syncToEngine() {
        for (tool in tools) {
            val def = agentToolToDefinition(tool)
            com.heartflow.engine.ToolRegistry.register(def)
        }
    }

    /** 将 AgentTool 转换为 engine.ToolDefinition，保留执行逻辑 */
    private fun agentToolToDefinition(tool: AgentTool): ToolDefinition {
        return ToolDefinition(
            name = tool.name,
            description = tool.description,
            parameters = agentParamsToJsonSchema(tool.parameters),
            category = agentCategoryToEngineCategory(tool.category),
            executor = { args -> tool.execute(args) }
        )
    }

    /** 将 AgentTool 的 List<ToolParameter> 转换为 JSON Schema */
    private fun agentParamsToJsonSchema(params: List<ToolParameter>): JSONObject {
        return JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                for (p in params) {
                    put(p.name, JSONObject().apply {
                        put("type", p.type)
                        put("description", p.description)
                    })
                }
            })
            put("required", JSONArray().apply {
                for (p in params.filter { it.required }) {
                    put(p.name)
                }
            })
        }
    }

    /** 将 data 层分类字符串映射到 engine 层枚举 */
    private fun agentCategoryToEngineCategory(category: String): ToolCategory {
        return when (category.lowercase()) {
            "基础", "核心", "开发", "系统" -> ToolCategory.CORE
            "文档" -> ToolCategory.FILE
            "网络", "百度ai", "github" -> ToolCategory.WEB
            "记忆", "知识库" -> ToolCategory.MEMORY
            "技能" -> ToolCategory.SKILL
            "心虫" -> ToolCategory.CORE
            "apk审计" -> ToolCategory.FILE
            "hermes" -> ToolCategory.SKILL
            else -> ToolCategory.CORE
        }
    }
    fun getAll(): List<AgentTool> = tools.toList()
    fun getByName(name: String): AgentTool? = tools.firstOrNull { it.name == name }

    fun getToolDescriptions(): String {
        return tools.joinToString("\n") { tool ->
            "- ${tool.name}: ${tool.description}"
        }
    }

    /**
     * 添加函数调用格式（兼容 OpenAI function calling）
     */
    fun getFunctionCallFormat(): List<Map<String, Any>> {
        return tools.map { tool ->
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to tool.name,
                    "description" to tool.description,
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to tool.parameters.associate { param ->
                            param.name to mapOf("type" to param.type, "description" to param.description)
                        },
                        "required" to tool.parameters.filter { it.required }.map { it.name }
                    )
                )
            )
        }
    }

    private fun evaluateExpression(expr: String): Double {
        val sanitized = expr.replace(" ", "")
        return object : Any() {
            var pos = 0
            fun parse(): Double {
                var result = parseTerm()
                while (pos < sanitized.length) { when (sanitized[pos]) { '+' -> { pos++; result += parseTerm() } '-' -> { pos++; result -= parseTerm() } else -> break } }
                return result
            }
            fun parseTerm(): Double {
                var result = parseFactor()
                while (pos < sanitized.length) { when (sanitized[pos]) { '*' -> { pos++; result *= parseFactor() } '/' -> { pos++; result /= parseFactor() } else -> break } }
                return result
            }
            fun parseFactor(): Double {
                if (pos < sanitized.length && sanitized[pos] == '(') { pos++; val result = parse(); if (pos < sanitized.length && sanitized[pos] == ')') pos++; return result }
                val start = pos
                if (pos < sanitized.length && sanitized[pos] == '-') pos++
                while (pos < sanitized.length && (sanitized[pos].isDigit() || sanitized[pos] == '.')) pos++
                return sanitized.substring(start, pos).toDouble()
            }
        }.parse()
    }
}
