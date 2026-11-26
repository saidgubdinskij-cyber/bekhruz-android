package com.bekhruz.vpn

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val neon = Color.rgb(0, 245, 160)
    private val muted = Color.rgb(155, 165, 175)
    private var protocol = "WireGuard"
    private lateinit var status: TextView
    // Kept in memory only; never write VPN private keys to logs or plain preferences.
    private var importedConfig: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(5, 6, 8)
        window.navigationBarColor = Color.rgb(5, 6, 8)
        window.decorView.systemUiVisibility = 0
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
        wg.setOnClickListener { protocol = "WireGuard"; updateChoice() }
        awg.setOnClickListener { protocol = "AmneziaWG"; updateChoice() }
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
            startActivityForResult(pick, 7)
        }
        root.addView(import, full().apply { topMargin = dp(24) })

        status = label("No configuration selected").apply { gravity = Gravity.CENTER }
        root.addView(status, full().apply { topMargin = dp(12) })

        root.addView(TextView(this).apply {
            text = "VPN ENGINE NOT CONNECTED"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(255, 190, 80))
        }, full().apply { topMargin = dp(28) })

        root.addView(TextView(this).apply {
            text = "Configuration file import and basic WireGuard validation are available. The VPN tunnel engine and Android VPN permission flow still need to be integrated, so importing a file does not connect the VPN yet."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setLineSpacing(dp(3).toFloat(), 1f)
        }, full().apply { topMargin = dp(12) })

        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        root.addView(label("BLACK  •  NEON  •  ANDROID").apply { gravity = Gravity.CENTER }, full())
        setContentView(root)
    }

    private fun readDisplayName(uri: Uri): String {
        var name = "WireGuard configuration"
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val column = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && it.moveToFirst()) name = it.getString(column) ?: name
        }
        return name
    }

    private fun validateWireGuardConfig(text: String): String? {
        val hasInterface = Regex("(?im)^\\s*\\[Interface]\\s*$").containsMatchIn(text)
        val hasPeer = Regex("(?im)^\\s*\\[Peer]\\s*$").containsMatchIn(text)
        val hasPrivateKey = Regex("(?im)^\\s*PrivateKey\\s*=\\s*\\S+").containsMatchIn(text)
        val hasAddress = Regex("(?im)^\\s*Address\\s*=\\s*\\S+").containsMatchIn(text)
        val hasPublicKey = Regex("(?im)^\\s*PublicKey\\s*=\\s*\\S+").containsMatchIn(text)

        return when {
            !hasInterface -> "Missing [Interface] section"
            !hasPrivateKey -> "Missing Interface PrivateKey"
            !hasAddress -> "Missing Interface Address"
            !hasPeer -> "Missing [Peer] section"
            !hasPublicKey -> "Missing Peer PublicKey"
            else -> null
        }
    }

    private fun label(value: String) = TextView(this).apply {
        text = value; textSize = 12f; letterSpacing = .08f; setTextColor(muted)
    }
    private fun button(value: String) = Button(this).apply {
        text = value; textSize = 12f; isAllCaps = false
        setTextColor(neon); setBackgroundColor(Color.rgb(22, 28, 30))
    }
    private fun full() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        try {
            val configText = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                it.readText()
            } ?: throw IllegalStateException("Could not read the selected file")

            val validationError = validateWireGuardConfig(configText)
            if (validationError != null) {
                importedConfig = null
                status.text = "Invalid WireGuard configuration"
                Toast.makeText(this, validationError, Toast.LENGTH_LONG).show()
                return
            }

            importedConfig = configText
            val fileName = readDisplayName(uri)
            status.text = "Imported: $fileName"
            Toast.makeText(this, "WireGuard configuration imported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            importedConfig = null
            status.text = "Could not import configuration"
            Toast.makeText(this, "File read failed", Toast.LENGTH_LONG).show()
        }
    }
}
