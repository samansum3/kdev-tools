package com.khalibre.tools.devpanel.tickets.icons

import com.khalibre.tools.devpanel.config.DevConfig
import java.io.File
import java.net.URI

object IconUtils {
    fun fixIconUrl(url: String?, cwDir: File?): String? {
        if (url == null) return null

        val baseUrl = DevConfig.load(cwDir).jira.base_url

        val sourceUri = URI(url)
        val sourceOrigin = "${sourceUri.scheme}://${sourceUri.host}"

        return url.replace(sourceOrigin, baseUrl)
    }
}
