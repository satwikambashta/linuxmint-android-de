package com.leptos.deviceconnector.client

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var preferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences("deviceconnector", MODE_PRIVATE)
        setContentView(R.layout.activity_main)

        val hostInput = findViewById<EditText>(R.id.host_input)
        val portInput = findViewById<EditText>(R.id.port_input)
        val authInput = findViewById<EditText>(R.id.auth_token_input)
        val saveButton = findViewById<Button>(R.id.save_button)
        val openSettingsButton = findViewById<Button>(R.id.open_listener_button)

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
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
