package com.heartflow.app.browser

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 书签管理器 — 持久化书签存储
 *
 * 使用 SharedPreferences + JSON 序列化存储书签列表。
 * 最多保留 100 条书签，按添加时间倒序排列。
 *
 * 使用方式（与 [GeckoEngine] 一致的初始化和获取方式）：
 *   BookmarkManager.init(context)
 *   BookmarkManager.getInstance().addBookmark(url, title)
 */
class BookmarkManager private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 获取所有书签（最新在前） */
    fun getAllBookmarks(): List<Bookmark> {
        val json = prefs.getString(KEY_BOOKMARKS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<Bookmark>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    Bookmark(
                        url = obj.getString("url"),
                        title = obj.getString("title"),
                        addedAt = obj.optLong("addedAt", System.currentTimeMillis())
                    )
                )
            }
            list.sortedByDescending { it.addedAt } // 最新在前
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 添加书签（如果已存在则更新标题和时间） */
    fun addBookmark(url: String, title: String) {
        val list = getAllBookmarks().toMutableList()
        // 移除旧条目（如果同一 URL 已存在）
        list.removeAll { it.url == url }
        // 插入新条目（最新在前）
        list.add(0, Bookmark(url = url, title = title, addedAt = System.currentTimeMillis()))
        // 限制最大条数
        val trimmed = if (list.size > MAX_BOOKMARKS) list.take(MAX_BOOKMARKS) else list
        saveBookmarks(trimmed)
    }

    /** 移除书签 */
    fun removeBookmark(url: String) {
        val list = getAllBookmarks().toMutableList()
        val removed = list.removeAll { it.url == url }
        if (removed) saveBookmarks(list)
    }

    /** 快速检查 URL 是否已被收藏 */
    fun isBookmarked(url: String): Boolean {
        return getAllBookmarks().any { it.url == url }
    }

    /** 切换书签状态：未收藏则添加，已收藏则移除 */
    fun toggleBookmark(url: String, title: String): Boolean {
        return if (isBookmarked(url)) {
            removeBookmark(url)
            false // 当前状态：未收藏
        } else {
            addBookmark(url, title)
            true // 当前状态：已收藏
        }
    }

    /** 清除所有书签 */
    fun clearAll() {
        prefs.edit().remove(KEY_BOOKMARKS).apply()
    }

    /** 获取书签数量 */
    fun count(): Int = getAllBookmarks().size

    // ── 内部序列化 ──────────────────────────────────────────

    private fun saveBookmarks(bookmarks: List<Bookmark>) {
        val arr = JSONArray()
        for (b in bookmarks) {
            arr.put(
                JSONObject().apply {
                    put("url", b.url)
                    put("title", b.title)
                    put("addedAt", b.addedAt)
                }
            )
        }
        prefs.edit().putString(KEY_BOOKMARKS, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "heartbug_bookmarks"
        private const val KEY_BOOKMARKS = "bookmarks_json"
        private const val MAX_BOOKMARKS = 100

        @Volatile
        private var instance: BookmarkManager? = null
        private var appContext: Context? = null

        /**
         * 初始化书签管理器上下文
         */
        fun init(context: Context) {
            appContext = context.applicationContext
        }

        /**
         * 获取书签管理器实例
         */
        @JvmStatic
        fun getInstance(): BookmarkManager {
            val ctx = appContext
                ?: throw IllegalStateException(
                    "BookmarkManager 未初始化。请在 Application.onCreate 中调用 BookmarkManager.init(this)"
                )
            return instance ?: synchronized(this) {
                instance ?: BookmarkManager(ctx).also { instance = it }
            }
        }
    }
}

/**
 * 书签数据类
 */
data class Bookmark(
    val url: String,
    val title: String,
    val addedAt: Long = System.currentTimeMillis()
)
