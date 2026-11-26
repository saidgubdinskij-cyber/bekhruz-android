package com.bekhruz.vpn

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.wireguard.crypto.KeyPair
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Base64
import java.io.ByteArrayInputStream

class MainActivity : Activity() {
    private val bg = Color.rgb(5, 8, 9)
    private val panel = Color.rgb(17, 25, 25)
    private val neon = Color.rgb(0, 239, 155)
    private val muted = Color.rgb(150, 165, 164)
    private val warning = Color.rgb(255, 190, 90)
    private var protocol = "WireGuard"
    private lateinit var status: TextView
    private lateinit var engineStatus: TextView
    private lateinit var connectionButton: Button
    private lateinit var wireGuardButton: Button
    private lateinit var warpButton: Button
    private lateinit var amneziaButton: Button
    private var importedConfig: Config? = null
    private var backend: GoBackend? = null
    private var connected = false
    private var busy = false
    private var isWarpConfig = false
    private var warpReserved: String? = null

    private val tunnel = object : Tunnel {
        override fun getName(): String {
            return if (isWarpConfig && !warpReserved.isNullOrBlank()) "bekhruz@reserved=$warpReserved" else "bekhruz"
        }
        override fun onStateChange(newState: Tunnel.State) {
            runOnUiThread {
                connected = newState == Tunnel.State.UP
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_CONNECTED, connected).apply()
                BekhruzTileService.refresh(this@MainActivity, connected)
                busy = false
                updateConnectionUi()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        protocol = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_PROTOCOL, "WireGuard") ?: "WireGuard"
        restoreSavedConfiguration()
        connected = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_CONNECTED, false)
        render()
        when (intent?.action) {
            ACTION_TILE_CONNECT -> toggleConnection()
            else -> {
                // Launching Bekhruz from the Infinix One-Tap button is a connect shortcut.
                // One-Tap launch toggles the saved VPN state: connect if off, disconnect if on.
                if (importedConfig != null) {
                    connectionButton.post { toggleConnection() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_TILE_CONNECT) toggleConnection()
    }

    override fun onResume() {
        super.onResume()
        if (::connectionButton.isInitialized) {
            connected = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_CONNECTED, connected)
            updateConnectionUi()
        }
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(26), dp(22), dp(20))
            setBackgroundColor(bg)
        }

        root.addView(TextView(this).apply {
            text = "bekhruz"
            textSize = 36f
            gravity = Gravity.CENTER
            setTextColor(neon)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = .02f
        }, full())
        root.addView(TextView(this).apply {
            text = "ЛИЧНОЕ ПОДКЛЮЧЕНИЕ"
            textSize = 10f
            letterSpacing = .32f
            gravity = Gravity.CENTER
            setTextColor(muted)
        }, full())

        val topSpacer = View(this)
        root.addView(topSpacer, LinearLayout.LayoutParams(1, 0, 1f))

        val stateCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(20), dp(18), dp(20))
            background = rounded(panel, 22)
        }
        val statusIcon = TextView(this).apply {
            text = "●"
            textSize = 38f
            gravity = Gravity.CENTER
            setTextColor(neon)
        }
        stateCard.addView(statusIcon, full())
        engineStatus = TextView(this).apply {
            text = "VPN ОТКЛЮЧЁН"
            textSize = 14f
            letterSpacing = .12f
            gravity = Gravity.CENTER
            setTextColor(warning)
            typeface = Typeface.DEFAULT_BOLD
        }
        stateCard.addView(engineStatus, full().apply { topMargin = dp(8) })
        status = TextView(this).apply {
            text = if (importedConfig != null) "Сохранено: ${savedConfigName()}" else "Добавьте конфигурацию для начала"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(muted)
        }
        stateCard.addView(status, full().apply { topMargin = dp(8) })
        root.addView(stateCard, full().apply { topMargin = dp(34) })

        connectionButton = Button(this).apply {
            text = "ПОДКЛЮЧИТЬСЯ"
            textSize = 15f
            letterSpacing = .08f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            background = rounded(neon, 18)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { toggleConnection() }
        }
        root.addView(connectionButton, full().apply { topMargin = dp(18) })

        root.addView(TextView(this).apply {
            text = "ВЫБЕРИТЕ ПРОТОКОЛ VPN"
            textSize = 11f
            letterSpacing = .15f
            setTextColor(muted)
        }, full().apply { topMargin = dp(30) })

        val choices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        wireGuardButton = protocolButton("Вайргард")
        warpButton = protocolButton("WARP")
        amneziaButton = protocolButton("Амнезия")
        wireGuardButton.setOnClickListener { selectProtocol("WireGuard") }
        warpButton.setOnClickListener { selectProtocol("WARP") }
        amneziaButton.setOnClickListener {
            selectProtocol("AmneziaWG")
            status.text = "Поддержка AmneziaWG ещё не подключена"
        }
        choices.addView(wireGuardButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(5) })
        choices.addView(warpButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(5); rightMargin = dp(5) })
        choices.addView(amneziaButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(5) })
        root.addView(choices, full().apply { topMargin = dp(10) })
        updateProtocolButtons()

        val importButton = Button(this).apply {
            text = "＋   ДОБАВИТЬ КОНФИГУРАЦИЮ"
            textSize = 13f
            isAllCaps = false
            setTextColor(neon)
            background = rounded(panel, 16)
            setOnClickListener {
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(pick, REQUEST_CONFIG)
            }
        }
        root.addView(importButton, full().apply { topMargin = dp(14) })

        val generateWarpButton = Button(this).apply {
            text = "✦   СОЗДАТЬ WARP-ПРОФИЛЬ"
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.BLACK)
            background = rounded(neon, 16)
            setOnClickListener { generateWarpProfile() }
        }
        root.addView(generateWarpButton, full().apply { topMargin = dp(10) })

        root.addView(TextView(this).apply {
            text = "Ваша конфигурация сохранена на телефоне и загрузится автоматически при следующем запуске bekhruz."
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setLineSpacing(dp(3).toFloat(), 1f)
        }, full().apply { topMargin = dp(14) })

        val bottomSpacer = View(this)
        root.addView(bottomSpacer, LinearLayout.LayoutParams(1, 0, 1f))
        root.addView(TextView(this).apply {
            text = "ЗАЩИТА  •  ПРИВАТНОСТЬ  •  BEKHRUZ"
            textSize = 10f
            letterSpacing = .18f
            gravity = Gravity.CENTER
            setTextColor(muted)
        }, full())

        setContentView(root)
        updateConnectionUi()
    }

    private fun selectProtocol(value: String) {
        protocol = value
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_PROTOCOL, protocol).apply()
        updateProtocolButtons()
        if (value == "WARP" && isWarpConfig) {
            status.text = "Профиль WARP: ${savedConfigName()}"
        } else if (value == "AmneziaWG") {
            status.text = "Поддержка AmneziaWG ещё не подключена"
        } else if (importedConfig != null) {
            status.text = "Сохранено: ${savedConfigName()}"
        }
        updateConnectionUi()
    }

    private fun updateProtocolButtons() {
        if (!::wireGuardButton.isInitialized) return
        listOf(wireGuardButton to "WireGuard", warpButton to "WARP", amneziaButton to "AmneziaWG")
            .forEach { (button, value) ->
                val selected = protocol == value
                button.setTextColor(if (selected) Color.BLACK else neon)
                button.background = rounded(if (selected) neon else panel, 12)
            }
    }

    private fun toggleConnection() {
        if (busy) return
        if (connected) {
            disconnectTunnel()
            return
        }
        if (protocol == "WARP" && (!isWarpConfig || warpReserved.isNullOrBlank())) {
            status.text = "Для WARP нужен профиль с тремя Reserved-байтами. Создайте новый профиль или импортируйте WARP-конфигурацию."
            return
        }
        if (protocol == "AmneziaWG") {
            status.text = "Поддержка AmneziaWG ещё не подключена"
            return
        }
        if (importedConfig == null) {
            status.text = "Сначала добавьте файл конфигурации .conf"
            return
        }
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) startActivityForResult(permissionIntent, REQUEST_VPN_PERMISSION)
        else connectTunnel()
    }

    private fun generateWarpProfile() {
        if (busy) return
        busy = true
        status.text = "Создаю ключи и регистрирую устройство в Cloudflare…"
        updateConnectionUi()
        Thread {
            try {
                val keys = KeyPair()
                val publicKey = keys.publicKey.toBase64()
                val privateKey = keys.privateKey.toBase64()
                val tos = Instant.now().toString()
                val payload = JSONObject()
                    .put("key", publicKey)
                    .put("install_id", "")
                    .put("warp_enabled", true)
                    .put("tos", tos)
                    .put("type", "Android")
                    .put("locale", "ru_RU")
                val connection = (URL("https://api.cloudflareclient.com/v0a737/reg").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20000
                    readTimeout = 20000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "okhttp/3.12.1")
                }
                val responseCode: Int
                val responseText: String
                try {
                    connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    responseCode = connection.responseCode
                    val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                    responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                } finally {
                    connection.disconnect()
                }
                if (responseCode !in 200..299) throw IllegalStateException("Cloudflare: HTTP $responseCode")
                val response = JSONObject(responseText)
                val config = response.getJSONObject("config")
                val iface = config.getJSONObject("interface")
                val addresses = iface.getJSONObject("addresses")
                val peer = config.getJSONArray("peers").getJSONObject(0)
                val endpoint = peer.getJSONObject("endpoint").getString("host")
                val clientId = config.getString("client_id")
                val reservedBytes = Base64.getDecoder().decode(clientId)
                if (reservedBytes.size < 3) throw IllegalStateException("Cloudflare вернул некорректный client_id")
                val reserved = reservedBytes.take(3).joinToString(", ") { (it.toInt() and 0xff).toString() }
                val ipv4 = addresses.optString("v4")
                val ipv6 = addresses.optString("v6")
                if (ipv4.isBlank() || endpoint.isBlank()) throw IllegalStateException("В ответе Cloudflare нет адреса или сервера")
                val addressLine = buildList {
                    add("$ipv4/32")
                    if (ipv6.isNotBlank() && ipv6 != "null") add("$ipv6/128")
                }.joinToString(", ")
                val dns = "1.1.1.1, 1.0.0.1, 2606:4700:4700::1111, 2606:4700:4700::1001"
                val profile = """[Interface]
PrivateKey = $privateKey
Address = $addressLine
DNS = $dns
MTU = 1280

[Peer]
PublicKey = ${peer.getString("public_key")}
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = $endpoint
PersistentKeepalive = 25
"""
                val parsed = Config.parse(ByteArrayInputStream(profile.toByteArray(Charsets.UTF_8)))
                openFileOutput(CONFIG_FILE, MODE_PRIVATE).use { it.write(profile.toByteArray(Charsets.UTF_8)) }
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(KEY_CONFIG_NAME, "Cloudflare WARP")
                    .putString(KEY_PROTOCOL, "WARP")
                    .putBoolean(KEY_IS_WARP, true)
                    .putString(KEY_WARP_RESERVED, reserved)
                    .putString("warp_client_id", clientId)
                    .apply()
                runOnUiThread {
                    importedConfig = parsed
                    isWarpConfig = true
                    warpReserved = reserved
                    protocol = "WARP"
                    updateProtocolButtons()
                    busy = false
                    status.text = "WARP-профиль создан и сохранён. Reserved: $reserved"
                    Toast.makeText(this, "Профиль Cloudflare WARP сохранён", Toast.LENGTH_LONG).show()
                    updateConnectionUi()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    status.text = "Не удалось создать WARP: ${e.message?.take(100) ?: e.javaClass.simpleName}"
                    updateConnectionUi()
                }
            }
        }.start()
    }

    private fun connectTunnel() {
        val config = importedConfig ?: return
        busy = true
        updateConnectionUi()
        Thread {
            try {
                val activeBackend = backend ?: GoBackend(applicationContext).also { backend = it }
                activeBackend.setState(tunnel, Tunnel.State.UP, config)
                runOnUiThread {
                    connected = true
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_CONNECTED, true).apply()
                    BekhruzTileService.refresh(this@MainActivity, true)
                    busy = false
                    status.text = if (protocol == "WARP") {
                        "WARP-туннель запущен с Reserved=${warpReserved}. Проверяем связь…"
                    } else "Туннель WireGuard работает"
                    updateConnectionUi()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    connected = false
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_CONNECTED, false).apply()
                    BekhruzTileService.refresh(this@MainActivity, false)
                    busy = false
                    status.text = e.message?.take(120) ?: "Не удалось запустить VPN"
                    updateConnectionUi()
                }
            }
        }.start()
    }

    private fun disconnectTunnel() {
        val activeBackend = backend ?: run {
            connected = false
            updateConnectionUi()
            return
        }
        busy = true
        updateConnectionUi()
        Thread {
            try {
                activeBackend.setState(tunnel, Tunnel.State.DOWN, null)
                runOnUiThread {
                    connected = false
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_CONNECTED, false).apply()
                    BekhruzTileService.refresh(this@MainActivity, false)
                    busy = false
                    status.text = "VPN отключён"
                    updateConnectionUi()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    status.text = e.message?.take(120) ?: "Не удалось отключить VPN"
                    updateConnectionUi()
                }
            }
        }.start()
    }

    private fun updateConnectionUi() {
        if (!::connectionButton.isInitialized) return
        connectionButton.text = when {
            busy -> "ПОДОЖДИТЕ…"
            connected -> "ОТКЛЮЧИТЬСЯ"
            else -> "ПОДКЛЮЧИТЬСЯ"
        }
        connectionButton.isEnabled = !busy && (protocol != "AmneziaWG" || connected)
        connectionButton.background = rounded(if (connected) Color.rgb(255, 100, 90) else neon, 18)
        if (::engineStatus.isInitialized) {
            engineStatus.text = when {
                busy -> "VPN ПОДКЛЮЧАЕТСЯ"
                connected -> "VPN ПОДКЛЮЧЁН"
                else -> "VPN ОТКЛЮЧЁН"
            }
            engineStatus.setTextColor(if (connected) neon else warning)
        }
    }

    private fun restoreSavedConfiguration() {
        try {
            val file = getFileStreamPath(CONFIG_FILE)
            if (!file.exists()) return
            val text = openFileInput(CONFIG_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
            isWarpConfig = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_IS_WARP, false)
            warpReserved = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_WARP_RESERVED, null)
            importedConfig = Config.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
            if (isWarpConfig) protocol = "WARP"
        } catch (_: Exception) {
            importedConfig = null
        }
    }

    private fun savedConfigName(): String =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_CONFIG_NAME, "VPN configuration") ?: "VPN configuration"

    private fun readDisplayName(uri: Uri): String {
        var name = "VPN configuration"
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val column = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && it.moveToFirst()) name = it.getString(column) ?: name
        }
        return name
    }

    private fun protocolButton(value: String) = Button(this).apply {
        text = value
        textSize = 10f
        isAllCaps = false
        setTextColor(neon)
        background = rounded(panel, 12)
        minWidth = 0
        setPadding(dp(3), 0, dp(3), 0)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun full() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

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
            val raw = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?.removePrefix("\uFEFF")?.replace("\r\n", "\n")?.replace("\r", "\n")
                ?: throw IllegalStateException("Не удалось прочитать выбранный файл")
            val reservedMatch = Regex("(?im)^\\s*#?\\s*Reserved\\s*=\\s*([0-9\\s,]+)").find(raw)
            val reserved = reservedMatch?.groupValues?.get(1)?.trim()
            val normalized = raw.lines().filterNot {
                it.trim().matches(Regex("(?i)^#?\\s*Reserved\\s*=.*$"))
            }.joinToString("\n").trim() + "\n"
            val detectedWarp = raw.contains("cloudflare", ignoreCase = true) ||
                raw.contains("162.159.") || raw.contains("2606:4700:") || reservedMatch != null
            val parsed = Config.parse(ByteArrayInputStream(normalized.toByteArray(Charsets.UTF_8)))
            val displayName = readDisplayName(uri)
            openFileOutput(CONFIG_FILE, MODE_PRIVATE).use { it.write(normalized.toByteArray(Charsets.UTF_8)) }
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(KEY_CONFIG_NAME, displayName)
                .putString(KEY_PROTOCOL, if (detectedWarp) "WARP" else protocol)
                .putBoolean(KEY_IS_WARP, detectedWarp)
                .putString(KEY_WARP_RESERVED, reserved)
                .apply()
            importedConfig = parsed
            isWarpConfig = detectedWarp
            warpReserved = reserved
            if (detectedWarp) protocol = "WARP"
            updateProtocolButtons()
            status.text = if (detectedWarp) "Профиль WARP сохранён: $displayName" else "Сохранено: $displayName"
            Toast.makeText(this, "Конфигурация сохранена на устройстве", Toast.LENGTH_SHORT).show()
            if (detectedWarp && !reserved.isNullOrBlank()) {
                engineStatus.text = "ПРОФИЛЬ WARP • RESERVED НАЙДЕН"
                status.text = "Профиль WARP сохранён. Reserved: $reserved"
            }
        } catch (e: Exception) {
            val detail = e.message?.take(140)?.ifBlank { null }
                ?: e.javaClass.simpleName
            status.text = "Не удалось прочитать конфигурацию: $detail"
            Toast.makeText(this, "Файл не добавлен. Проверьте формат WireGuard .conf", Toast.LENGTH_LONG).show()
        }
        updateConnectionUi()
    }

    companion object {
        const val ACTION_TILE_CONNECT = "com.bekhruz.vpn.ACTION_TILE_CONNECT"
        private const val KEY_CONNECTED = "vpn_connected"
        private const val REQUEST_CONFIG = 7
        private const val REQUEST_VPN_PERMISSION = 8
        private const val PREFS_NAME = "bekhruz_settings"
        private const val KEY_PROTOCOL = "selected_protocol"
        private const val KEY_CONFIG_NAME = "saved_config_name"
        private const val KEY_IS_WARP = "is_warp_profile"
        private const val KEY_WARP_RESERVED = "warp_reserved"
        private const val CONFIG_FILE = "saved_vpn.conf"
    }
}
