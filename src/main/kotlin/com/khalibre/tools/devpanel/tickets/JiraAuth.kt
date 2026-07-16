package com.khalibre.tools.devpanel.tickets

import com.khalibre.tools.devpanel.config.DevConfig
import java.io.File
import java.net.HttpURLConnection
import java.util.*

object JiraAuth {
    fun basicHeaderValue(cwDir: File?): String {
        val cfg = DevConfig.load(cwDir)
        val auth = Base64.getEncoder()
            .encodeToString("${cfg.jira.email}:${cfg.jira.api_token}".toByteArray())
        return "Basic $auth"
    }

    fun apply(conn: HttpURLConnection, cwDir: File?) {
        conn.setRequestProperty("Authorization", basicHeaderValue(cwDir))
    }
}
