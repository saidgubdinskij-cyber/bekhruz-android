package com.bekhruz.vpn

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.net.VpnService
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream

class MainActivity : Activity() {
    private val neon = Color.rgb(0, 245, 160)
    private val muted = Color.rgb(155, 165, 175)
    private var protocol = "WireGuard"
    private lateinit var status: TextView
    private lateinit var engineStatus: TextView
    private lateinit var connectionButton: Button
    private var importedConfig: Config? = null
    private var backend: GoBackend? = null
    private var connected = false
    private var busy = false

    private val tunnel = object : Tunnel {
        override fun getName(): String = "bekhruz"
        override fun onStateChange(newState: Tunnel.State) {
            runOnUiThread {
                connected = newState == Tunnel.State.UP
                busy = false
                updateConnectionUi()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(5, 6, 8)
        window.navigationBarColor = Color.rgb(5, 6, 8)
        protocol = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_PROTOCOL, "WireGuard") ?: "WireGuard"
        restoreSavedConfiguration()
        render()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(36), dp(24), dp(24))
            setBackgroundColor(Color.rgb(5, 6, 8))
        }
        root.addView(TextView(this).apply {
            text = "bekhruz"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(neon)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }, full())
        root.addView(TextView(this).apply {
            text = "PRIVATE CONNECTION"
            textSize = 11f
            letterSpacing = .22f
            gravity = Gravity.CENTER
            setTextColor(muted)
        }, full())

        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        root.addView(label("VPN PROTOCOL"), full())

        val choices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val wg = button("WireGuard")
        val awg = button("AmneziaWG")
        fun updateChoice() {
            listOf(wg to "WireGuard", awg to "AmneziaWG").forEach { (b, p) ->
                b.setTextColor(if (protocol == p) Color.BLACK else neon)
                b.setBackgroundColor(if (protocol == p) neon else Color.rgb(22, 28, 30))
            }
        }
        wg.setOnClickListener {
            protocol = "WireGuard"
            saveProtocol()
            updateChoice()
            updateConnectionUi()
        }
        awg.setOnClickListener {
            protocol = "AmneziaWG"
            saveProtocol()
            updateChoice()
            updateConnectionUi()
            if (importedConfig != null) {
                status.text = "AmneziaWG engine is not installed yet"
            }
        }
        choices.addView(wg, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
        choices.addView(awg, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        root.addView(choices, full())
        updateChoice()

        val import = button("IMPORT CONFIGURATION")
        import.setOnClickListener {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(pick, REQUEST_CONFIG)
        }
        root.addView(import, full().apply { topMargin = dp(24) })

        status = label(
            if (importedConfig != null) "Saved: ${savedConfigName()}" else "No configuration selected"
        ).apply { gravity = Gravity.CENTER }
        root.addView(status, full().apply { topMargin = dp(12) })

        engineStatus = TextView(this).apply {
            text = "VPN ENGINE READY"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(neon)
        }
        root.addView(engineStatus, full().apply { topMargin = dp(28) })

        connectionButton = button("CONNECT")
        connectionButton.setOnClickListener { toggleConnection() }
        root.addView(connectionButton, full().apply { topMargin = dp(20) })

        root.addView(TextView(this).apply {
            text = "Import a valid WireGuard .conf file, then tap CONNECT. Android will ask you to approve the VPN connection. Your configuration stays on this device."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setLineSpacing(dp(3).toFloat(), 1f)
        }, full().apply { topMargin = dp(16) })

        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        root.addView(label("BLACK  •  NEON  •  ANDROID").apply { gravity = Gravity.CENTER }, full())
        setContentView(root)
        updateConnectionUi()
    }

    private fun toggleConnection() {
        if (busy) return
        if (connected) {
            disconnectTunnel()
            return
        }
        if (protocol != "WireGuard") {
            status.text = "AmneziaWG support is not integrated yet"
            return
        }
        if (importedConfig == null) {
            status.text = "Import a WireGuard configuration first"
            return
        }

        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            startActivityForResult(permissionIntent, REQUEST_VPN_PERMISSION)
        } else {
            connectTunnel()
        }
    }

    private fun connectTunnel() {
        val config = importedConfig ?: return
        busy = true
        updateConnectionUi()
        engineStatus.text = "CONNECTING…"

        Thread {
            try {
                val activeBackend = backend ?: GoBackend(applicationContext).also { backend = it }
                activeBackend.setState(tunnel, Tunnel.State.UP, config)
                runOnUiThread {
                    connected = true
                    busy = false
                    engineStatus.text = "VPN CONNECTED"
                    status.text = "WireGuard tunnel is active"
                    updateConnectionUi()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    connected = false
                    busy = false
                    engineStatus.text = "CONNECTION FAILED"
                    status.text = e.message?.take(110) ?: "Could not start WireGuard"
                    updateConnectionUi()
                }
            }
        }.start()
    }

    private fun disconnectTunnel() {
        val activeBackend = backend
        if (activeBackend == null) {
            connected = false
            updateConnectionUi()
            return
        }
        busy = true
        updateConnectionUi()
        engineStatus.text = "DISCONNECTING…"
        Thread {
            try {
                activeBackend.setState(tunnel, Tunnel.State.DOWN, null)
                runOnUiThread {
                    connected = false
                    busy = false
                    engineStatus.text = "VPN DISCONNECTED"
                    status.text = "Tunnel stopped"
                    updateConnectionUi()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    engineStatus.text = "DISCONNECT FAILED"
                    status.text = e.message?.take(110) ?: "Could not stop tunnel"
                    updateConnectionUi()
                }
            }
        }.start()
    }

    private fun updateConnectionUi() {
        if (!::connectionButton.isInitialized) return
        connectionButton.text = when {
            busy -> "PLEASE WAIT…"
            connected -> "DISCONNECT"
            else -> "CONNECT"
        }
        connectionButton.isEnabled = !busy && (protocol == "WireGuard" || connected)
        if (::engineStatus.isInitialized) {
            engineStatus.text = when {
                busy -> "VPN WORKING…"
                connected -> "VPN CONNECTED"
                else -> "VPN ENGINE READY"
            }
            engineStatus.setTextColor(if (connected) neon else Color.rgb(255, 190, 80))
        }
    }

    private fun restoreSavedConfiguration() {
        try {
            val file = getFileStreamPath(CONFIG_FILE)
            if (!file.exists()) return
            val configText = openFileInput(CONFIG_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
            importedConfig = Config.parse(
                ByteArrayInputStream(configText.toByteArray(Charsets.UTF_8))
            )
        } catch (_: Exception) {
            importedConfig = null
        }
    }

    private fun saveProtocol() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_PROTOCOL, protocol)
            .apply()
    }

    private fun savedConfigName(): String =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_CONFIG_NAME, "WireGuard configuration") ?: "WireGuard configuration"

    private fun readDisplayName(uri: Uri): String {
        var name = "WireGuard configuration"
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val column = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && it.moveToFirst()) name = it.getString(column) ?: name
        }
        return name
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 12f
        letterSpacing = .08f
        setTextColor(muted)
    }

    private fun button(value: String) = Button(this).apply {
        text = value
        textSize = 12f
        isAllCaps = false
        setTextColor(neon)
        setBackgroundColor(Color.rgb(22, 28, 30))
    }

    private fun full() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return

        if (requestCode == REQUEST_VPN_PERMISSION) {
            connectTunnel()
            return
        }
        if (requestCode != REQUEST_CONFIG) return
        val uri = data?.data ?: return

        try {
            val configText = contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: throw IllegalStateException("Could not read selected file")

            val parsed = Config.parse(ByteArrayInputStream(configText.toByteArray(Charsets.UTF_8)))
            val displayName = readDisplayName(uri)
            // Keep the configuration in app-private storage so it survives app restarts and reboots.
            openFileOutput(CONFIG_FILE, MODE_PRIVATE).use { it.write(configText.toByteArray(Charsets.UTF_8)) }
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(KEY_CONFIG_NAME, displayName)
                .putString(KEY_PROTOCOL, protocol)
                .apply()
            importedConfig = parsed
            status.text = "Saved: $displayName"
            engineStatus.text = "CONFIGURATION SAVED"
            Toast.makeText(this, "Configuration saved on this device", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            importedConfig = null
            status.text = "Invalid config: ${e.message?.take(90) ?: "check .conf file"}"
            engineStatus.text = "CONFIGURATION ERROR"
            Toast.makeText(this, "Could not read this WireGuard config", Toast.LENGTH_LONG).show()
        }
        updateConnectionUi()
    }

    companion object {
        private const val REQUEST_CONFIG = 7
        private const val REQUEST_VPN_PERMISSION = 8
        private const val PREFS_NAME = "bekhruz_settings"
        private const val KEY_PROTOCOL = "selected_protocol"
        private const val KEY_CONFIG_NAME = "saved_config_name"
        private const val CONFIG_FILE = "saved_wireguard.conf"
    }
}
