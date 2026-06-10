package com.khalibre.link2command.devpanel.config

import com.google.gson.Gson
import java.io.File

object AuthorCache {
    private val file =
        File(System.getProperty("user.home"), ".config/devtools/pr-authors-cache.json")
    private val gson = Gson()

    data class CachedAuthor(val login: String, val avatarUrl: String)
    private data class Cache(
        val repo: String,
        val authors: List<CachedAuthor>,
        val fetchedAt: String
    )

    fun load(repo: String): List<CachedAuthor> {
        return try {
            val cache = gson.fromJson(file.readText(), Cache::class.java)
            if (cache.repo == repo) cache.authors else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(repo: String, authors: List<CachedAuthor>) {
        try {
            file.parentFile.mkdirs()
            val cache = Cache(repo, authors, java.time.Instant.now().toString())
            file.writeText(gson.toJson(cache))
        } catch (e: Exception) {
            // ignore write failures
        }
    }
}
