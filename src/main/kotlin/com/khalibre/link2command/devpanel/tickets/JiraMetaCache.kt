package com.khalibre.link2command.devpanel.tickets

import com.intellij.openapi.application.ApplicationManager
import com.khalibre.link2command.devpanel.tickets.JiraMetaCache.addListener
import com.khalibre.link2command.devpanel.tickets.JiraMetaCache.forceReloadStatuses
import com.khalibre.link2command.devpanel.tickets.JiraMetaCache.forceReloadTypes
import com.khalibre.link2command.devpanel.tickets.JiraMetaCache.load
import com.khalibre.link2command.devpanel.tickets.JiraMetaCache.removeListener
import java.io.File
import javax.swing.SwingUtilities

/**
 * Singleton in-memory cache for Jira project statuses and issue types.
 *
 * Any component can:
 *   - call [load] to populate from disk cache (or Jira if absent)
 *   - call [forceReloadStatuses] / [forceReloadTypes] to refresh from Jira
 *   - call [addListener] to be notified whenever the data changes
 *   - call [removeListener] to unregister
 *
 * All listener callbacks are delivered on the EDT.
 */
object JiraMetaCache {

    data class State(
        val statuses: List<String> = emptyList(),
        val types: List<JiraMetaService.IssueTypeInfo> = emptyList(),
        val cwDir: File? = null
    )

    @Volatile
    private var state = State()
    private val listeners = mutableListOf<(State) -> Unit>()

    fun current(): State = state

    fun addListener(listener: (State) -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: (State) -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    /** Loads from disk cache if not already loaded (or if cwDir changed). No-op when already populated. */
    fun load(cwDir: File) {
        if (state.statuses.isNotEmpty() && state.cwDir == cwDir) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val statuses = JiraMetaService.loadStatuses(cwDir)
            val types = JiraMetaService.loadTypes(cwDir)
            updateState(State(statuses, types, cwDir))
        }
    }

    /** Deletes status cache file, re-fetches from Jira, notifies listeners. */
    fun forceReloadStatuses(cwDir: File) {
        ApplicationManager.getApplication().executeOnPooledThread {
            JiraMetaService.statusCacheFile(cwDir).delete()
            val statuses = JiraMetaService.fetchAndCacheStatuses(cwDir)
            updateState(state.copy(statuses = statuses, cwDir = cwDir))
        }
    }

    /** Deletes types cache file, re-fetches from Jira, notifies listeners. */
    fun forceReloadTypes(cwDir: File) {
        ApplicationManager.getApplication().executeOnPooledThread {
            JiraMetaService.typesCacheFile(cwDir).delete()
            val types = JiraMetaService.fetchAndCacheTypes(cwDir)
            updateState(state.copy(types = types, cwDir = cwDir))
        }
    }

    /** Notifies listeners with fresh state (re-reads cache files). Called after save so exclusions are re-applied. */
    fun notifyConfigChanged(cwDir: File) {
        // Re-read from disk cache (files unchanged, but exclusion config has changed)
        // Listeners rebuild their badge rows filtering against the new DevConfig exclusions.
        val current = state
        if (current.statuses.isNotEmpty() || current.types.isNotEmpty()) {
            // Data already loaded — just broadcast so listeners re-read DevConfig exclusions
            notifyListeners(current)
        } else {
            // Nothing loaded yet — do a full load
            load(cwDir)
        }
    }

    private fun updateState(newState: State) {
        state = newState
        notifyListeners(newState)
    }

    private fun notifyListeners(s: State) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        SwingUtilities.invokeLater { snapshot.forEach { it(s) } }
    }
}
