package com.slackoff.app.support

import android.content.Context
import com.slackoff.app.model.NovelChapterBody
import com.slackoff.app.model.NovelChapterList
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 小说目录与章节正文的持久化缓存，存放在应用私有文件目录。 */
object NovelChapterCache {
    private const val ROOT_DIR = "novel-chapters"
    private const val CATALOG_FILE = "catalog.json"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    private var root: File? = null

    fun init(context: Context) {
        if (root != null) return
        root = File(context.applicationContext.filesDir, ROOT_DIR).apply { mkdirs() }
    }

    suspend fun catalog(source: String, bookId: String): NovelChapterList? = withContext(Dispatchers.IO) {
        read(File(bookDirectory(source, bookId), CATALOG_FILE))
    }

    suspend fun putCatalog(source: String, bookId: String, catalog: NovelChapterList) = withContext(Dispatchers.IO) {
        write(File(bookDirectory(source, bookId), CATALOG_FILE), json.encodeToString(catalog))
    }

    suspend fun chapter(source: String, bookId: String, chapterId: String): NovelChapterBody? =
        withContext(Dispatchers.IO) {
            read(chapterFile(source, bookId, chapterId))
        }

    suspend fun putChapter(source: String, bookId: String, body: NovelChapterBody) = withContext(Dispatchers.IO) {
        write(chapterFile(source, bookId, body.chapterId), json.encodeToString(body))
    }

    suspend fun cachedChapterCount(source: String, bookId: String): Int = withContext(Dispatchers.IO) {
        val directory = File(bookDirectory(source, bookId), "bodies")
        directory.listFiles()?.count { it.isFile && it.extension == "json" } ?: 0
    }

    private inline fun <reified T> read(file: File): T? {
        if (!file.isFile) return null
        return try {
            json.decodeFromString<T>(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    private fun write(file: File, content: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(content)
        if (!temporary.renameTo(file)) {
            file.writeText(content)
            temporary.delete()
        }
    }

    private fun chapterFile(source: String, bookId: String, chapterId: String): File =
        File(File(bookDirectory(source, bookId), "bodies"), "${digest(chapterId)}.json")

    private fun bookDirectory(source: String, bookId: String): File {
        val base = checkNotNull(root) { "NovelChapterCache.init must be called first" }
        return File(base, digest("$source:$bookId"))
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
