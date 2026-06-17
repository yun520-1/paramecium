package com.heartflow.skills

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * SkillLoader - 技能加载器
 * 扫描技能目录，解析frontmatter，检测冲突
 */
class SkillLoader(private val skillsDir: File) {
    
    // 技能缓存：名称 -> 技能元数据
    private val skillCache = ConcurrentHashMap<String, SkillMetadata>()
    
    // 排除的目录模式
    private val excludedDirs = setOf(
        "node_modules", ".git", ".svn", ".hg",
        "build", "dist", "__pycache__", ".idea",
        ".vscode", "target", "out", "bin",
        "obj", "packages", "vendor", "bower_components"
    )
    
    // 文件扩展名白名单
    private val allowedExtensions = setOf(
        ".md", ".txt", ".json", ".yaml", ".yml",
        ".kt", ".java", ".py", ".js", ".ts",
        ".sh", ".bat", ".cmd", ".ps1"
    )
    
    /**
     * 扫描技能目录并加载所有技能
     * @return 加载的技能列表
     */
    fun scanAndLoadSkills(): List<SkillMetadata> {
        val skills = mutableListOf<SkillMetadata>()
        
        if (!skillsDir.exists() || !skillsDir.isDirectory) {
            println("⚠️ 技能目录不存在: ${skillsDir.absolutePath}")
            return skills
        }
        
        // 递归扫描目录
        scanDirectory(skillsDir, skills)
        
        // 缓存技能
        skills.forEach { skill ->
            skillCache[skill.name] = skill
        }
        
        println("✅ 成功加载 ${skills.size} 个技能")
        return skills
    }
    
    /**
     * 递归扫描目录
     */
    private fun scanDirectory(dir: File, skills: MutableList<SkillMetadata>) {
        // 检查是否在排除列表中
        if (excludedDirs.contains(dir.name)) {
            return
        }
        
        dir.listFiles()?.forEach { file ->
            when {
                file.isDirectory -> scanDirectory(file, skills)
                file.isFile && isSkillFile(file) -> {
                    val metadata = parseSkillFile(file)
                    if (metadata != null) {
                        // 检测冲突
                        val conflict = detectConflict(metadata)
                        if (conflict != null) {
                            println("⚠️ 技能冲突: ${conflict.first} 与 ${conflict.second} 冲突")
                        } else {
                            skills.add(metadata)
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 判断是否为技能文件
     */
    private fun isSkillFile(file: File): Boolean {
        val name = file.name.lowercase()
        
        // 检查扩展名
        val hasValidExtension = allowedExtensions.any { name.endsWith(it) }
        
        // 检查文件名模式
        val isValidName = name.endsWith(".skill") || 
                         name == "skill.md" || 
                         name.startsWith("skill-") ||
                         name.contains("skill")
        
        return hasValidExtension && isValidName
    }
    
    /**
     * 解析技能文件
     * @param file 技能文件
     * @return 技能元数据，解析失败返回null
     */
    fun parseSkillFile(file: File): SkillMetadata? {
        return try {
            val content = file.readText(Charsets.UTF_8)
            
            // 检查文件大小限制（100K字符）
            if (content.length > 100 * 1024) {
                println("⚠️ 技能文件过大 (${content.length} 字符): ${file.name}")
                return null
            }
            
            // 解析frontmatter
            val frontmatter = parseFrontmatter(content)
            
            // 提取元数据
            val name = frontmatter["name"] ?: file.nameWithoutExtension
            val description = frontmatter["description"] ?: ""
            
            // 验证元数据
            val validationError = SkillValidator.validate(name, description, content.length)
            if (validationError != null) {
                println("⚠️ 技能验证失败: $validationError (${file.name})")
                return null
            }
            
            // 提取平台兼容性
            val platforms = PlatformMatcher.parsePlatforms(frontmatter)
            
            // 提取关联文件
            val relatedFiles = extractRelatedFiles(file.parentFile)
            
            SkillMetadata(
                name = name,
                description = description,
                filePath = file.absolutePath,
                content = content,
                platforms = platforms,
                relatedFiles = relatedFiles,
                fileSize = content.length,
                lastModified = file.lastModified()
            )
        } catch (e: Exception) {
            println("❌ 解析技能文件失败: ${file.name} - ${e.message}")
            null
        }
    }
    
    /**
     * 解析YAML frontmatter
     * @param content 文件内容
     * @return frontmatter键值对
     */
    fun parseFrontmatter(content: String): Map<String, String> {
        val frontmatter = mutableMapOf<String, String>()
        
        // 检查是否有frontmatter
        if (!content.startsWith("---")) {
            return frontmatter
        }
        
        val lines = content.lines()
        var inFrontmatter = false
        var frontmatterContent = StringBuilder()
        
        for (line in lines) {
            when {
                line.trim() == "---" && !inFrontmatter -> {
                    inFrontmatter = true
                    continue
                }
                line.trim() == "---" && inFrontmatter -> {
                    // 解析frontmatter内容
                    parseYamlContent(frontmatterContent.toString(), frontmatter)
                    break
                }
                inFrontmatter -> {
                    frontmatterContent.appendLine(line)
                }
            }
        }
        
        return frontmatter
    }
    
    /**
     * 解析YAML内容
     */
    private fun parseYamlContent(yaml: String, result: MutableMap<String, String>) {
        var currentKey = ""
        var currentValue = StringBuilder()
        var isMultilineValue = false
        
        for (line in yaml.lines()) {
            val trimmedLine = line.trim()
            
            when {
                trimmedLine.isEmpty() && isMultilineValue -> {
                    currentValue.appendLine()
                }
                trimmedLine.contains(":") -> {
                    // 保存上一个键值对
                    if (currentKey.isNotEmpty()) {
                        result[currentKey] = currentValue.toString().trim()
                    }
                    
                    // 解析新的键值对
                    val colonIndex = trimmedLine.indexOf(':')
                    currentKey = trimmedLine.substring(0, colonIndex).trim()
                    val value = trimmedLine.substring(colonIndex + 1).trim()
                    
                    if (value.isEmpty()) {
                        // 多行值
                        isMultilineValue = true
                        currentValue = StringBuilder()
                    } else {
                        // 单行值
                        isMultilineValue = false
                        currentValue = StringBuilder(value)
                    }
                }
                isMultilineValue -> {
                    currentValue.appendLine(trimmedLine)
                }
            }
        }
        
        // 保存最后一个键值对
        if (currentKey.isNotEmpty()) {
            result[currentKey] = currentValue.toString().trim()
        }
    }
    
    /**
     * 提取关联文件
     */
    private fun extractRelatedFiles(dir: File?): List<String> {
        val relatedFiles = mutableListOf<String>()
        
        dir?.listFiles()?.forEach { file ->
            if (file.isFile && file.name != "SKILL.md" && file.name != "skill.md") {
                relatedFiles.add(file.absolutePath)
            }
        }
        
        return relatedFiles
    }
    
    /**
     * 检测技能冲突
     * @param metadata 新技能元数据
     * @return 冲突信息（技能名称1, 技能名称2），无冲突返回null
     */
    private fun detectConflict(metadata: SkillMetadata): Pair<String, String>? {
        val existingSkill = skillCache[metadata.name]
        
        if (existingSkill != null) {
            // 同名技能冲突
            return Pair(metadata.name, existingSkill.name)
        }
        
        // 检查描述相似度冲突（简化版本）
        for ((_, existing) in skillCache) {
            if (isDescriptionConflict(metadata.description, existing.description)) {
                return Pair(metadata.name, existing.name)
            }
        }
        
        return null
    }
    
    /**
     * 检查描述冲突
     */
    private fun isDescriptionConflict(desc1: String, desc2: String): Boolean {
        // 简化的冲突检测：如果描述高度相似且超过阈值
        val similarity = calculateSimilarity(desc1, desc2)
        return similarity > 0.8
    }
    
    /**
     * 计算字符串相似度（Jaccard相似度）
     */
    private fun calculateSimilarity(s1: String, s2: String): Double {
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        
        val words1 = s1.split("\\s+".toRegex()).toSet()
        val words2 = s2.split("\\s+".toRegex()).toSet()
        
        val intersection = words1.intersect(words2).size.toDouble()
        val union = words1.union(words2).size.toDouble()
        
        return if (union > 0) intersection / union else 0.0
    }
    
    /**
     * 获取技能元数据
     * @param name 技能名称
     * @return 技能元数据，不存在返回null
     */
    fun getSkill(name: String): SkillMetadata? {
        return skillCache[name]
    }
    
    /**
     * 获取所有技能名称
     * @return 技能名称列表
     */
    fun getSkillNames(): List<String> {
        return skillCache.keys.toList()
    }
    
    /**
     * 清除缓存
     */
    fun clearCache() {
        skillCache.clear()
    }
}

/**
 * 技能元数据
 */
data class SkillMetadata(
    val name: String,
    val description: String,
    val filePath: String,
    val content: String,
    val platforms: Set<Platform>,
    val relatedFiles: List<String>,
    val fileSize: Int,
    val lastModified: Long
) {
    /**
     * 获取Tier1元数据（仅名称和描述）
     */
    fun toTier1(): Tier1Metadata {
        return Tier1Metadata(
            name = name,
            description = description,
            platforms = platforms
        )
    }
    
    /**
     * 获取Tier2完整指令（包含完整内容）
     */
    fun toTier2(): Tier2Metadata {
        return Tier2Metadata(
            name = name,
            description = description,
            content = content,
            platforms = platforms,
            fileSize = fileSize
        )
    }
    
    /**
     * 获取Tier3关联文件
     */
    fun toTier3(): Tier3Metadata {
        return Tier3Metadata(
            name = name,
            relatedFiles = relatedFiles,
            totalFiles = relatedFiles.size + 1
        )
    }
}

/**
 * 渐进式披露层级
 * Tier1: 仅元数据（名称、描述）
 * Tier2: 完整指令
 * Tier3: 关联文件
 */
enum class DisclosureTier {
    TIER_1,  // 元数据
    TIER_2,  // 完整指令
    TIER_3   // 关联文件
}

/**
 * Tier1元数据
 */
data class Tier1Metadata(
    val name: String,
    val description: String,
    val platforms: Set<Platform>
)

/**
 * Tier2元数据（包含完整内容）
 */
data class Tier2Metadata(
    val name: String,
    val description: String,
    val content: String,
    val platforms: Set<Platform>,
    val fileSize: Int
)

/**
 * Tier3元数据（包含关联文件）
 */
data class Tier3Metadata(
    val name: String,
    val relatedFiles: List<String>,
    val totalFiles: Int
)

/**
 * ProgressiveDisclosure - 渐进式披露管理器
 * 根据需要加载不同层级的技能信息
 */
class ProgressiveDisclosure(private val loader: SkillLoader) {
    
    /**
     * 获取指定层级的技能信息
     * @param skillName 技能名称
     * @param tier 披露层级
     * @return 技能信息，不存在返回null
     */
    fun getSkillInfo(skillName: String, tier: DisclosureTier): Any? {
        val metadata = loader.getSkill(skillName) ?: return null
        
        return when (tier) {
            DisclosureTier.TIER_1 -> metadata.toTier1()
            DisclosureTier.TIER_2 -> metadata.toTier2()
            DisclosureTier.TIER_3 -> metadata.toTier3()
        }
    }
    
    /**
     * 获取技能摘要（Tier1）
     * @param skillName 技能名称
     * @return 技能摘要
     */
    fun getSkillSummary(skillName: String): String? {
        val metadata = loader.getSkill(skillName) ?: return null
        return """
            |技能: ${metadata.name}
            |描述: ${metadata.description}
            |平台: ${metadata.platforms.joinToString(", ") { it.name }}
            |文件大小: ${metadata.fileSize} 字符
        """.trimMargin()
    }
    
    /**
     * 获取技能完整内容（Tier2）
     * @param skillName 技能名称
     * @return 技能完整内容
     */
    fun getSkillContent(skillName: String): String? {
        return loader.getSkill(skillName)?.content
    }
    
    /**
     * 获取技能关联文件列表（Tier3）
     * @param skillName 技能名称
     * @return 关联文件路径列表
     */
    fun getRelatedFiles(skillName: String): List<String> {
        return loader.getSkill(skillName)?.relatedFiles ?: emptyList()
    }
}

/**
 * 平台枚举
 */
enum class Platform {
    ANDROID,
    IOS,
    DESKTOP,
    WEB,
    ALL
}

/**
 * PlatformMatcher - 平台兼容性匹配器
 */
object PlatformMatcher {
    
    // 平台关键词映射
    private val platformKeywords = mapOf(
        Platform.ANDROID to setOf("android", "mobile", "phone", "tablet"),
        Platform.IOS to setOf("ios", "iphone", "ipad", "apple", "swift"),
        Platform.DESKTOP to setOf("desktop", "windows", "macos", "linux", "electron"),
        Platform.WEB to setOf("web", "browser", "javascript", "typescript", "react", "vue"),
        Platform.ALL to setOf("all", "cross-platform", "multi-platform")
    )
    
    /**
     * 解析平台兼容性
     * @param frontmatter frontmatter键值对
     * @return 支持的平台集合
     */
    fun parsePlatforms(frontmatter: Map<String, String>): Set<Platform> {
        val platforms = mutableSetOf<Platform>()
        
        // 从frontmatter提取平台信息
        val platformField = frontmatter["platforms"] ?: 
                           frontmatter["platform"] ?: 
                           frontmatter["target"] ?: ""
        
        if (platformField.isNotEmpty()) {
            // 解析逗号分隔的平台列表
            platformField.split(",", ";").forEach { platformStr ->
                val platform = parsePlatformString(platformStr.trim())
                if (platform != null) {
                    platforms.add(platform)
                }
            }
        }
        
        // 从描述和内容推断平台
        val description = frontmatter["description"] ?: ""
        val name = frontmatter["name"] ?: ""
        val combinedText = "$name $description".lowercase()
        
        for ((platform, keywords) in platformKeywords) {
            if (platform == Platform.ALL) continue
            if (keywords.any { combinedText.contains(it) }) {
                platforms.add(platform)
            }
        }
        
        // 如果没有明确指定平台，默认支持所有平台
        if (platforms.isEmpty()) {
            platforms.add(Platform.ALL)
        }
        
        return platforms
    }
    
    /**
     * 解析单个平台字符串
     */
    private fun parsePlatformString(platformStr: String): Platform? {
        return when (platformStr.lowercase()) {
            "android", "mobile" -> Platform.ANDROID
            "ios", "iphone", "ipad" -> Platform.IOS
            "desktop", "windows", "mac", "macos", "linux" -> Platform.DESKTOP
            "web", "browser" -> Platform.WEB
            "all", "cross-platform", "multi-platform" -> Platform.ALL
            else -> null
        }
    }
    
    /**
     * 检查技能是否兼容指定平台
     * @param skillPlatforms 技能支持的平台
     * @param targetPlatform 目标平台
     * @return 是否兼容
     */
    fun isCompatible(skillPlatforms: Set<Platform>, targetPlatform: Platform): Boolean {
        return skillPlatforms.contains(Platform.ALL) || skillPlatforms.contains(targetPlatform)
    }
    
    /**
     * 过滤兼容指定平台的技能
     * @param skills 技能列表
     * @param targetPlatform 目标平台
     * @return 兼容的技能列表
     */
    fun filterCompatible(skills: List<SkillMetadata>, targetPlatform: Platform): List<SkillMetadata> {
        return skills.filter { isCompatible(it.platforms, targetPlatform) }
    }
}

/**
 * SkillValidator - 技能验证器
 */
object SkillValidator {
    
    // 验证规则常量
    private const val MAX_NAME_LENGTH = 64
    private const val MAX_DESCRIPTION_LENGTH = 1024
    private const val MAX_FILE_SIZE = 100 * 1024  // 100K字符
    
    // 名称正则表达式（允许字母、数字、连字符、下划线）
    private val NAME_PATTERN = "^[a-zA-Z0-9_-]+$".toRegex()
    
    /**
     * 验证技能元数据
     * @param name 技能名称
     * @param description 技能描述
     * @param fileSize 文件大小（字符数）
     * @return 错误信息，验证通过返回null
     */
    fun validate(name: String, description: String, fileSize: Int): String? {
        // 验证名称
        val nameError = validateName(name)
        if (nameError != null) return nameError
        
        // 验证描述
        val descriptionError = validateDescription(description)
        if (descriptionError != null) return descriptionError
        
        // 验证文件大小
        val sizeError = validateFileSize(fileSize)
        if (sizeError != null) return sizeError
        
        return null
    }
    
    /**
     * 验证技能名称
     * @param name 技能名称
     * @return 错误信息，验证通过返回null
     */
    fun validateName(name: String): String? {
        return when {
            name.isEmpty() -> "技能名称不能为空"
            name.length > MAX_NAME_LENGTH -> "技能名称长度不能超过${MAX_NAME_LENGTH}字符（当前${name.length}字符）"
            !NAME_PATTERN.matches(name) -> "技能名称只能包含字母、数字、连字符和下划线"
            else -> null
        }
    }
    
    /**
     * 验证技能描述
     * @param description 技能描述
     * @return 错误信息，验证通过返回null
     */
    fun validateDescription(description: String): String? {
        return when {
            description.isEmpty() -> "技能描述不能为空"
            description.length > MAX_DESCRIPTION_LENGTH -> "技能描述长度不能超过${MAX_DESCRIPTION_LENGTH}字符（当前${description.length}字符）"
            else -> null
        }
    }
    
    /**
     * 验证文件大小
     * @param fileSize 文件大小（字符数）
     * @return 错误信息，验证通过返回null
     */
    fun validateFileSize(fileSize: Int): String? {
        return when {
            fileSize > MAX_FILE_SIZE -> "技能文件大小不能超过${MAX_FILE_SIZE/1024}K字符（当前${fileSize/1024}K字符）"
            fileSize <= 0 -> "技能文件不能为空"
            else -> null
        }
    }
    
    /**
     * 获取验证规则摘要
     * @return 验证规则描述
     */
    fun getValidationRules(): String {
        return """
            |技能验证规则:
            |1. 名称: 最大${MAX_NAME_LENGTH}字符，只允许字母、数字、连字符、下划线
            |2. 描述: 最大${MAX_DESCRIPTION_LENGTH}字符
            |3. 文件大小: 最大${MAX_FILE_SIZE/1024}K字符
        """.trimMargin()
    }
}