package com.khalibre.tools.devpanel.tickets

import com.khalibre.tools.devpanel.config.DevConfig
import java.net.HttpURLConnection
import java.util.*

object JiraAuth {
    fun basicHeaderValue(): String {
        val cfg = DevConfig.load()
        val auth = Base64.getEncoder()
            .encodeToString("${cfg.jira.email}:${cfg.jira.api_token}".toByteArray())
        return "Basic $auth"
    }

    fun apply(conn: HttpURLConnection) {
        conn.setRequestProperty("Authorization", basicHeaderValue())
    }
}