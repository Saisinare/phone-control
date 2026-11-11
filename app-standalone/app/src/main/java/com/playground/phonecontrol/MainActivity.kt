package com.playground.phonecontrol

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

private val INK = Color.parseColor("#15141F")
private val MUTED = Color.parseColor("#8B8A9B")
private val LINE = Color.parseColor("#E6E4F0")
private val PURPLE = Color.parseColor("#6C5CE7")
private val PURPLE_LIGHT = Color.parseColor("#8F82FF")
private val PURPLE_SOFT = Color.parseColor("#ECE9FF")
private val MINT = Color.parseColor("#12A383")
private val MINT_SOFT = Color.parseColor("#DCF5EE")
private val PINK = Color.parseColor("#D1489B")
private val PINK_SOFT = Color.parseColor("#FCE5F2")
private val AMBER = Color.parseColor("#C98A12")
private val AMBER_SOFT = Color.parseColor("#FFF2D9")
private val RED = Color.parseColor("#D64545")
private val RED_SOFT = Color.parseColor("#FDE7E7")
private val GREY_SOFT = Color.parseColor("#EFEEF5")
private val NAV = Color.parseColor("#16151D")
private val NAV_ITEM = Color.parseColor("#2B2A35")
private val RIPPLE = Color.parseColor("#226C5CE7")
private val SHADOW = Color.parseColor("#556C5CE7")

/** The one screen, in three tabs: Control (status, server, address), Activity (log) and Safety (protected apps). */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1500)
        }
    }
    private var updating = false
    private var shownRunning: Boolean? = null
    private var logKey = ""
    private var safetyKey = ""

    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private lateinit var bigTitle: TextView
    private lateinit var headerDot: View
    private lateinit var pages: List<LinearLayout>
    private lateinit var navItems: List<LinearLayout>
    private lateinit var statusCard: LinearLayout
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var serverSwitch: Switch
    private lateinit var accessValue: TextView
    private lateinit var wifiValue: TextView
    private lateinit var protectedValue: TextView
    private lateinit var activityValue: TextView
    private lateinit var address: TextView
    private lateinit var logRows: LinearLayout
    private lateinit var wordsText: TextView
    private lateinit var appsText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(120))
            clipToPadding = false
        }
        column.addView(header(), lp(MATCH_PARENT, WRAP_CONTENT))
        bigTitle = label("", 30f, INK, light)
        column.addView(bigTitle, lp(MATCH_PARENT, WRAP_CONTENT) { topMargin = dp(20); bottomMargin = dp(14) })
        pages = listOf(controlPage(), activityPage(), safetyPage())
        pages.forEach { column.addView(it, lp(MATCH_PARENT, WRAP_CONTENT)) }

        val root = FrameLayout(this)
        root.addView(
            ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                addView(column)
            },
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )
        root.addView(
            nav(),
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = dp(20) },
        )
        setContentView(root)
        show(0)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        // Bring the server back after an update or restart; Android only allows this from the foreground.
        if (Config.serverOn && !ServerService.running) {
            try {
                ServerService.start(this)
            } catch (_: Exception) {
            }
        }
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    // --------------------------------------------------------------- pages

    private fun header(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(circle(R.drawable.ic_stat, INK, Color.WHITE, 46), lp(dp(46), dp(46)))
        addView(label("Phone Control", 17f, INK, medium).apply { gravity = Gravity.CENTER }, lp(0, WRAP_CONTENT, 1f))
        val power = FrameLayout(context)
        power.addView(
            circle(R.drawable.ic_power, INK, Color.WHITE, 46).apply {
                contentDescription = "Turn the server on or off"
                setOnClickListener { setServer(!Config.serverOn) }
            },
            FrameLayout.LayoutParams(dp(46), dp(46)),
        )
        headerDot = View(context).apply { elevation = dpf(4f) }
        power.addView(headerDot, FrameLayout.LayoutParams(dp(11), dp(11), Gravity.TOP or Gravity.END))
        addView(power, lp(dp(46), dp(46)))
    }

    private fun controlPage(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        statusCard = card(30, 14, vertical = false) {
            gravity = Gravity.CENTER_VERTICAL
            addView(circle(R.drawable.ic_stat, PURPLE, PURPLE_SOFT, 48, elevated = false), lp(dp(48), dp(48)))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    statusTitle = label("", 16f, INK, medium)
                    statusSub = label("", 13f, MUTED, regular)
                    addView(statusTitle)
                    addView(statusSub)
                },
                lp(0, WRAP_CONTENT, 1f) { marginStart = dp(12) },
            )
            serverSwitch = Switch(context).apply {
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                thumbTintList = ColorStateList(states, intArrayOf(PURPLE, Color.WHITE))
                trackTintList = ColorStateList(states, intArrayOf(PURPLE_LIGHT, Color.parseColor("#C9C8D6")))
                contentDescription = "Server"
                setOnCheckedChangeListener { _, on -> if (!updating) setServer(on) }
            }
            addView(serverSwitch, lp(WRAP_CONTENT, WRAP_CONTENT))
        }
        addView(statusCard, lp(MATCH_PARENT, WRAP_CONTENT))

        val first = LinearLayout(context)
        accessValue = tile(first, R.drawable.ic_eye, PURPLE, PURPLE_SOFT, "Accessibility") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        wifiValue = tile(first, R.drawable.ic_wifi, MINT, MINT_SOFT, "Wi-Fi access") {
            Config.allowLan = !Config.allowLan
            if (ServerService.running) restartServer()
            refresh()
        }
        addView(first, lp(MATCH_PARENT, WRAP_CONTENT) { topMargin = dp(12) })

        val second = LinearLayout(context)
        protectedValue = tile(second, R.drawable.ic_shield, PINK, PINK_SOFT, "Protected apps") { show(2) }
        activityValue = tile(second, R.drawable.ic_list, AMBER, AMBER_SOFT, "Activity") { show(1) }
        addView(second, lp(MATCH_PARENT, WRAP_CONTENT) { topMargin = dp(12) })

        addView(
            card {
                addView(label("Address", 13f, MUTED, regular))
                address = label("", 13f, INK, Typeface.MONOSPACE).apply {
                    setTextIsSelectable(true)
                    setLineSpacing(dpf(3f), 1f)
                }
                addView(address, lp(MATCH_PARENT, WRAP_CONTENT) { topMargin = dp(6) })
                addView(pill("Copy address", filled = true) { copyAddress() }, lp(MATCH_PARENT, dp(52)) { topMargin = dp(16) })
                addView(pill("New secret", filled = false) { confirmNewSecret() }, lp(MATCH_PARENT, dp(52)) { topMargin = dp(10) })
            },
            lp(MATCH_PARENT, WRAP_CONTENT) { topMargin = dp(12) },
        )
    }

    private fun activityPage(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            card {
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label("Recent actions", 16f, INK, medium), lp(0, WRAP_CONTENT, 1f))
                    addView(label("Clear", 13f, INK, medium).apply {
                        setPadding(dp(16), dp(8), dp(16), dp(8))
                        background = ripple(rounded(Color.WHITE, 20, LINE))
                        setOnClickListener {
                            Config.clearLog()
                            refresh()
                        }
                    })
                })
                logRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                addView(logRows, lp(MATCH_PARENT, WRAP_CONTENT) { topMargin = dp(8) })
            },
            lp(MATCH_PARENT, WRAP_CONTENT),
        )
    }

    private fun safetyPage(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            card(30, 14, vertical = false) {
                gravity = Gravity.CENTER_VERTICAL
                addView(circle(R.drawable.ic_shield, PINK, PINK_SOFT, 48, elevated = false), lp(dp(48), dp(48)))
                addView(
                    label("Taps, swipes and typing are refused while a banking, UPI or wallet app is on screen.", 14f, INK, regular),
                    lp(0, WRAP_CONTENT, 1f) { marginStart = dp(12) },
                )
            },
            lp(MATCH_PARENT, WRAP_CONTENT),
        )
        addView(
            card {
                addView(label("Blocked words", 13f, MUTED, regular))
                wordsText = label("", 15f, INK, regular).apply { setLineSpacing(dpf(4f), 1f) }
                addView(wordsText, lp(MATCH_PARENT, WRAP_CONTENT) { topMargin = dp(6) })
                addView(label("Blocked apps", 13f, MUTED, regular), lp(MATCH_PARENT, WRAP_CONTENT) { topMargin = dp(18) })
                appsText = label("", 12.5f, INK, Typeface.MONOSPACE).apply { setLineSpacing(dpf(4f), 1f) }
                addView(appsText, lp(MATCH_PARENT, WRAP_CONTENT) { topMargin = dp(6) })
                addView(pill("Edit list", filled = true) { editBlocklist() }, lp(MATCH_PARENT, dp(52)) { topMargin = dp(18) })
                addView(
                    pill("Reset to defaults", filled = false) {
                        Config.resetBlocklist()
                        Config.log("protected_list_reset")
                        refresh()
                    },
                    lp(MATCH_PARENT, dp(52)) { topMargin = dp(10) },
                )
            },
            lp(MATCH_PARENT, WRAP_CONTENT) { topMargin = dp(12) },
        )
    }

    private fun nav(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = rounded(NAV, 40)
        elevation = dpf(10f)
        val tabs = listOf(R.drawable.ic_home to "Control", R.drawable.ic_list to "Activity", R.drawable.ic_shield to "Safety")
        navItems = tabs.mapIndexed { i, (icon, name) ->
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                contentDescription = name
                addView(
                    ImageView(context).apply {
                        setImageResource(icon)
                        imageTintList = ColorStateList.valueOf(Color.WHITE)
                        setPadding(dp(11), dp(11), dp(11), dp(11))
                    },
                    lp(dp(44), dp(44)),
                )
                addView(label(name, 14f, Color.WHITE, medium), lp(WRAP_CONTENT, WRAP_CONTENT) { marginStart = dp(8); marginEnd = dp(14) })
                setOnClickListener { show(i) }
            }.also { addView(it, lp(WRAP_CONTENT, WRAP_CONTENT) { if (i > 0) marginStart = dp(6) }) }
        }
    }

    private fun show(index: Int) {
        bigTitle.text = listOf("Control", "Activity", "Safety")[index]
        pages.forEachIndexed { i, page -> page.visibility = if (i == index) View.VISIBLE else View.GONE }
        navItems.forEachIndexed { i, item ->
            val active = i == index
            item.background = if (active) rounded(Color.TRANSPARENT, 30, PURPLE_LIGHT, 1.5f) else null
            item.getChildAt(0).background = oval(if (active) PURPLE else NAV_ITEM)
            item.getChildAt(1).visibility = if (active) View.VISIBLE else View.GONE
        }
    }

    // ------------------------------------------------------------- actions

    private fun setServer(on: Boolean) {
        Config.serverOn = on
        ServerService.error = null
        if (on) ServerService.start(this) else ServerService.stop(this)
        handler.postDelayed({ refresh() }, 300)
    }

    private fun restartServer() {
        ServerService.stop(this)
        handler.postDelayed({ if (Config.serverOn) ServerService.start(this) }, 300)
    }

    private fun localUrl() = "http://127.0.0.1:${Config.PORT}/${Config.secret()}/mcp"

    private fun wifiAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    } catch (e: Exception) {
        emptyList()
    }

    private fun copyAddress() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Phone Control address", localUrl()))
        Toast.makeText(this, "Address copied", Toast.LENGTH_SHORT).show()
    }

    private fun confirmNewSecret() {
        AlertDialog.Builder(this)
            .setTitle("Make a new secret?")
            .setMessage("Apps that use the current address will stop working until you give them the new one.")
            .setPositiveButton("New secret") { _, _ ->
                Config.newSecret()
                Config.log("secret_changed")
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editBlocklist() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }

        fun field(title: String, lines: List<String>): EditText {
            box.addView(label(title, 13f, MUTED, regular), lp(MATCH_PARENT, WRAP_CONTENT) { topMargin = dp(12) })
            return EditText(this).apply {
                setText(lines.joinToString("\n"))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                textSize = 14f
                gravity = Gravity.TOP
                box.addView(this, lp(MATCH_PARENT, WRAP_CONTENT))
            }
        }

        val keywords = field("Words in the app's package name, one per line", Config.keywords)
        val packages = field("Exact package names, one per line", Config.packages)
        AlertDialog.Builder(this)
            .setTitle("Protected apps")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                Config.keywords = Config.splitLines(keywords.text.toString())
                Config.packages = Config.splitLines(packages.text.toString())
                Config.log("protected_list_changed")
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------- display

    private fun accessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val me = ComponentName(this, ControlService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun refresh() {
        updating = true
        val access = accessibilityEnabled()
        val running = ServerService.running

        if (shownRunning != running) {
            shownRunning = running
            headerDot.background = oval(if (running) PURPLE else Color.parseColor("#C9C8D6"))
            statusCard.background = rounded(Color.WHITE, 30, if (running) PURPLE else LINE, 1.5f)
        }
        statusTitle.text = if (running) "Server is on" else "Server is off"
        statusSub.text = when {
            running && !access -> "Needs the accessibility permission"
            running -> "Port ${Config.PORT} · " + if (Config.allowLan) "this phone and Wi-Fi" else "this phone only"
            else -> ServerService.error ?: "Nothing can reach this phone"
        }
        statusSub.setTextColor(if ((running && !access) || (!running && ServerService.error != null)) RED else MUTED)
        serverSwitch.isChecked = Config.serverOn

        accessValue.text = if (access) "On" else "Off · tap to turn on"
        accessValue.setTextColor(if (access) MINT else RED)
        wifiValue.text = if (Config.allowLan) "Allowed" else "Off"
        protectedValue.text = "${Config.keywords.size + Config.packages.size} rules"

        val urls = mutableListOf(localUrl())
        if (Config.allowLan) wifiAddresses().forEach { urls.add("http://$it:${Config.PORT}/${Config.secret()}/mcp") }
        address.text = urls.joinToString("\n\n")

        val log = Config.readLog(60)
        val key = "${log.size}|${log.lastOrNull()}"
        if (key != logKey) {
            logKey = key
            renderLog(log.asReversed())
            activityValue.text = if (log.isEmpty()) "Nothing yet" else "Last at ${entry(log.last()).time}"
        }
        val safety = Config.keywords.joinToString("\n") + "|" + Config.packages.joinToString("\n")
        if (safety != safetyKey) {
            safetyKey = safety
            wordsText.text = Config.keywords.joinToString("  ·  ").ifEmpty { "None" }
            appsText.text = Config.packages.joinToString("\n").ifEmpty { "None" }
        }
        updating = false
    }

    private fun renderLog(lines: List<String>) {
        logRows.removeAllViews()
        if (lines.isEmpty()) {
            logRows.addView(
                label("Nothing yet. Actions from connected apps show up here.", 14f, MUTED, regular),
                lp(MATCH_PARENT, WRAP_CONTENT) { topMargin = dp(8) },
            )
            return
        }
        for (line in lines) {
            val e = entry(line)
            logRows.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, dp(9))
                addView(circle(e.icon, e.tint, e.soft, 38, elevated = false), lp(dp(38), dp(38)))
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(label(e.title, 14f, INK, medium))
                        if (e.detail.isNotEmpty()) {
                            addView(label(e.detail, 12f, MUTED, regular).apply {
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                            })
                        }
                    },
                    lp(0, WRAP_CONTENT, 1f) { marginStart = dp(12); marginEnd = dp(8) },
                )
                addView(label(e.time, 12f, MUTED, regular))
            })
        }
    }

    private class Entry(
        val icon: Int, val tint: Int, val soft: Int, val title: String, val detail: String, val time: String,
    )

    /** Turns one line of the action log into a row: icon, plain-words title, detail and time. */
    private fun entry(line: String): Entry {
        val o = try {
            JSONObject(line)
        } catch (e: Exception) {
            JSONObject()
        }
        val time = o.optString("time").let { if (it.length >= 16) it.substring(11, 16) else "" }
        fun row(icon: Int, tint: Int, soft: Int, title: String, detail: String = "") =
            Entry(icon, tint, soft, title, detail, time)
        return when (val action = o.optString("action")) {
            "tap" -> row(R.drawable.ic_touch, PURPLE, PURPLE_SOFT, "Tap", "at ${o.opt("x")}, ${o.opt("y")}")
            "long_press" -> row(R.drawable.ic_touch, PURPLE, PURPLE_SOFT, "Long press", "at ${o.opt("x")}, ${o.opt("y")}")
            "swipe" -> row(
                R.drawable.ic_touch, PURPLE, PURPLE_SOFT, "Swipe",
                "${o.opt("x1")}, ${o.opt("y1")} to ${o.opt("x2")}, ${o.opt("y2")}",
            )
            "type_text" -> row(R.drawable.ic_keyboard, MINT, MINT_SOFT, "Typed text", o.optString("text"))
            "press_key" -> row(
                R.drawable.ic_keyboard, MINT, MINT_SOFT, "Pressed a key",
                o.optString("key") + if (o.optInt("repeat", 1) > 1) " × ${o.optInt("repeat")}" else "",
            )
            "open_app" -> row(R.drawable.ic_open, AMBER, AMBER_SOFT, "Opened an app", o.optString("package"))
            "open_url" -> row(R.drawable.ic_open, AMBER, AMBER_SOFT, "Opened a link", o.optString("url"))
            "refused" -> row(R.drawable.ic_block, RED, RED_SOFT, "Refused", o.optString("package"))
            "connected" -> row(R.drawable.ic_wifi, PURPLE, PURPLE_SOFT, "App connected", o.optString("client"))
            "server_started" -> row(R.drawable.ic_power, MINT, MINT_SOFT, "Server started")
            "server_stopped" -> row(R.drawable.ic_power, MUTED, GREY_SOFT, "Server stopped")
            "secret_changed" -> row(R.drawable.ic_shield, PINK, PINK_SOFT, "New secret made")
            "protected_list_changed" -> row(R.drawable.ic_shield, PINK, PINK_SOFT, "Protected list changed")
            "protected_list_reset" -> row(R.drawable.ic_shield, PINK, PINK_SOFT, "Protected list reset")
            else -> row(R.drawable.ic_list, MUTED, GREY_SOFT, action.ifEmpty { line })
        }
    }

    // ------------------------------------------------------------- widgets

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    private fun lp(w: Int, h: Int, weight: Float = 0f, more: LinearLayout.LayoutParams.() -> Unit = {}) =
        LinearLayout.LayoutParams(w, h, weight).apply(more)

    private fun rounded(color: Int, radius: Int, stroke: Int = 0, strokeDp: Float = 1f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radius.toFloat())
        if (stroke != 0) setStroke(dpf(strokeDp).toInt().coerceAtLeast(1), stroke)
    }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun ripple(content: Drawable) = RippleDrawable(ColorStateList.valueOf(RIPPLE), content, null)

    private fun shadow(view: View, elevationDp: Float) {
        view.elevation = dpf(elevationDp)
        view.outlineSpotShadowColor = SHADOW
        view.outlineAmbientShadowColor = SHADOW
    }

    private fun label(text: String, sp: Float, color: Int, font: Typeface): TextView = TextView(this).apply {
        this.text = text
        textSize = sp
        typeface = font
        setTextColor(color)
    }

    private fun circle(icon: Int, tint: Int, fill: Int, size: Int, elevated: Boolean = true): ImageView =
        ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(tint)
            val pad = dp(size) * 27 / 100
            setPadding(pad, pad, pad, pad)
            background = ripple(oval(fill))
            if (elevated) shadow(this, 2f)
        }

    private fun card(radius: Int = 26, pad: Int = 18, vertical: Boolean = true, build: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
            background = rounded(Color.WHITE, radius)
            shadow(this, 3f)
            build()
        }

    /** A square tile for the grid; returns the line under its name so the status can be updated. */
    private fun tile(row: LinearLayout, icon: Int, tint: Int, fill: Int, name: String, onClick: () -> Unit): TextView {
        val value = label("", 13f, MUTED, regular)
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = ripple(rounded(Color.WHITE, 26))
            shadow(this, 3f)
            setOnClickListener { onClick() }
            addView(circle(icon, tint, fill, 44, elevated = false), lp(dp(44), dp(44)))
            addView(label(name, 15f, INK, regular), lp(MATCH_PARENT, WRAP_CONTENT) { topMargin = dp(28) })
            addView(value, lp(MATCH_PARENT, WRAP_CONTENT) { topMargin = dp(2) })
        }
        row.addView(tile, lp(0, WRAP_CONTENT, 1f) { if (row.childCount > 0) marginStart = dp(12) })
        return value
    }

    private fun pill(text: String, filled: Boolean, onClick: () -> Unit): TextView =
        label(text, 15f, if (filled) Color.WHITE else INK, medium).apply {
            gravity = Gravity.CENTER
            background = RippleDrawable(
                ColorStateList.valueOf(if (filled) Color.parseColor("#44FFFFFF") else RIPPLE),
                if (filled) rounded(PURPLE, 28) else rounded(Color.WHITE, 28, LINE),
                null,
            )
            setOnClickListener { onClick() }
        }
}
