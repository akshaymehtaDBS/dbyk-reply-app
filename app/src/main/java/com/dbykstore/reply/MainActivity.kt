package com.dbykstore.reply

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var s: Settings
    private val inputs = mutableMapOf<String, EditText>()
    private lateinit var modelSpinner: Spinner
    private lateinit var bubbleStatus: TextView
    private val models = listOf("claude-sonnet-5-5", "claude-haiku-5-5", "claude-opus-5-5")
    private val modelLabels = listOf("Claude Sonnet 5.5 (recommended)", "Claude Haiku 5.5 (faster, cheaper)", "Claude Opus 5.5 (most capable)")

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = Settings(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(40))
            setBackgroundColor(Color.parseColor("#F9FAFB"))
        }

        root.addView(text("D Byk Reply", 22f, bold = true))
        root.addView(text("Floating reply helper for WhatsApp. Settings stay on this phone only.", 13f, color = "#6B7280"))

        // --- Bubble ---
        val bubbleCard = card(root, "1. Turn on the bubble")
        bubbleStatus = text("", 14f)
        bubbleCard.addView(bubbleStatus)
        bubbleCard.addView(text("Settings → Accessibility → Installed apps → D Byk Reply bubble → On.\nIf it's greyed out: App info → ⋮ (top right) → Allow restricted settings, then try again.", 12f, color = "#6B7280"))
        bubbleCard.addView(btn("Open Accessibility settings") { startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) })
        bubbleCard.addView(btn("Open App info (for restricted settings)") {
            startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(android.net.Uri.parse("package:$packageName")))
        })

        // --- Claude ---
        val claude = card(root, "2. Claude")
        input(claude, "anthropicKey", "Anthropic API key", secret = true)
        claude.addView(text("Model", 13f, bold = true))
        modelSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modelLabels)
            setSelection(models.indexOf(s.model).coerceAtLeast(0))
        }
        claude.addView(modelSpinner)
        testRow(claude, "Save & test Claude") { Engine(s).testClaude() }

        // --- Shopify ---
        val shop = card(root, "3. Shopify (catalogue, prices, orders)")
        input(shop, "shopDomain", "Store domain (….myshopify.com)")
        input(shop, "shopifyClientId", "Client ID (Dev Dashboard app)")
        input(shop, "shopifyClientSecret", "Client secret", secret = true)
        input(shop, "shopifyApiVersion", "API version")
        input(shop, "siteUrl", "Public website (for product links)")
        testRow(shop, "Save & test Shopify") { Engine(s).testShopify() }

        // --- Zoho ---
        val zoho = card(root, "4. Zoho Books (real stock per store)")
        input(zoho, "zohoOrgId", "Organization ID")
        input(zoho, "zohoClientId", "Client ID (Self Client)")
        input(zoho, "zohoClientSecret", "Client secret", secret = true)
        input(zoho, "zohoRefreshToken", "Refresh token", secret = true)
        val code = EditText(this).apply { hint = "Or paste a grant code (scope ZohoBooks.settings.READ)"; styleField(this) }
        zoho.addView(code, lp(8))
        val zohoResult = text("", 13f)
        zoho.addView(btn("Generate refresh token from code") {
            save()
            zohoResult.text = "Generating…"; zohoResult.setTextColor(Color.parseColor("#374151"))
            val c = code.text.toString()
            thread {
                try {
                    val rt = Engine(s).exchangeZohoCode(c)
                    runOnUiThread { inputs["zohoRefreshToken"]?.setText(rt); code.setText(""); ok(zohoResult, "Refresh token saved. Now tap Save & test Zoho.") }
                } catch (e: Exception) { runOnUiThread { err(zohoResult, e.message) } }
            }
        })
        zoho.addView(zohoResult)
        testRow(zoho, "Save & test Zoho") { Engine(s).testZoho() }

        // --- Store info ---
        val info = card(root, "5. What Claude knows about the store")
        input(info, "businessBrief", "Business information", lines = 10)
        input(info, "styleNotes", "Reply style", lines = 5)

        root.addView(btn("Save", primary = true) { save(); toast("Saved") }, lp(16))

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        val on = isBubbleEnabled()
        bubbleStatus.text = if (on) "✓ Bubble is on — open a WhatsApp chat and tap ✦" else "✗ Bubble is off"
        bubbleStatus.setTextColor(Color.parseColor(if (on) "#15803D" else "#B91C1C"))
    }

    override fun onPause() { super.onPause(); save() }

    private fun isBubbleEnabled(): Boolean {
        val enabled = AndroidSettings.Secure.getString(contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, BubbleService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun save() {
        inputs.forEach { (k, v) -> s.set(k, v.text.toString()) }
        s.set("model", models[modelSpinner.selectedItemPosition.coerceAtLeast(0)])
        // fresh tokens after any change
        s.prefs.edit().remove("shopifyTokenCache").remove("zohoTokenCache").apply()
    }

    // ---------- tiny UI helpers ----------

    private fun lp(top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun text(t: String, size: Float, bold: Boolean = false, color: String = "#111827") = TextView(this).apply {
        text = t; textSize = size; setTextColor(Color.parseColor(color)); if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.WHITE); setStroke(dp(1), Color.parseColor("#E5E7EB")) }
        }
        c.addView(text(title, 16f, bold = true))
        parent.addView(c, lp(14))
        return c
    }

    private fun styleField(e: EditText) {
        e.textSize = 14f
        e.setTextColor(Color.parseColor("#111827"))
        e.background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.WHITE); setStroke(dp(1), Color.parseColor("#D1D5DB")) }
        e.setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    private fun input(parent: LinearLayout, key: String, label: String, secret: Boolean = false, lines: Int = 1) {
        parent.addView(text(label, 13f, bold = true), lp(6))
        val e = EditText(this).apply {
            styleField(this)
            setText(s.get(key))
            if (lines > 1) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = lines; maxLines = 30; gravity = Gravity.TOP
            } else {
                inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
                maxLines = 1
            }
        }
        inputs[key] = e
        parent.addView(e)
    }

    private fun btn(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false
        setTextColor(if (primary) Color.WHITE else Color.parseColor("#111827"))
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(if (primary) Color.parseColor("#16A34A") else Color.WHITE)
            setStroke(dp(1), if (primary) Color.parseColor("#16A34A") else Color.parseColor("#D1D5DB"))
        }
        setOnClickListener { onClick() }
        layoutParams = lp(8)
    }

    private fun testRow(parent: LinearLayout, label: String, test: () -> String) {
        val result = text("", 13f)
        parent.addView(btn(label) {
            save()
            result.text = "Testing…"; result.setTextColor(Color.parseColor("#374151"))
            thread {
                try { val m = test(); runOnUiThread { ok(result, m) } }
                catch (e: Exception) { runOnUiThread { err(result, e.message) } }
            }
        })
        parent.addView(result)
    }

    private fun ok(t: TextView, m: String) { t.text = m; t.setTextColor(Color.parseColor("#15803D")); t.visibility = View.VISIBLE }
    private fun err(t: TextView, m: String?) { t.text = m ?: "Failed"; t.setTextColor(Color.parseColor("#B91C1C")); t.visibility = View.VISIBLE }
    private fun toast(m: String) = android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()
}
