package com.khalibre.link2command.devpanel.pr

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

/** A single PR Tools sub-tab: just an id (stable, used for state dir + storage keys) and a display name. */
data class PrTabConfig(
    val id: String,
    val name: String
)

data class PrTabsState(
    val tabs: List<PrTabConfig> = emptyList(),
    val selectedTabId: String = ""
)

/**
 * Persists the list of PR Tools sub-tabs (and which one is selected) to `.git/cw/pr-tabs.json`,
 * and owns the on-disk layout for each tab's own state (base branch / author selections),
 * which lives under `.git/cw/tabs-pr/<tabId>/`.
 *
 * Mirrors [com.khalibre.link2command.devpanel.tickets.TicketTabsStore] — kept as its own
 * object (rather than shared) since the two tab strips persist unrelated state shapes.
 */
object PrTabsStore {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private fun stateFile(cwDir: File): File = File(cwDir, "pr-tabs.json")

    /** Next letter name in the A, B, C, … Z, AA, AB, … sequence, skipping names already in use. */
    fun nextTabName(existing: List<PrTabConfig>): String {
        val used = existing.map { it.name }.toSet()
        var n = 0
        while (true) {
            val name = letterName(n)
            if (name !in used) return name
            n++
        }
    }

    private fun letterName(index: Int): String {
        var i = index
        val sb = StringBuilder()
        do {
            sb.insert(0, ('A' + (i % 26)))
            i = i / 26 - 1
        } while (i >= 0)
        return sb.toString()
    }

    fun load(cwDir: File): PrTabsState {
        val f = stateFile(cwDir)
        if (!f.exists()) return defaultState()
        return try {
            val state = gson.fromJson(f.readText(), PrTabsState::class.java)
            if (state == null || state.tabs.isEmpty()) defaultState() else state
        } catch (_: Exception) {
            defaultState()
        }
    }

    fun save(cwDir: File, state: PrTabsState) {
        try {
            cwDir.mkdirs()
            stateFile(cwDir).writeText(gson.toJson(state))
        } catch (_: Exception) {
        }
    }

    private fun defaultState(): PrTabsState {
        val id = newTabId()
        return PrTabsState(tabs = listOf(PrTabConfig(id, "A")), selectedTabId = id)
    }

    fun newTabId(): String = "tab-${System.currentTimeMillis()}-${(0..9999).random()}"

    /** Directory holding one tab's own PR list state (base branch, author). */
    fun tabStateDir(cwDir: File, tabId: String): File = File(cwDir, "tabs-pr/$tabId")

    fun deleteTabState(cwDir: File, tabId: String) {
        tabStateDir(cwDir, tabId).deleteRecursively()
    }
}
