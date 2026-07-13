package com.khalibre.tools.devpanel.tickets.icons

import com.khalibre.tools.devpanel.config.DevConfig
import java.net.URI

object IconUtils {
    val jiraConfig = DevConfig.load().jira
    val baseUrl = jiraConfig.base_url

    fun fixIconUrl(url: String?): String? {
        if (url == null) return null

        val sourceUri = URI(url)
        val sourceOrigin = "${sourceUri.scheme}://${sourceUri.host}"

        return url.replace(sourceOrigin, baseUrl)
    }
}
