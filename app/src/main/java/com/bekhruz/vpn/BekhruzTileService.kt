package com.bekhruz.vpn

import android.content.ComponentName
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class BekhruzTileService : TileService() {
    private val prefs by lazy { getSharedPreferences("bekhruz_settings", MODE_PRIVATE) }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val launch = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_TILE_CONNECT
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivityAndCollapse(launch)
    }

    private fun updateTileState() {
        qsTile?.let { tile ->
            val isConnected = prefs.getBoolean("vpn_connected", false)
            tile.label = "ZENIX"
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
