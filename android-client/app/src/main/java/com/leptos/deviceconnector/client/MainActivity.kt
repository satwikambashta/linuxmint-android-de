package com.leptos.deviceconnector.client

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : Activity() {
    private lateinit var preferences: SharedPreferences
    private val filePickerRequestCode = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences("deviceconnector", MODE_PRIVATE)
        setContentView(R.layout.activity_main)

        val hostInput = findViewById<EditText>(R.id.host_input)
        val portInput = findViewById<EditText>(R.id.port_input)
        val authInput = findViewById<EditText>(R.id.auth_token_input)
        val secureCheckbox = findViewById<CheckBox>(R.id.secure_checkbox)
        val saveButton = findViewById<Button>(R.id.save_button)
        val openSettingsButton = findViewById<Button>(R.id.open_listener_button)
        val sendFileButton = findViewById<Button>(R.id.send_file_button)

        hostInput.setText(preferences.getString("server_host", ""))
        portInput.setText(preferences.getInt("server_port", 14353).toString())
        authInput.setText(preferences.getString("auth_token", ""))
        secureCheckbox.isChecked = preferences.getBoolean("secure_transfer", false)

        saveButton.setOnClickListener {
            val host = hostInput.text.toString().trim()
            val port = portInput.text.toString().toIntOrNull() ?: -1
            val authToken = authInput.text.toString().trim()
            val secure = secureCheckbox.isChecked

            if (host.isEmpty()) {
                showToast("Please enter the Linux server host")
                return@setOnClickListener
            }

            if (port < 1 || port > 65535) {
                showToast("Please enter a valid port number")
                return@setOnClickListener
            }

            if (secure && authToken.isEmpty()) {
                showToast("A non-empty auth token is required for encrypted transport")
                return@setOnClickListener
            }

            preferences.edit()
                .putString("server_host", host)
                .putInt("server_port", port)
                .putString("auth_token", authToken)
                .putBoolean("secure_transfer", secure)
                .apply()

            showToast("Settings saved. Enable Notification Access next.")
        }

        openSettingsButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }

        sendFileButton.setOnClickListener {
            val host = hostInput.text.toString().trim()
            val port = portInput.text.toString().toIntOrNull() ?: -1

            if (host.isEmpty()) {
                showToast("Please enter the Linux server host")
                return@setOnClickListener
            }

            if (port < 1 || port > 65535) {
                showToast("Please enter a valid port number")
                return@setOnClickListener
            }

            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, filePickerRequestCode)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != filePickerRequestCode || resultCode != RESULT_OK) {
            return
        }

        val uri = data?.data ?: return
        val host = preferences.getString("server_host", "") ?: ""
        val port = preferences.getInt("server_port", 14353)
        val authToken = preferences.getString("auth_token", "") ?: ""
        val secure = preferences.getBoolean("secure_transfer", false)

        if (host.isEmpty()) {
            showToast("Server host is required")
            return
        }

        if (secure && authToken.isEmpty()) {
            showToast("Encrypted transport requires an auth token")
            return
        }

        Thread {
            sendFileToServer(host, port, authToken, secure, uri)
        }.start()
    }

    private fun sendFileToServer(host: String, port: Int, authToken: String, secure: Boolean, uri: Uri) {
        try {
            val fileName = queryFileName(uri) ?: "uploaded_file"
            val fileBytes = readUriBytes(uri) ?: run {
                showToastOnUiThread("Unable to read selected file")
                return
            }
            val success = repeatSendFile(host, port, authToken, secure, fileName, fileBytes)
            if (success) {
                showToastOnUiThread("File sent successfully")
            } else {
                showToastOnUiThread("File transfer failed")
            }
        } catch (error: Exception) {
            showToastOnUiThread("File transfer error: ${error.message}")
        }
    }

    private fun repeatSendFile(host: String, port: Int, authToken: String, secure: Boolean, fileName: String, fileBytes: ByteArray): Boolean {
        repeat(3) { attempt ->
            if (sendFileOnce(host, port, authToken, secure, fileName, fileBytes)) {
                return true
            }
            if (attempt < 2) {
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                }
            }
        }
        return false
    }

    private fun sendFileOnce(host: String, port: Int, authToken: String, secure: Boolean, fileName: String, fileBytes: ByteArray): Boolean {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), 10000)
            val outputStream = socket.getOutputStream()
            val writer = BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8))
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

            val payload = if (secure) {
                val encodedFile = android.util.Base64.encodeToString(fileBytes, android.util.Base64.NO_WRAP)
                val line = "FILE|${CryptoUtils.escapeField(fileName)}|${fileBytes.size}|$encodedFile\n"
                CryptoUtils.encryptPayload(authToken, line)
            } else {
                val header = "FILE|${CryptoUtils.escapeField(fileName)}|${fileBytes.size}\n"
                writer.write(header)
                writer.flush()
                outputStream.write(fileBytes)
                outputStream.flush()
                null
            }

            if (payload != null) {
                writer.write(payload)
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

    private fun sendMessageToServer(host: String, port: Int, authToken: String, secure: Boolean, message: String): Boolean {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), 10000)
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

            val payload = if (secure) CryptoUtils.encryptPayload(authToken, "$message\n") else "$message\n"
            payload?.let {
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

    private fun readUriBytes(uri: Uri): ByteArray? {
        return try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (ex: IOException) {
            null
        }
    }

    private fun queryFileName(uri: Uri): String? {
        var name: String? = null
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    name = cursor.getString(index)
                }
            }
        }
        return name
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showToastOnUiThread(message: String) {
        runOnUiThread { showToast(message) }
    }
}
