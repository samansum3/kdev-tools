package com.khalibre.tools.devpanel.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

data class GitConfig(
    val base_branch: String = "main",
    val default_reviewers: List<String> = emptyList(),
    val user_session: String = "",
    val can_merge: Boolean = false
)

data class JiraConfig(
    val base_url: String = "",
    val project_key: String = "",
    // Project key used to populate the Time Log tab's "Time code" dropdown — kept separate
    // from [project_key] since worklogs are typically tracked against a dedicated Jira
    // project (e.g. "TIME") rather than the main development project.
    val time_project_key: String = "",
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
        private const val CONFIG_FILENAME = "general-config.json"

        // Pre-relocation location. Only read once, as a one-time migration source, when a
        // project's .git/cw/general-config.json doesn't exist yet.
        private val LEGACY_CONFIG_FILE =
            File(System.getProperty("user.home"), ".config/devtools/config.json")

        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

        /**
         * Resolves the config file for [cwDir] (a project's `.git/cw` directory, from
         * [com.khalibre.tools.devpanel.common.ProjectPaths.cwDir]). Falls back to the legacy
         * home-directory location when [cwDir] is unavailable (e.g. no git repo detected).
         */
        fun configFile(cwDir: File?): File =
            if (cwDir != null) File(cwDir, CONFIG_FILENAME) else LEGACY_CONFIG_FILE

        fun load(cwDir: File?): DevConfig {
            val file = configFile(cwDir)
            if (file.exists()) {
                return try {
                    gson.fromJson(file.readText(), DevConfig::class.java) ?: DevConfig()
                } catch (e: Exception) {
                    DevConfig()
                }
            }
            // One-time migration: an old-style ~/.config/devtools/config.json exists but this
            // project hasn't got its own .git/cw/general-config.json yet — read the legacy file
            // and immediately persist it at the new per-project location so credentials aren't
            // lost by the relocation.
            if (cwDir != null && LEGACY_CONFIG_FILE.exists()) {
                return try {
                    val migrated =
                        gson.fromJson(LEGACY_CONFIG_FILE.readText(), DevConfig::class.java)
                            ?: DevConfig()
                    save(migrated, cwDir)
                    migrated
                } catch (e: Exception) {
                    DevConfig()
                }
            }
            return DevConfig()
        }

        fun save(config: DevConfig, cwDir: File?) {
            val file = configFile(cwDir)
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(config))
        }
    }
}
