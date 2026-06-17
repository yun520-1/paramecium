package com.heartflow.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// ==================== 会话键格式 ====================
/**
 * 会话键格式: agent:main:{platform}:{chat_type}:{chat_id}
 * 例如: agent:main:wechat:private:user123
 */
object SessionKey {
    private const val SEPARATOR = ":"

    /**
     * 构建会话键
     */
    fun build(
        platform: String = "local",
        chatType: String = "default",
        chatId: String = "main"
    ): String {
        return "agent:main:$platform:$chatType:$chatId"
    }

    /**
     * 解析会话键
     */
    fun parse(key: String): SessionKeyParts? {
        val parts = key.split(SEPARATOR)
        return if (parts.size >= 4 && parts[0] == "agent") {
            SessionKeyParts(
                agent = parts[0],
                scope = parts[1],
                platform = parts.getOrElse(2) { "unknown" },
                chatType = parts.getOrElse(3) { "default" },
                chatId = parts.getOrElse(4) { "main" }
            )
        } else null
    }

    /**
     * 验证会话键格式是否有效
     */
    fun isValid(key: String): Boolean = parse(key) != null
}

data class SessionKeyParts(
    val agent: String,
    val scope: String,
    val platform: String,
    val chatType: String,
    val chatId: String
)

// ==================== 会话数据模型 ====================

enum class SessionStatus {
    ACTIVE,    // 活跃状态
    PAUSED,    // 暂停状态
    CLOSED     // 已关闭
}

data class Session(
    val id: String = UUID.randomUUID().toString(),
    val key: String,
    val title: String = "新会话",
    val status: SessionStatus = SessionStatus.ACTIVE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
    val summary: String? = null,
    val branchFrom: String? = null,
    val tags: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("key", key)
        put("title", title)
        put("status", status.name)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("messageCount", messageCount)
        if (summary != null) put("summary", summary)
        if (branchFrom != null) put("branchFrom", branchFrom)
        put("tags", JSONArray(tags))
        val metaObj = JSONObject()
        metadata.forEach { (k, v) -> metaObj.put(k, v) }
        put("metadata", metaObj)
    }

    companion object {
        fun fromJson(json: JSONObject): Session = Session(
            id = json.getString("id"),
            key = json.getString("key"),
            title = json.optString("title", "新会话"),
            status = SessionStatus.valueOf(json.optString("status", "ACTIVE")),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
            messageCount = json.optInt("messageCount", 0),
            summary = json.optString("summary", "").ifEmpty { null },
            branchFrom = json.optString("branchFrom", "").ifEmpty { null },
            tags = (0 until (json.optJSONArray("tags")?.length() ?: 0))
                .map { json.optJSONArray("tags")?.getString(it) ?: "" },
            metadata = json.optJSONObject("metadata")?.let { obj ->
                obj.keys().asSequence().associateWith { obj.getString(it) }
            } ?: emptyMap()
        )
    }
}

// ==================== 会话数据库 ====================

/**
 * SessionDB - 使用SharedPreferences+JSON持久化会话
 */
class SessionDB(context: Context) {
    private val prefs = context.getSharedPreferences("heartflow_sessions", Context.MODE_PRIVATE)
    private val sessionsKey = "sessions_list"

    /**
     * 保存单个会话
     */
    fun save(session: Session) {
        val sessions = loadAll().toMutableList()
        val index = sessions.indexOfFirst { it.id == session.id }
        if (index >= 0) {
            sessions[index] = session
        } else {
            sessions.add(session)
        }
        saveAll(sessions)
    }

    /**
     * 批量保存会话
     */
    fun saveAll(sessions: List<Session>) {
        val json = JSONArray()
        sessions.forEach { json.put(it.toJson()) }
        prefs.edit().putString(sessionsKey, json.toString()).apply()
    }

    /**
     * 加载所有会话
     */
    fun loadAll(): List<Session> {
        val json = prefs.getString(sessionsKey, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { Session.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 根据ID加载单个会话
     */
    fun loadById(id: String): Session? {
        return loadAll().firstOrNull { it.id == id }
    }

    /**
     * 根据键加载会话
     */
    fun loadByKey(key: String): Session? {
        return loadAll().firstOrNull { it.key == key }
    }

    /**
     * 删除单个会话
     */
    fun delete(id: String) {
        val sessions = loadAll().toMutableList()
        sessions.removeAll { it.id == id }
        saveAll(sessions)
    }

    /**
     * 清空所有会话
     */
    fun clear() {
        prefs.edit().remove(sessionsKey).apply()
    }

    /**
     * 获取会话总数
     */
    fun count(): Int = loadAll().size

    /**
     * 获取指定状态的会话数
     */
    fun countByStatus(status: SessionStatus): Int {
        return loadAll().count { it.status == status }
    }
}

// ==================== 会话管理器 ====================

/**
 * SessionManager - 会话的增删改查、分支、压缩
 */
class SessionManager(private val db: SessionDB) {

    /**
     * 创建新会话
     */
    fun create(key: String, title: String = "新会话"): Session {
        val session = Session(key = key, title = title)
        db.save(session)
        return session
    }

    /**
     * 根据ID获取会话
     */
    fun get(id: String): Session? = db.loadById(id)

    /**
     * 根据键获取会话
     */
    fun getByKey(key: String): Session? = db.loadByKey(key)

    /**
     * 获取所有会话
     */
    fun list(): List<Session> = db.loadAll()

    /**
     * 按状态过滤会话
     */
    fun listByStatus(status: SessionStatus): List<Session> {
        return db.loadAll().filter { it.status == status }
    }

    /**
     * 删除会话
     */
    fun delete(id: String) = db.delete(id)

    /**
     * 关闭会话（状态变为CLOSED）
     */
    fun close(id: String): Session? {
        val session = db.loadById(id) ?: return null
        val updated = session.copy(status = SessionStatus.CLOSED, updatedAt = System.currentTimeMillis())
        db.save(updated)
        return updated
    }

    /**
     * 暂停会话（状态变为PAUSED）
     */
    fun pause(id: String): Session? {
        val session = db.loadById(id) ?: return null
        val updated = session.copy(status = SessionStatus.PAUSED, updatedAt = System.currentTimeMillis())
        db.save(updated)
        return updated
    }

    /**
     * 恢复会话（状态变为ACTIVE）
     */
    fun resume(id: String): Session? {
        val session = db.loadById(id) ?: return null
        val updated = session.copy(status = SessionStatus.ACTIVE, updatedAt = System.currentTimeMillis())
        db.save(updated)
        return updated
    }

    /**
     * 更新消息计数
     */
    fun incrementMessageCount(id: String): Session? {
        val session = db.loadById(id) ?: return null
        val updated = session.copy(
            messageCount = session.messageCount + 1,
            updatedAt = System.currentTimeMillis()
        )
        db.save(updated)
        return updated
    }

    /**
     * 更新会话摘要
     */
    fun updateSummary(id: String, summary: String): Session? {
        val session = db.loadById(id) ?: return null
        val updated = session.copy(summary = summary, updatedAt = System.currentTimeMillis())
        db.save(updated)
        return updated
    }

    /**
     * 分支会话 - 从现有会话创建新分支
     */
    fun branch(parentId: String, newKey: String): Session? {
        val parent = db.loadById(parentId) ?: return null
        val branchSession = Session(
            key = newKey,
            title = "${parent.title} (分支)",
            branchFrom = parentId,
            tags = parent.tags
        )
        db.save(branchSession)
        return branchSession
    }

    /**
     * 压缩会话 - 合并消息摘要，释放空间
     * 返回压缩后的摘要
     */
    fun compress(id: String, summary: String): Session? {
        val session = db.loadById(id) ?: return null
        val updated = session.copy(
            summary = summary,
            messageCount = 0,
            updatedAt = System.currentTimeMillis()
        )
        db.save(updated)
        return updated
    }

    /**
     * 更新会话标题
     */
    fun updateTitle(id: String, title: String): Session? {
        val session = db.loadById(id) ?: return null
        val updated = session.copy(title = title, updatedAt = System.currentTimeMillis())
        db.save(updated)
        return updated
    }

    /**
     * 添加标签
     */
    fun addTag(id: String, tag: String): Session? {
        val session = db.loadById(id) ?: return null
        if (session.tags.contains(tag)) return session
        val updated = session.copy(
            tags = session.tags + tag,
            updatedAt = System.currentTimeMillis()
        )
        db.save(updated)
        return updated
    }

    /**
     * 移除标签
     */
    fun removeTag(id: String, tag: String): Session? {
        val session = db.loadById(id) ?: return null
        val updated = session.copy(
            tags = session.tags - tag,
            updatedAt = System.currentTimeMillis()
        )
        db.save(updated)
        return updated
    }

    /**
     * 设置元数据
     */
    fun setMetadata(id: String, key: String, value: String): Session? {
        val session = db.loadById(id) ?: return null
        val updated = session.copy(
            metadata = session.metadata + (key to value),
            updatedAt = System.currentTimeMillis()
        )
        db.save(updated)
        return updated
    }
}

// ==================== 会话搜索 ====================

/**
 * SessionSearch - 提供关键词搜索、滚动读取、批量浏览功能
 */
class SessionSearch(private val db: SessionDB) {

    /**
     * 按关键词搜索会话
     */
    fun search(query: String): List<SearchResult> {
        val q = query.lowercase()
        return db.loadAll().mapNotNull { session ->
            val titleMatch = session.title.lowercase().contains(q)
            val summaryMatch = session.summary?.lowercase()?.contains(q) ?: false
            val tagMatch = session.tags.any { it.lowercase().contains(q) }
            val keyMatch = session.key.lowercase().contains(q)
            if (titleMatch || summaryMatch || tagMatch || keyMatch) {
                val relevance = calculateRelevance(session, q)
                SearchResult(session = session, relevance = relevance)
            } else null
        }.sortedByDescending { it.relevance }
    }

    /**
     * 计算搜索相关性评分
     */
    private fun calculateRelevance(session: Session, query: String): Float {
        var score = 0f
        if (session.title.lowercase().contains(query)) score += 10f
        if (session.summary?.lowercase()?.contains(query) == true) score += 5f
        if (session.tags.any { it.lowercase().contains(query) }) score += 3f
        if (session.key.lowercase().contains(query)) score += 2f
        // 最近更新的会话权重更高
        val ageHours = (System.currentTimeMillis() - session.updatedAt) / (1000 * 60 * 60)
        score += maxOf(0f, 24f - ageHours) / 24f
        return score
    }

    /**
     * 滚动读取 - 分页获取会话列表
     * @param offset 起始位置
     * @param limit 每页数量
     * @param status 可选的状态过滤
     */
    fun scroll(
        offset: Int = 0,
        limit: Int = 20,
        status: SessionStatus? = null
    ): ScrollResult {
        var sessions = db.loadAll().sortedByDescending { it.updatedAt }
        if (status != null) {
            sessions = sessions.filter { it.status == status }
        }
        val total = sessions.size
        val page = sessions.drop(offset).take(limit)
        return ScrollResult(
            sessions = page,
            offset = offset,
            total = total,
            hasMore = offset + limit < total
        )
    }

    /**
     * 读取单个会话的详细信息
     */
    fun read(id: String): SessionDetail? {
        val session = db.loadById(id) ?: return null
        return SessionDetail(
            session = session,
            ageText = formatAge(session.createdAt),
            lastActiveText = formatAge(session.updatedAt)
        )
    }

    /**
     * 浏览 - 获取会话摘要概览
     */
    fun browse(): List<SessionOverview> {
        return db.loadAll().sortedByDescending { it.updatedAt }.map { session ->
            SessionOverview(
                id = session.id,
                key = session.key,
                title = session.title,
                status = session.status,
                messageCount = session.messageCount,
                preview = session.summary ?: session.title,
                updatedAt = session.updatedAt
            )
        }
    }

    /**
     * 按标签过滤
     */
    fun filterByTag(tag: String): List<Session> {
        return db.loadAll().filter { it.tags.contains(tag) }
    }

    /**
     * 按时间范围过滤
     */
    fun filterByTimeRange(startTime: Long, endTime: Long): List<Session> {
        return db.loadAll().filter {
            it.updatedAt in startTime..endTime
        }
    }

    private fun formatAge(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        val minutes = diff / (1000 * 60)
        val hours = diff / (1000 * 60 * 60)
        val days = diff / (1000 * 60 * 60 * 24)
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "${minutes}分钟前"
            hours < 24 -> "${hours}小时前"
            days < 7 -> "${days}天前"
            else -> "${days / 7}周前"
        }
    }
}

// ==================== 搜索结果数据类 ====================

data class SearchResult(
    val session: Session,
    val relevance: Float
)

data class ScrollResult(
    val sessions: List<Session>,
    val offset: Int,
    val total: Int,
    val hasMore: Boolean
)

data class SessionDetail(
    val session: Session,
    val ageText: String,
    val lastActiveText: String
)

data class SessionOverview(
    val id: String,
    val key: String,
    val title: String,
    val status: SessionStatus,
    val messageCount: Int,
    val preview: String,
    val updatedAt: Long
)

// ==================== 统一入口 ====================

/**
 * 会话管理统一入口 - 提供完整的会话管理功能
 */
object SessionManagerFactory {
    private var instance: SessionManager? = null
    private var search: SessionSearch? = null

    /**
     * 初始化（应用启动时调用一次）
     */
    fun init(context: Context) {
        val db = SessionDB(context)
        instance = SessionManager(db)
        search = SessionSearch(db)
    }

    /**
     * 获取会话管理器实例
     */
    fun getManager(): SessionManager {
        return instance ?: throw IllegalStateException("SessionManagerFactory未初始化，请先调用init()")
    }

    /**
     * 获取搜索器实例
     */
    fun getSearch(): SessionSearch {
        return search ?: throw IllegalStateException("SessionManagerFactory未初始化，请先调用init()")
    }

    /**
     * 获取或创建会话（按键）
     */
    fun getOrCreate(key: String, title: String = "新会话"): Session {
        val manager = getManager()
        return manager.getByKey(key) ?: manager.create(key, title)
    }
}
