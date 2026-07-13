package com.khalibre.tools.devpanel.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

data class GitConfig(
    val base_branch: String = "main",
    val stack_remote: String = "origin",
    val default_reviewers: List<String> = emptyList(),
    val user_session: String = "",
    val can_merge: Boolean = false
)

data class JiraConfig(
    val base_url: String = "",
    val project_key: String = "",
    val email: String = "",
    val api_token: String = ""
)

/**
 * Per-project ticket behaviour config.
 * [doneStatusesByType]  — map of issueType name → list of statuses considered "done" for that type.
 *                         Used by the "Hide Done" JQL clause.
 * [excludedStatuses]    — statuses hidden from the Tickets > Status filter badges.
 * [excludedTypes]       — issue types hidden from Tickets > type / not-type filter badges.
 * [developmentTypes]    — issue types eligible to show the "Pick" action button on a ticket card.
 *                         If empty, every type is eligible (keeps prior behaviour for unconfigured projects).
 * [itemMode]            — "default" or "compact". Controls how a ticket card's status/transitions
 *                         are rendered — see TicketsPanel's card-building code.
 */
data class TicketConfig(
    val doneStatusesByType: Map<String, List<String>> = emptyMap(),
    val excludedStatuses: List<String> = emptyList(),
    val excludedTypes: List<String> = emptyList(),
    val developmentTypes: List<String> = emptyList(),
    val itemMode: String = "compact"
)

data class TelegramConfig(
    val chat_id: String = "",
    val bot_token: String = ""
)

data class CalendarificConfig(
    val api_key: String = ""
)

data class DevConfig(
    val git: GitConfig = GitConfig(),
    val jira: JiraConfig = JiraConfig(),
    val ticket: TicketConfig = TicketConfig(),
    val telegram: TelegramConfig = TelegramConfig(),
    val calendarific: CalendarificConfig = CalendarificConfig()
) {
    companion object {
        private val CONFIG_FILE =
            File(System.getProperty("user.home"), ".config/devtools/config.json")
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
