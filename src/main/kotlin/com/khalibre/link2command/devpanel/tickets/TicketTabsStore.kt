package com.khalibre.link2command.devpanel.tickets

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

/** A single Tickets sub-tab: just an id (stable, used for state dir + storage keys) and a display name. */
data class TicketTabConfig(
    val id: String,
    val name: String
)

data class TicketTabsState(
    val tabs: List<TicketTabConfig> = emptyList(),
    val selectedTabId: String = ""
)

/**
 * Persists the list of Tickets sub-tabs (and which one is selected) to `.git/cw/ticket-tabs.json`,
 * and owns the on-disk layout for each tab's own state (parent tickets / fix version / filters),
 * which lives under `.git/cw/tabs/<tabId>/`.
 */
object TicketTabsStore {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private fun stateFile(cwDir: File): File = File(cwDir, "ticket-tabs.json")

    /** Next letter name in the A, B, C, … Z, AA, AB, … sequence, skipping names already in use. */
    fun nextTabName(existing: List<TicketTabConfig>): String {
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

    fun load(cwDir: File): TicketTabsState {
        val f = stateFile(cwDir)
        if (!f.exists()) return defaultState()
        return try {
            val state = gson.fromJson(f.readText(), TicketTabsState::class.java)
            if (state == null || state.tabs.isEmpty()) defaultState() else state
        } catch (_: Exception) {
            defaultState()
        }
    }

    fun save(cwDir: File, state: TicketTabsState) {
        try {
            cwDir.mkdirs()
            stateFile(cwDir).writeText(gson.toJson(state))
        } catch (_: Exception) {
        }
    }

    private fun defaultState(): TicketTabsState {
        val id = newTabId()
        return TicketTabsState(tabs = listOf(TicketTabConfig(id, "A")), selectedTabId = id)
    }

    fun newTabId(): String = "tab-${System.currentTimeMillis()}-${(0..9999).random()}"

    /** Directory holding one tab's own ticket-list state (parent keys, fix version, filters). */
    fun tabStateDir(cwDir: File, tabId: String): File = File(cwDir, "tabs/$tabId")

    fun deleteTabState(cwDir: File, tabId: String) {
        tabStateDir(cwDir, tabId).deleteRecursively()
    }
}
