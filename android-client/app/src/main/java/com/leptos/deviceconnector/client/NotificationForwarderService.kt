package com.leptos.deviceconnector.client

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

class NotificationForwarderService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: ""
        val body = extras.getString("android.text") ?: ""

        val config = getSharedPreferences("deviceconnector", MODE_PRIVATE)
        val host = config.getString("server_host", "") ?: ""
        val port = config.getInt("server_port", 14353)
        val authToken = config.getString("auth_token", "") ?: ""

        if (host.isEmpty()) {
            return
        }

        val message = buildNotifyMessage(title.toString(), body.toString())
        sendMessage(host, port, authToken, message)
    }

    private fun buildNotifyMessage(title: String, body: String): String {
        return "NOTIFY|${escapeField(title)}|${escapeField(body)}\n"
    }

    private fun escapeField(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("|", "\\|")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun sendMessage(host: String, port: Int, authToken: String, payload: String) {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), 5000)
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

            if (authToken.isNotEmpty()) {
                writer.write("AUTH|${escapeField(authToken)}\n")
                writer.flush()
                val response = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                if (response != "OK") {
                    return
                }
            }

            writer.write(payload)
            writer.flush()
        } catch (_: Exception) {
            // Ignore failures; avoid crashing notification service.
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }
}
