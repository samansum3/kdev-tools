package com.khalibre.link2command.devpanel.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

data class GitConfig(
    val base_branch: String = "main",
    val stack_remote: String = "origin",
    val default_reviewers: List<String> = emptyList(),
    val user_session: String = ""
)

data class JiraConfig(
    val base_url: String = "",
    val project_key: String = "",
    val email: String = "",
    val api_token: String = ""
)

data class DevConfig(
    val git: GitConfig = GitConfig(),
    val jira: JiraConfig = JiraConfig()
) {
    companion object {
        private val CONFIG_FILE = File(System.getProperty("user.home"), ".config/devtools/config.json")
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

        fun load(): DevConfig {
            if (!CONFIG_FILE.exists()) return DevConfig()
            return try {
                gson.fromJson(CONFIG_FILE.readText(), DevConfig::class.java) ?: DevConfig()
            } catch (e: Exception) {
                DevConfig()
            }
        }

        fun save(config: DevConfig) {
            CONFIG_FILE.parentFile.mkdirs()
            CONFIG_FILE.writeText(gson.toJson(config))
        }

        fun configFile(): File = CONFIG_FILE
    }
}
