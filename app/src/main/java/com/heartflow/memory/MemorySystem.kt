package com.heartflow.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MemorySystem(private val context: Context) {
    private val workingMemory = mutableMapOf<String, String>()
    private val recentInteractions = mutableListOf<MemoryItem>()
    private val episodicStore = mutableListOf<MemoryItem>()
    private val coreStore = mutableListOf<MemoryItem>()

    private val episodicFile: File get() = File(context.filesDir, "memory/episodic.json")
    private val coreFile: File get() = File(context.filesDir, "memory/core.json")
    private val userFactsPrefs = context.getSharedPreferences("heartflow_facts", Context.MODE_PRIVATE)

    companion object {
        private const val COMPRESSION_THRESHOLD = 0.85
        private const val MAX_EPISODIC_SIZE = 300
        private const val MAX_WORKING_SIZE = 100
    }

    init {
        File(context.filesDir, "memory").mkdirs()
        loadEpisodic()
        loadCore()
    }

    fun store(content: String, layer: MemoryLayer, tags: List<String> = emptyList(),
              importance: Int = 1, emotion: String? = null, source: String? = null): MemoryItem {
        val item = MemoryItem(
            content = content, layer = layer, tags = tags,
            importance = importance, emotion = emotion, source = source
        )
        when (layer) {
            MemoryLayer.WORKING -> {
                recentInteractions.add(item)
                if (recentInteractions.size > MAX_WORKING_SIZE) {
                    compressWorkingMemory()
                }
            }
            MemoryLayer.EPISODIC -> {
                episodicStore.add(item)
                if (episodicStore.size > MAX_EPISODIC_SIZE) {
                    compressEpisodicMemory()
                }
                saveEpisodic()
            }
            MemoryLayer.CORE -> {
                coreStore.add(item)
                saveCore()
            }
        }
        return item
    }

    private fun compressWorkingMemory() {
        val threshold = (recentInteractions.size * COMPRESSION_THRESHOLD).toInt()
        recentInteractions.sortByDescending { it.importance }
        while (recentInteractions.size > threshold) {
            recentInteractions.removeLast()
        }
    }

    private fun compressEpisodicMemory() {
        val threshold = (episodicStore.size * COMPRESSION_THRESHOLD).toInt()
        episodicStore.sortByDescending { it.importance * 10 + it.accessCount }
        while (episodicStore.size > threshold) {
            episodicStore.removeLast()
        }
        saveEpisodic()
    }

    fun remember(key: String, value: String, source: String? = null) {
        workingMemory[key] = value
        userFactsPrefs.edit().putString("fact_$key", value).apply()
    }

    fun recall(key: String): String? {
        workingMemory[key]?.let { return it }
        return userFactsPrefs.getString("fact_$key", null)
    }

    fun query(layer: MemoryLayer? = null, tags: List<String>? = null,
              limit: Int = 10, minImportance: Int = 0): List<MemoryItem> {
        val results = when (layer) {
            MemoryLayer.WORKING -> recentInteractions.toList()
            MemoryLayer.EPISODIC -> episodicStore
            MemoryLayer.CORE -> coreStore
            null -> recentInteractions + episodicStore + coreStore
        }
        return results.filter { item ->
            (tags == null || tags.isEmpty() || item.tags.any { it in tags }) &&
            item.importance >= minImportance
        }.sortedByDescending { it.timestamp }.take(limit)
    }

    fun queryRecent(tags: List<String>? = null, limit: Int = 20): List<MemoryItem> {
        return query(tags = tags, limit = limit)
    }

    fun getImportantMemories(limit: Int = 10): List<MemoryItem> {
        return (episodicStore + coreStore)
            .sortedByDescending { it.importance * 10 + it.accessCount }
            .take(limit)
    }


    fun getContextString(tags: List<String>? = null, ignoreEmptyTags: Boolean = false): String {
        // 如果没有提供 tags 或 ignoreEmptyTags=true，返回所有层级的记忆
        val recentMemories = if (tags.isNullOrEmpty() && ignoreEmptyTags) {
            // 返回所有最近记忆（不受 tags 限制）
            recentInteractions.takeLast(15).reversed()
        } else {
            queryRecent(tags = tags, limit = 15)
        }
        val importantMemories = getImportantMemories(limit = 5)

        val sb = StringBuilder()
        if (recentMemories.isNotEmpty()) {
            sb.appendLine("【近期记忆】")
            recentMemories.forEach { sb.appendLine("- ${it.content}") }
        }
        if (importantMemories.isNotEmpty()) {
            sb.appendLine("【重要记忆】")
            importantMemories.forEach { sb.appendLine("- [${it.importance}] ${it.content}") }
        }

        val rememberedFacts = userFactsPrefs.all.filterKeys { it.startsWith("fact_") }
        if (rememberedFacts.isNotEmpty()) {
            sb.appendLine("【记住的信息】")
            rememberedFacts.forEach { (key, value) ->
                sb.appendLine("- ${key.removePrefix("fact_")}: $value")
            }
        }
        return sb.toString()
    }

    fun getStats(): MemoryStats = MemoryStats(
        workingCount = recentInteractions.size,
        episodicCount = episodicStore.size,
        coreCount = coreStore.size,
        lastAccessTime = (episodicStore + coreStore).maxOfOrNull { it.timestamp } ?: 0,
        totalMemories = recentInteractions.size + episodicStore.size + coreStore.size
    )

    /** 获取所有层级的全部记忆项，按时间降序排列 */
    fun getAllItems(): List<MemoryItem> {
        return (recentInteractions + episodicStore + coreStore)
            .sortedByDescending { it.timestamp }
    }

    fun clearWorking() { workingMemory.clear(); recentInteractions.clear() }

    fun clear() {
        workingMemory.clear()
        recentInteractions.clear()
        episodicStore.clear()
        coreStore.clear()
        saveEpisodic()
        saveCore()
        // 清除所有文件
        episodicFile.delete()
        coreFile.delete()
        userFactsPrefs.edit().clear().apply()
    }

    fun forget(id: String) {
        episodicStore.removeAll { it.id == id }
        coreStore.removeAll { it.id == id }
        saveEpisodic(); saveCore()
    }

    private fun loadEpisodic() {
        if (!episodicFile.exists()) return
        try {
            val arr = JSONArray(episodicFile.readText())
            for (i in 0 until arr.length()) {
                episodicStore.add(MemoryItem.fromJson(arr.getJSONObject(i)))
            }
        } catch (_: Exception) {}
    }

    private fun saveEpisodic() {
        episodicFile.writeText(JSONArray(episodicStore.map { it.toJson() }).toString())
    }

    private fun loadCore() {
        if (!coreFile.exists()) return
        try {
            val arr = JSONArray(coreFile.readText())
            for (i in 0 until arr.length()) {
                coreStore.add(MemoryItem.fromJson(arr.getJSONObject(i)))
            }
        } catch (_: Exception) {}
    }

    private fun saveCore() {
        coreFile.writeText(JSONArray(coreStore.map { it.toJson() }).toString())
    }
}