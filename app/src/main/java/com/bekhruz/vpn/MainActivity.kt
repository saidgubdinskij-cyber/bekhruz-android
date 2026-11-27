package com.bekhruz.vpn

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.backend.TunnelActionHandler
import org.amnezia.awg.config.Config
import org.amnezia.awg.crypto.KeyPair
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
    private lateinit var speedStatus: TextView
    private val speedHandler = Handler(Looper.getMainLooper())
    private var lastRxBytes = TrafficStats.getTotalRxBytes()
    private var lastTxBytes = TrafficStats.getTotalTxBytes()
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
        override fun isIpv4ResolutionPreferred(): Boolean = true
        override fun isMetered(): Boolean = false
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
        if (intent?.action == ACTION_TILE_CONNECT) toggleConnection()
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
            lastRxBytes = TrafficStats.getTotalRxBytes()
            lastTxBytes = TrafficStats.getTotalTxBytes()
            speedHandler.removeCallbacks(speedRunnable)
            speedHandler.post(speedRunnable)
        }
    }

    override fun onPause() {
        speedHandler.removeCallbacks(speedRunnable)
        super.onPause()
    }

    private val speedRunnable = object : Runnable {
        override fun run() {
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            if (connected) {
                val down = if (rx >= lastRxBytes && lastRxBytes >= 0) rx - lastRxBytes else 0L
                val up = if (tx >= lastTxBytes && lastTxBytes >= 0) tx - lastTxBytes else 0L
                if (::speedStatus.isInitialized) {
                    speedStatus.text = "↓ " + formatSpeed(down) + "     ↑ " + formatSpeed(up)
                }
            } else if (::speedStatus.isInitialized) {
                speedStatus.text = "↓ 0 КБ/с     ↑ 0 КБ/с"
            }
            // Обновляем базовые значения и в отключённом состоянии,
            // чтобы после подключения не учитывать трафик до VPN.
            lastRxBytes = rx
            lastTxBytes = tx
            speedHandler.postDelayed(this, 1000)
        }
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        val kb = bytesPerSecond / 1024.0
        return if (kb >= 1024) String.format(java.util.Locale.US, "%.2f МБ/с", kb / 1024)
        else String.format(java.util.Locale.US, "%.0f КБ/с", kb)
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
            setBackgroundColor(bg)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.bekhruz_emblem)
            contentDescription = "Bek01"
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        header.addView(TextView(this).apply {
            text = "Bek01"
            textSize = 27f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "⋮"
            textSize = 28f
            setTextColor(neon)
            gravity = Gravity.CENTER
            setOnClickListener { showAppMenu() }
        }, LinearLayout.LayoutParams(dp(40), dp(44)))
        root.addView(header, full())

        root.addView(TextView(this).apply {
            text = "ТУННЕЛИ"
            textSize = 11f
            letterSpacing = .16f
            setTextColor(muted)
        }, full().apply { topMargin = dp(28); bottomMargin = dp(10) })

        val profileList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        profilesContainer = profileList
        root.addView(profileList, full())

        val addButton = Button(this).apply {
            text = "＋  Добавить туннель"
            textSize = 14f
            isAllCaps = false
            setTextColor(neon)
            background = rounded(panel, 14)
            setOnClickListener { showAddMenu() }
        }
        root.addView(addButton, full().apply { topMargin = dp(14) })

        val stateCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(panel, 16)
        }
        engineStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(warning)
            typeface = Typeface.DEFAULT_BOLD
        }
        stateCard.addView(engineStatus, full())
        status = TextView(this).apply {
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(7), 0, 0)
        }
        stateCard.addView(status, full())
        speedStatus = TextView(this).apply {
            text = "↓ 0 КБ/с     ↑ 0 КБ/с"
            textSize = 13f
            setTextColor(neon)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, 0)
        }
        stateCard.addView(speedStatus, full())
        root.addView(stateCard, full().apply { topMargin = dp(18) })


        connectionButton = Button(this).apply {
            textSize = 14f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            setOnClickListener { toggleConnection() }
        }
        root.addView(connectionButton, full().apply { topMargin = dp(12) })

        root.addView(TextView(this).apply {
            text = "Bek01 • WireGuard VPN"
            textSize = 10f
            gravity = Gravity.CENTER
            letterSpacing = .12f
            setTextColor(muted)
            setPadding(0, dp(18), 0, 0)
        }, full())
        setContentView(root)
        refreshProfilesUi()
        updateConnectionUi()
    }

    private var profilesContainer: LinearLayout? = null

    private fun profilesDirectory(): java.io.File =
        java.io.File(filesDir, "profiles").apply { if (!exists()) mkdirs() }

    private fun profileFiles(): List<java.io.File> =
        profilesDirectory().listFiles()?.filter { it.isFile && it.extension == "conf" }?.sortedBy { it.name.lowercase() } ?: emptyList()

    private fun selectedProfileFile(): java.io.File? {
        val name = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString("active_profile_file", null)
        return if (name.isNullOrBlank()) null else java.io.File(profilesDirectory(), name).takeIf { it.exists() }
    }

    private fun saveProfile(name: String, raw: String, warp: Boolean = false, reserved: String? = null) {
        val safe = name.trim().replace(Regex("[^A-Za-z0-9._ -]"), "_").take(48).ifBlank { "WireGuard" }
        val fileName = safe + "_" + System.currentTimeMillis() + ".conf"
        java.io.File(profilesDirectory(), fileName).writeText(raw, Charsets.UTF_8)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString("active_profile_file", fileName)
            .putString(KEY_CONFIG_NAME, safe)
            .putBoolean(KEY_IS_WARP, warp)
            .putString(KEY_WARP_RESERVED, reserved)
            .putString(KEY_PROTOCOL, if (warp) "WARP" else "WireGuard")
            .apply()
        openFileOutput(CONFIG_FILE, MODE_PRIVATE).use { it.write(raw.toByteArray(Charsets.UTF_8)) }
        isWarpConfig = warp
        warpReserved = reserved
        protocol = if (warp) "WARP" else "WireGuard"
    }

    private fun activateProfile(file: java.io.File) {
        if (connected || busy) {
            Toast.makeText(this, "Сначала отключите текущий туннель", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val raw = file.readText(Charsets.UTF_8)
            val parsed = Config.parse(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val warp = raw.contains("cloudflare", true) || raw.contains("162.159.") || file.name.contains("warp", true)
            val amnezia = Regex("(?im)^\\s*(S[1-4]|Jc|Jmin|Jmax|H[1-4]|I[1-5])\\s*=").containsMatchIn(raw)
            val reserved = prefs.getString(KEY_WARP_RESERVED, null)
            prefs.edit().putString("active_profile_file", file.name)
                .putString(KEY_CONFIG_NAME, file.nameWithoutExtension.substringBeforeLast("_"))
                .putBoolean(KEY_IS_WARP, warp)
                .putString(KEY_PROTOCOL, when { warp -> "WARP"; amnezia -> "AmneziaWG"; else -> "WireGuard" }).apply()
            openFileOutput(CONFIG_FILE, MODE_PRIVATE).use { it.write(raw.toByteArray(Charsets.UTF_8)) }
            importedConfig = parsed
            isWarpConfig = warp
            protocol = when { warp -> "WARP"; amnezia -> "AmneziaWG"; else -> "WireGuard" }
            warpReserved = if (warp) reserved else null
            updateProtocolButtons()
            refreshProfilesUi()
            updateConnectionUi()
            status.text = "Выбран туннель: ${savedConfigName()}"
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка профиля: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshProfilesUi() {
        val list = profilesContainer ?: return
        list.removeAllViews()
        val active = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString("active_profile_file", null)
        val files = profileFiles()
        if (files.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Нет туннелей. Нажмите «Добавить туннель» и импортируйте .conf"
                textSize = 13f
                setTextColor(muted)
                setPadding(dp(14), dp(18), dp(14), dp(18))
                background = rounded(panel, 14)
            }, full())
        }
        files.forEach { file ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(8), dp(10))
                background = rounded(if (file.name == active) Color.rgb(22, 43, 37) else panel, 14)
                setOnClickListener { activateProfile(file) }
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(TextView(this).apply {
                text = file.nameWithoutExtension.substringBeforeLast("_")
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            }, full())
            info.addView(TextView(this).apply {
                text = if (file.name == active) "Выбранный туннель" else "WireGuard • Нажмите для выбора"
                textSize = 10f
                setTextColor(if (file.name == active) neon else muted)
                setPadding(0, dp(4), 0, 0)
            }, full())
            card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(TextView(this).apply {
                text = if (file.name == active && connected) "●" else "○"
                textSize = 20f
                setTextColor(if (file.name == active && connected) neon else muted)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(30), dp(42)))
            card.addView(TextView(this).apply {
                text = "✎"
                textSize = 20f
                setTextColor(neon)
                gravity = Gravity.CENTER
                setOnClickListener { editProfile(file) }
            }, LinearLayout.LayoutParams(dp(34), dp(42)))
            card.addView(TextView(this).apply {
                text = "⋮"
                textSize = 22f
                setTextColor(muted)
                gravity = Gravity.CENTER
                setOnClickListener { showProfileMenu(file) }
            }, LinearLayout.LayoutParams(dp(30), dp(42)))
            list.addView(card, full().apply { bottomMargin = dp(8) })
        }
    }

    private fun editProfile(file: java.io.File) {
        if (connected) {
            Toast.makeText(this, "Отключите VPN перед редактированием", Toast.LENGTH_SHORT).show()
            return
        }
        val editor = android.widget.EditText(this).apply {
            setText(file.readText(Charsets.UTF_8))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            minLines = 12
            gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        android.app.AlertDialog.Builder(this).setTitle("Редактировать конфигурацию")
            .setView(editor).setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                try {
                    val raw = editor.text.toString().trim() + "\\n"
                    val parsed = Config.parse(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))
                    file.writeText(raw, Charsets.UTF_8)
                    if (file.name == getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString("active_profile_file", null)) {
                        openFileOutput(CONFIG_FILE, MODE_PRIVATE).use { it.write(raw.toByteArray(Charsets.UTF_8)) }
                        importedConfig = parsed
                    }
                    Toast.makeText(this, "Конфигурация сохранена", Toast.LENGTH_SHORT).show()
                    refreshProfilesUi()
                } catch (e: Exception) {
                    Toast.makeText(this, "Ошибка конфигурации: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    private fun renameProfile(file: java.io.File) {
        val field = android.widget.EditText(this).apply {
            setText(file.nameWithoutExtension.substringBeforeLast("_"))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        android.app.AlertDialog.Builder(this).setTitle("Переименовать туннель")
            .setView(field).setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val base = field.text.toString().trim().replace(Regex("[^A-Za-z0-9._ -]"), "_").take(48)
                if (base.isNotBlank()) {
                    val suffix = file.name.substringAfterLast("_").removeSuffix(".conf")
                    file.renameTo(java.io.File(profilesDirectory(), "${base}_${suffix}.conf"))
                    refreshProfilesUi()
                }
            }.show()
    }

    private fun showProfileMenu(file: java.io.File) {
        val options = arrayOf("Подключить / выбрать", "Переименовать", "Удалить")
        android.app.AlertDialog.Builder(this).setTitle(file.nameWithoutExtension.substringBeforeLast("_"))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { activateProfile(file); if (!connected) toggleConnection() }
                    1 -> renameProfile(file)
                    2 -> android.app.AlertDialog.Builder(this).setMessage("Удалить этот туннель?")
                        .setNegativeButton("Отмена", null).setPositiveButton("Удалить") { _, _ ->
                            if (file.name == getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString("active_profile_file", null)) {
                                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove("active_profile_file").apply()
                                importedConfig = null
                                openFileOutput(CONFIG_FILE, MODE_PRIVATE).use { it.write(ByteArray(0)) }
                            }
                            file.delete()
                            refreshProfilesUi()
                            updateConnectionUi()
                        }.show()
                }
            }.show()
    }

    private fun showAddMenu() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(pick, REQUEST_CONFIG)
    }

    private fun showAppMenu() {
        android.app.AlertDialog.Builder(this).setTitle("Bek01")
            .setItems(arrayOf("Импортировать конфигурацию", "О приложении")) { _, which ->
                when (which) {
                    0 -> showAddMenu()
                    1 -> android.app.AlertDialog.Builder(this).setMessage("Bek01 — VPN-клиент на базе WireGuard.").setPositiveButton("OK", null).show()
                }
            }.show()
    }

    private fun selectProtocol(value: String) {
        protocol = value
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_PROTOCOL, protocol).apply()
        updateProtocolButtons()
        if (value == "WARP" && isWarpConfig) {
            status.text = "Профиль WARP: ${savedConfigName()}"
        } else if (value == "AmneziaWG") {
            status.text = "Профиль AmneziaWG: ${savedConfigName()}"
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
                saveProfile("Cloudflare WARP", profile, true, reserved)
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
                    refreshProfilesUi()
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
                val activeBackend = backend ?: GoBackend(applicationContext, NoOpTunnelActionHandler()).also { backend = it }
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
        if (::speedStatus.isInitialized && !connected) {
            lastRxBytes = TrafficStats.getTotalRxBytes()
            lastTxBytes = TrafficStats.getTotalTxBytes()
            speedStatus.text = "↓ 0 КБ/с     ↑ 0 КБ/с"
        }
        connectionButton.text = when {
            busy -> "ПОДОЖДИТЕ…"
            connected -> "ОТКЛЮЧИТЬСЯ"
            else -> "ПОДКЛЮЧИТЬСЯ"
        }
        connectionButton.isEnabled = !busy
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
            val detectedAmnezia = Regex("(?im)^\\s*(S[1-4]|Jc|Jmin|Jmax|H[1-4]|I[1-5])\\s*=").containsMatchIn(raw)
            val parsed = Config.parse(ByteArrayInputStream(normalized.toByteArray(Charsets.UTF_8)))
            val displayName = readDisplayName(uri)
            saveProfile(displayName.substringBeforeLast('.'), normalized, detectedWarp, reserved)
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(KEY_CONFIG_NAME, displayName)
                .putString(KEY_PROTOCOL, when { detectedWarp -> "WARP"; detectedAmnezia -> "AmneziaWG"; else -> "WireGuard" })
                .putBoolean(KEY_IS_WARP, detectedWarp)
                .putString(KEY_WARP_RESERVED, reserved)
                .apply()
            importedConfig = parsed
            refreshProfilesUi()
            isWarpConfig = detectedWarp
            warpReserved = reserved
            protocol = when { detectedWarp -> "WARP"; detectedAmnezia -> "AmneziaWG"; else -> "WireGuard" }
            updateProtocolButtons()
            status.text = when { detectedWarp -> "Профиль WARP сохранён: $displayName"; detectedAmnezia -> "Профиль AmneziaWG сохранён: $displayName"; else -> "Сохранено: $displayName" }
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

    private class NoOpTunnelActionHandler : TunnelActionHandler {
        override fun runPreUp(scripts: MutableCollection<String>) {}
        override fun runPostUp(scripts: MutableCollection<String>) {}
        override fun runPreDown(scripts: MutableCollection<String>) {}
        override fun runPostDown(scripts: MutableCollection<String>) {}
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
