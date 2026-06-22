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
        val secure = config.getBoolean("secure_transfer", false)

        if (host.isEmpty()) {
            return
        }

        if (secure && authToken.isEmpty()) {
            return
        }

        val payload = "NOTIFY|${CryptoUtils.escapeField(title.toString())}|${CryptoUtils.escapeField(body.toString())}" 

        Thread { sendMessageWithRetry(host, port, authToken, secure, payload) }.start()
    }

    private fun sendMessageWithRetry(host: String, port: Int, authToken: String, secure: Boolean, payload: String) {
        repeat(3) { attempt ->
            if (sendMessage(host, port, authToken, secure, payload)) {
                return
            }
            if (attempt < 2) {
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    private fun sendMessage(host: String, port: Int, authToken: String, secure: Boolean, payload: String): Boolean {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), 5000)
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)

            if (authToken.isNotEmpty()) {
                val authLine = if (secure) {
                    CryptoUtils.encryptPayload(authToken, "AUTH|${CryptoUtils.escapeField(authToken)}")
                } else {
                    "AUTH|${CryptoUtils.escapeField(authToken)}\n"
                }
                authLine?.let {
                    writer.write(it)
                    writer.flush()
                }

                val authResponse = reader.readLine() ?: return false
                val authResult = if (secure) CryptoUtils.decryptPayload(authToken, authResponse) else authResponse
                if (authResult != "OK") {
                    return false
                }
            }

            val commandLine = if (secure) {
                CryptoUtils.encryptPayload(authToken, "$payload\n")
            } else {
                "$payload\n"
            }

            commandLine?.let {
                writer.write(it)
                writer.flush()
            }

            val response = reader.readLine() ?: return false
            val decodedResponse = if (secure) CryptoUtils.decryptPayload(authToken, response) else response
            decodedResponse == "OK"
        } catch (_: Exception) {
            false
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }
}
