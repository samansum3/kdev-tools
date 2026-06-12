package com.khalibre.link2command.devpanel.tickets

import com.khalibre.link2command.devpanel.config.DevConfig
import java.net.HttpURLConnection
import java.util.*

object JiraAuth {
    fun apply(conn: HttpURLConnection) {
        val cfg = DevConfig.load()

        val auth = Base64.getEncoder()
            .encodeToString("${cfg.jira.email}:${cfg.jira.api_token}".toByteArray())

        conn.setRequestProperty("Authorization", "Basic $auth")
    }
}