package com.khalibre.tools.devpanel.config

import com.google.gson.Gson
import java.io.File

object AuthorCache {
    private const val FILENAME = "pr-authors-cache.json"

    // Pre-relocation location. Only read once, as a one-time migration source, when a
    // project's .git/cw/pr-authors-cache.json doesn't exist yet.
    private val LEGACY_FILE =
        File(System.getProperty("user.home"), ".config/devtools/pr-authors-cache.json")

    private val gson = Gson()

    data class CachedAuthor(val login: String, val avatarUrl: String)
    private data class Cache(
        val repo: String,
        val authors: List<CachedAuthor>,
        val fetchedAt: String
    )

    private fun file(cwDir: File?): File =
        if (cwDir != null) File(cwDir, FILENAME) else LEGACY_FILE

    fun load(repo: String, cwDir: File?): List<CachedAuthor> {
        val f = file(cwDir)
        if (f.exists()) {
            return try {
                val cache = gson.fromJson(f.readText(), Cache::class.java)
                if (cache.repo == repo) cache.authors else emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
        // One-time migration from the legacy home-directory cache file.
        if (cwDir != null && LEGACY_FILE.exists()) {
            return try {
                val cache = gson.fromJson(LEGACY_FILE.readText(), Cache::class.java)
                if (cache.repo == repo) {
                    save(repo, cache.authors, cwDir)
                    cache.authors
                } else emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
        return emptyList()
    }

    fun save(repo: String, authors: List<CachedAuthor>, cwDir: File?) {
        try {
            val f = file(cwDir)
            f.parentFile?.mkdirs()
            val cache = Cache(repo, authors, java.time.Instant.now().toString())
            f.writeText(gson.toJson(cache))
        } catch (e: Exception) {
            // ignore write failures
        }
    }
}
