package com.bekhruz.vpn

import android.content.ComponentName
import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream

class BekhruzTileService : TileService() {
    private val prefs by lazy { getSharedPreferences("bekhruz_settings", MODE_PRIVATE) }

    private val tunnel = object : Tunnel {
        override fun getName(): String = "bekhruz"
        override fun onStateChange(newState: Tunnel.State) {
            val isUp = newState == Tunnel.State.UP
            prefs.edit().putBoolean("vpn_connected", isUp).apply()
            refresh(this@BekhruzTileService, isUp)
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isConnected = prefs.getBoolean("vpn_connected", false)
        val selectedProtocol = prefs.getString("selected_protocol", "WireGuard")
        if (!isConnected && selectedProtocol == "WARP") {
            val launch = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivityAndCollapse(launch)
            return
        }
        if (!isConnected && VpnService.prepare(this) != null) {
            val launch = Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_TILE_CONNECT
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivityAndCollapse(launch)
            return
        }

        val configFile = getFileStreamPath("saved_vpn.conf")
        if (!isConnected && !configFile.exists()) {
            val launch = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivityAndCollapse(launch)
            return
        }

        Thread {
            try {
                val backend = GoBackend(applicationContext)
                if (isConnected) {
                    backend.setState(tunnel, Tunnel.State.DOWN, null)
                    prefs.edit().putBoolean("vpn_connected", false).apply()
                    refresh(this, false)
                } else {
                    val configText = openFileInput("saved_vpn.conf").bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val config = Config.parse(ByteArrayInputStream(configText.toByteArray(Charsets.UTF_8)))
                    backend.setState(tunnel, Tunnel.State.UP, config)
                    prefs.edit().putBoolean("vpn_connected", true).apply()
                    refresh(this, true)
                }
            } catch (_: Exception) {
                prefs.edit().putBoolean("vpn_connected", false).apply()
                refresh(this, false)
            }
        }.start()
    }

    private fun updateTileState() {
        qsTile?.let { tile ->
            val isConnected = prefs.getBoolean("vpn_connected", false)
            tile.label = "bekhruz"
            tile.state = if (isConnected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    companion object {
        @JvmStatic
        fun refresh(context: android.content.Context, connected: Boolean) {
            context.getSharedPreferences("bekhruz_settings", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("vpn_connected", connected).apply()
            requestListeningState(context, ComponentName(context, BekhruzTileService::class.java))
        }
    }
}
