package com.bekhruz.vpn

import android.content.ComponentName
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.backend.TunnelActionHandler
import org.amnezia.awg.config.Config
import java.io.ByteArrayInputStream

class BekhruzTileService : TileService() {
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val currentlyConnected = prefs.getBoolean(KEY_CONNECTED, false)
        qsTile?.state = Tile.STATE_UNAVAILABLE
        qsTile?.updateTile()

        Thread {
            try {
                if (VpnService.prepare(this) != null) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this, "Сначала откройте VOLTIX и разрешите подключение VPN", Toast.LENGTH_LONG).show()
                        updateTileState()
                    }
                    return@Thread
                }

                val raw = openFileInput(CONFIG_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val config = Config.parse(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))
                val reserved = prefs.getString(KEY_WARP_RESERVED, null)
                val warp = prefs.getBoolean(KEY_IS_WARP, false)
                val tunnel = object : Tunnel {
                    override fun getName(): String =
                        if (warp && !reserved.isNullOrBlank()) "voltix@reserved=$reserved" else "voltix"
                    override fun isIpv4ResolutionPreferred(): Boolean = true
                    override fun isMetered(): Boolean = false
                    override fun onStateChange(newState: Tunnel.State) {
                        val active = newState == Tunnel.State.UP
                        prefs.edit().putBoolean(KEY_CONNECTED, active).apply()
                        requestListeningState(this@BekhruzTileService,
                            ComponentName(this@BekhruzTileService, BekhruzTileService::class.java))
                    }
                }
                val backend = GoBackend(applicationContext, NoOpTunnelActionHandler())
                if (currentlyConnected) {
                    backend.setState(tunnel, Tunnel.State.DOWN, null)
                    prefs.edit().putBoolean(KEY_CONNECTED, false).apply()
                } else {
                    backend.setState(tunnel, Tunnel.State.UP, config)
                    prefs.edit().putBoolean(KEY_CONNECTED, true).apply()
                }
            } catch (e: Exception) {
                prefs.edit().putBoolean(KEY_CONNECTED, false).apply()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, "Ошибка VPN: ${e.message?.take(100) ?: "не удалось переключить"}", Toast.LENGTH_LONG).show()
                    updateTileState()
                }
            } finally {
                requestListeningState(this, ComponentName(this, BekhruzTileService::class.java))
            }
        }.start()
    }

    private fun updateTileState() {
        qsTile?.let { tile ->
            tile.label = "VOLTIX"
            tile.state = if (prefs.getBoolean(KEY_CONNECTED, false)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    private class NoOpTunnelActionHandler : TunnelActionHandler {
        override fun runPreUp(scripts: MutableCollection<String>) {}
        override fun runPostUp(scripts: MutableCollection<String>) {}
        override fun runPreDown(scripts: MutableCollection<String>) {}
        override fun runPostDown(scripts: MutableCollection<String>) {}
    }

    companion object {
        private const val PREFS_NAME = "bekhruz_settings"
        private const val KEY_CONNECTED = "vpn_connected"
        private const val KEY_IS_WARP = "is_warp_profile"
        private const val KEY_WARP_RESERVED = "warp_reserved"
        private const val CONFIG_FILE = "saved_vpn.conf"

        @JvmStatic
        fun refresh(context: android.content.Context, connected: Boolean) {
            context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_CONNECTED, connected).apply()
            requestListeningState(context, ComponentName(context, BekhruzTileService::class.java))
        }
    }
}