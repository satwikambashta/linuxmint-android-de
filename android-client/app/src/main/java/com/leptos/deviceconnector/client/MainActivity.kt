package com.leptos.deviceconnector.client

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
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
        val saveButton = findViewById<Button>(R.id.save_button)
        val openSettingsButton = findViewById<Button>(R.id.open_listener_button)
        val sendFileButton = findViewById<Button>(R.id.send_file_button)

        hostInput.setText(preferences.getString("server_host", ""))
        portInput.setText(preferences.getInt("server_port", 14353).toString())
        authInput.setText(preferences.getString("auth_token", ""))

        saveButton.setOnClickListener {
            val host = hostInput.text.toString().trim()
            val port = portInput.text.toString().toIntOrNull() ?: -1
            val authToken = authInput.text.toString().trim()

            if (host.isEmpty()) {
                showToast("Please enter the Linux server host")
                return@setOnClickListener
            }

            if (port < 1 || port > 65535) {
                showToast("Please enter a valid port number")
                return@setOnClickListener
            }

            preferences.edit()
                .putString("server_host", host)
                .putInt("server_port", port)
                .putString("auth_token", authToken)
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

        if (host.isEmpty()) {
            showToast("Server host is required")
            return
        }

        Thread {
            sendFileToServer(host, port, authToken, uri)
        }.start()
    }

    private fun sendFileToServer(host: String, port: Int, authToken: String, uri: Uri) {
        try {
            val fileName = queryFileName(uri) ?: "uploaded_file"
            val fileSize = queryFileSize(uri)
            val socket = Socket()

            socket.connect(InetSocketAddress(host, port), 10000)
            val outputStream = socket.getOutputStream()
            val writer = BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8))
            val inputStream = socket.getInputStream().bufferedReader(Charsets.UTF_8)

            if (authToken.isNotEmpty()) {
                writer.write("AUTH|${escapeField(authToken)}\n")
                writer.flush()

                val response = inputStream.readLine()
                if (response != "OK") {
                    showToastOnUiThread("Auth failed or server did not accept auth")
                    socket.close()
                    return
                }
            }

            if (fileSize != null && fileSize >= 0) {
                writer.write("FILE|${escapeField(fileName)}|${fileSize}\n")
                writer.flush()
                sendUriBytes(uri, outputStream)
            } else {
                val fileBytes = readUriBytes(uri)
                if (fileBytes == null) {
                    showToastOnUiThread("Unable to read selected file")
                    socket.close()
                    return
                }
                writer.write("FILE|${escapeField(fileName)}|${fileBytes.size}\n")
                writer.flush()
                outputStream.write(fileBytes)
            }

            outputStream.flush()
            val response = inputStream.readLine()
            if (response == "OK") {
                showToastOnUiThread("File sent successfully")
            } else {
                showToastOnUiThread("File transfer failed: $response")
            }

            socket.close()
        } catch (error: Exception) {
            showToastOnUiThread("File transfer error: ${error.message}")
        }
    }

    private fun sendUriBytes(uri: Uri, outputStream: java.io.OutputStream) {
        contentResolver.openInputStream(uri)?.use { input ->
            input.copyTo(outputStream)
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

    private fun queryFileSize(uri: Uri): Long? {
        var size: Long? = null
        val projection = arrayOf(OpenableColumns.SIZE)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0) {
                    val fileSize = cursor.getLong(index)
                    if (fileSize >= 0) {
                        size = fileSize
                    }
                }
            }
        }
        return size
    }

    private fun escapeField(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("|", "\\|")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showToastOnUiThread(message: String) {
        runOnUiThread { showToast(message) }
    }
}
