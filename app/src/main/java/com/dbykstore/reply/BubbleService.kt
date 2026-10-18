package com.dbykstore.reply

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.concurrent.thread

/**
 * Floating bubble over WhatsApp. Tap → reads the visible chat → drafts a reply → "Insert" types it
 * into WhatsApp's message box. It never presses send.
 */
class BubbleService : AccessibilityService() {

    private val waPackages = setOf("com.whatsapp", "com.whatsapp.w4b")
    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var settings: Settings

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: View? = null

    // panel widgets
    private lateinit var chatLabel: TextView
    private lateinit var instr: EditText
    private lateinit var goBtn: Button
    private lateinit var status: TextView
    private lateinit var draft: EditText
    private lateinit var noteView: TextView
    private lateinit var lookupsView: TextView

    private var busy = false
    private var lastChat = ""
    private var lastWaPackage = "com.whatsapp"

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        settings = Settings(this)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        removeBubble(); removePanel()
        super.onDestroy()
    }

    // ---------- show the bubble only while WhatsApp is on screen ----------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        when {
            pkg in waPackages -> { lastWaPackage = pkg; showBubble() }
            pkg == packageName || isKeyboard(pkg) || pkg == "com.android.systemui" -> Unit
            else -> { removeBubble(); removePanel() }
        }
    }

    private fun isKeyboard(pkg: String): Boolean {
        if ("inputmethod" in pkg || "keyboard" in pkg || "honeyboard" in pkg) return true
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == pkg }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun showBubble() {
        if (bubble != null) return
        val size = dp(52)
        val tv = TextView(this).apply {
            text = "✦"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#16A34A")); setStroke(dp(2), Color.WHITE) }
            elevation = dp(6).toFloat()
        }
        val p = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = settings.prefs.getInt("bubbleX", resources.displayMetrics.widthPixels - size - dp(8))
            y = settings.prefs.getInt("bubbleY", resources.displayMetrics.heightPixels / 3)
        }

        // drag to move, tap to open
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) { p.x = startX + dx.toInt(); p.y = startY + dy.toInt(); wm.updateViewLayout(tv, p) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) settings.prefs.edit().putInt("bubbleX", p.x).putInt("bubbleY", p.y).apply()
                    else togglePanel()
                    true
                }
                else -> false
            }
        }
        try { wm.addView(tv, p); bubble = tv; bubbleParams = p } catch (_: Exception) {}
    }

    private fun removeBubble() {
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
    }

    // ---------- the reply panel ----------

    private fun togglePanel() {
        if (panel != null) { removePanel(); return }
        openPanel()
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(if (primary) Color.WHITE else Color.parseColor("#111827"))
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(if (primary) Color.parseColor("#16A34A") else Color.WHITE)
            setStroke(dp(1), if (primary) Color.parseColor("#16A34A") else Color.parseColor("#D1D5DB"))
        }
        setOnClickListener { onClick() }
    }

    private fun field(hint: String, lines: Int) = EditText(this).apply {
        this.hint = hint
        textSize = 14f
        setTextColor(Color.parseColor("#111827"))
        setHintTextColor(Color.parseColor("#9CA3AF"))
        background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.WHITE); setStroke(dp(1), Color.parseColor("#D1D5DB")) }
        setPadding(dp(10), dp(8), dp(10), dp(8))
        if (lines > 1) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = lines; maxLines = 10; gravity = Gravity.TOP
        } else {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_GO
        }
    }

    private fun openPanel() {
        val pad = dp(12)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat(), 0f, 0f, 0f, 0f)
                setColor(Color.parseColor("#F9FAFB"))
            }
            elevation = dp(12).toFloat()
        }
        val lp = { top: Int -> LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) } }

        // header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply { text = "D Byk Reply"; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor("#111827")); textSize = 15f })
        chatLabel = TextView(this).apply { setTextColor(Color.parseColor("#6B7280")); textSize = 13f; maxLines = 1; setPadding(dp(8), 0, 0, 0) }
        header.addView(chatLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply { text = "✕"; textSize = 20f; setTextColor(Color.parseColor("#6B7280")); setPadding(dp(12), 0, dp(4), 0); setOnClickListener { removePanel() } })
        root.addView(header)

        instr = field("Optional: how to reply (e.g. offer test ride Saturday, reply in Marathi)", 1)
        instr.setOnEditorActionListener { _, _, _ -> suggest(); true }
        root.addView(instr, lp(10))

        goBtn = button("Suggest reply", true) { suggest() }
        root.addView(goBtn, lp(8))

        status = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#6B7280")); visibility = View.GONE }
        root.addView(status, lp(6))

        draft = field("The draft appears here. Edit it before inserting.", 5)
        root.addView(draft, lp(8))

        noteView = TextView(this).apply {
            textSize = 12f; setTextColor(Color.parseColor("#92400E")); setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.parseColor("#FEF3C7")) }
            visibility = View.GONE
        }
        root.addView(noteView, lp(6))

        lookupsView = TextView(this).apply { textSize = 11f; setTextColor(Color.parseColor("#6B7280")); visibility = View.GONE }
        root.addView(lookupsView, lp(6))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Insert into chat", true) { insert() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        row.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        row.addView(button("Copy", false) { copy() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row, lp(8))
        root.addView(TextView(this).apply { text = "Never sends on its own — check it, then tap send in WhatsApp."; textSize = 11f; setTextColor(Color.parseColor("#9CA3AF")) }, lp(6))

        val scroll = ScrollView(this).apply { addView(root) }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        try { wm.addView(scroll, p); panel = scroll } catch (e: Exception) { toast("Couldn't open panel: ${e.message}"); return }

        // New chat since last time → fresh draft right away.
        val chat = readChat()
        chatLabel.text = chat?.first ?: ""
        if (chat != null && chat.first.isNotEmpty() && chat.first != lastChat) suggest()
    }

    private fun removePanel() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
    }

    private fun setStatus(msg: String, error: Boolean = false) {
        status.text = msg
        status.setTextColor(Color.parseColor(if (error) "#B91C1C" else "#6B7280"))
        status.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---------- read WhatsApp's screen ----------

    private fun waRoot(): AccessibilityNodeInfo? {
        try {
            for (w in windows) {
                val r = w.root ?: continue
                if (r.packageName?.toString() in waPackages) return r
            }
        } catch (_: Exception) {}
        val r = rootInActiveWindow
        return if (r?.packageName?.toString() in waPackages) r else null
    }

    /** Returns chat title + visible messages (oldest first), or null if no WhatsApp chat is open. */
    private fun readChat(): Pair<String, List<ChatMessage>>? {
        val root = waRoot() ?: return null
        val pkg = root.packageName.toString()
        val title = root.findAccessibilityNodeInfosByViewId("$pkg:id/conversation_contact_name").firstOrNull()?.text?.toString()?.trim().orEmpty()

        val nodes = root.findAccessibilityNodeInfosByViewId("$pkg:id/message_text") +
            root.findAccessibilityNodeInfosByViewId("$pkg:id/caption")
        val width = resources.displayMetrics.widthPixels
        val msgs = nodes.mapNotNull { n ->
            val text = n.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            val r = Rect(); n.getBoundsInScreen(r)
            // Our messages sit on the right, the customer's on the left.
            val outgoing = (width - r.right) < r.left
            Triple(r.top, outgoing, text)
        }.sortedBy { it.first }.map { ChatMessage(it.second, it.third) }

        if (title.isEmpty() && msgs.isEmpty()) return null
        return title to msgs
    }

    private fun suggest() {
        if (busy) return
        val chat = readChat()
        if (chat == null) { setStatus("Open a WhatsApp chat first.", true); return }
        val (title, msgs) = chat
        chatLabel.text = title
        if (msgs.isEmpty()) { setStatus("Couldn't read any messages on screen. Scroll so the customer's messages are visible and try again.", true); return }

        busy = true
        goBtn.isEnabled = false
        goBtn.text = "Thinking…"
        noteView.visibility = View.GONE
        setStatus("Reading ${msgs.size} messages…")
        val instruction = instr.text.toString()

        thread {
            try {
                val res = Engine(settings).suggest(title.ifEmpty { "this chat" }, msgs, instruction) { p -> main.post { if (panel != null) setStatus(p) } }
                main.post {
                    if (panel == null) return@post
                    draft.setText(res.reply)
                    lastChat = title
                    if (res.note.isNotEmpty()) { noteView.text = "Note: ${res.note}"; noteView.visibility = View.VISIBLE }
                    if (res.lookups.isNotEmpty()) {
                        lookupsView.text = res.lookups.joinToString("\n") { "• ${label(it.tool)}: ${it.summary}" }
                        lookupsView.visibility = View.VISIBLE
                    } else lookupsView.visibility = View.GONE
                    setStatus("")
                }
            } catch (e: Exception) {
                main.post { if (panel != null) setStatus(e.message ?: "Something went wrong", true) }
            } finally {
                main.post {
                    busy = false
                    if (panel != null) { goBtn.isEnabled = true; goBtn.text = if (draft.text.isNotEmpty()) "Regenerate" else "Suggest reply" }
                }
            }
        }
    }

    private fun label(tool: String) = when (tool) {
        "zoho_stock" -> "Zoho stock"; "browse_bikes" -> "Bikes"; "search_products" -> "Products"
        "get_order" -> "Order"; "find_customer" -> "Customer"; else -> tool
    }

    // ---------- put the draft into WhatsApp ----------

    private fun insert() {
        val text = draft.text.toString().trim()
        if (text.isEmpty()) return
        val current = readChat()?.first.orEmpty()
        if (lastChat.isNotEmpty() && current.isNotEmpty() && current != lastChat) {
            setStatus("This draft was for \"$lastChat\" but \"$current\" is open. Tap Regenerate, or Copy if you're sure.", true)
            return
        }
        val root = waRoot()
        val pkg = root?.packageName?.toString() ?: lastWaPackage
        val box = root?.findAccessibilityNodeInfosByViewId("$pkg:id/entry")?.firstOrNull()
        val ok = box?.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        ) == true
        if (ok) {
            removePanel()
            toast("Inserted — check it and tap send.")
        } else {
            copy()
            setStatus("Couldn't reach the message box, so I copied it. Long-press the box in WhatsApp and Paste.", true)
        }
    }

    private fun copy() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("D Byk reply", draft.text.toString()))
        setStatus("Copied.")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
