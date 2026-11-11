package com.playground.phonecontrol

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

class ToolError(message: String) : Exception(message)

/** The 12 tools, with the same names, arguments and results as server.py. */
object Tools {

    const val INSTRUCTIONS =
        "Controls the user's Android phone. Start with screenshot or read_screen, " +
            "then act with tap / swipe / type_text / press_key / open_app, and look " +
            "again after each action. All coordinates are pixels in the most recent " +
            "screenshot. Banking and payment apps are blocked on purpose."

    private val KEYS = listOf(
        "home", "back", "recents", "enter", "backspace", "tab", "escape", "menu", "search",
        "power", "wake", "volume_up", "volume_down", "media_play_pause",
        "up", "down", "left", "right", "paste", "notifications", "quick_settings",
    )
    private val ALWAYS_ALLOWED = setOf("home", "back", "recents", "wake", "volume_up", "volume_down")

    // ----------------------------------------------------------- catalogue

    private val REQUIRED = Any()

    private fun arg(name: String, type: String, default: Any? = REQUIRED) = Triple(name, type, default)

    private fun tool(name: String, description: String, vararg args: Triple<String, String, Any?>): JSONObject {
        val properties = JSONObject()
        val required = JSONArray()
        for ((argName, type, default) in args) {
            val p = JSONObject().put("type", type)
            if (default === REQUIRED) required.put(argName) else p.put("default", default)
            properties.put(argName, p)
        }
        val schema = JSONObject().put("type", "object").put("properties", properties)
        if (required.length() > 0) schema.put("required", required)
        return JSONObject().put("name", name).put("description", description).put("inputSchema", schema)
    }

    fun list(): JSONArray = JSONArray()
        .put(tool("phone_info", "Phone model, Android version, screen size and the app currently in front."))
        .put(tool("screenshot", "Capture the phone screen. Coordinates for tap and swipe refer to this image."))
        .put(
            tool(
                "read_screen",
                "List the visible UI elements as text: label, kind and tap point (screenshot pixels).\n\n" +
                    "Cheaper and more exact than a screenshot for finding buttons and fields.",
                arg("max_elements", "integer", 150),
            )
        )
        .put(tool("tap", "Tap at a point given in screenshot pixels.", arg("x", "integer"), arg("y", "integer")))
        .put(
            tool(
                "long_press", "Press and hold at a point given in screenshot pixels.",
                arg("x", "integer"), arg("y", "integer"), arg("duration_ms", "integer", 800),
            )
        )
        .put(
            tool(
                "swipe",
                "Swipe or drag between two points in screenshot pixels.\n\n" +
                    "To scroll a page down, swipe upward (start low, end high).",
                arg("x1", "integer"), arg("y1", "integer"), arg("x2", "integer"), arg("y2", "integer"),
                arg("duration_ms", "integer", 300),
            )
        )
        .put(
            tool(
                "type_text",
                "Type text into the focused field. Any language and emoji work.\n\n" +
                    "Tap the field first and make sure it has focus before typing. The text goes in at " +
                    "the cursor; in a password field it replaces what was there.",
                arg("text", "string"), arg("press_enter", "boolean", false),
            )
        )
        .put(
            tool(
                "press_key",
                "Press a phone key.\n\n" +
                    "Keys: home, back, recents, enter, backspace, tab, escape, power, wake, volume_up, " +
                    "volume_down, media_play_pause, up, down, left, right, paste, notifications, " +
                    "quick_settings. power locks the screen; menu and search are not available " +
                    "without a PC.",
                arg("key", "string"), arg("repeat", "integer", 1),
            )
        )
        .put(
            tool(
                "open_app",
                "Open an app by its name (WhatsApp), package name (com.whatsapp) or a keyword in either.",
                arg("app", "string"),
            )
        )
        .put(
            tool(
                "list_apps", "List installed app package names, optionally filtered by a keyword.",
                arg("filter", "string", ""), arg("include_system", "boolean", false),
            )
        )
        .put(
            tool(
                "open_url", "Open a web link (http or https) in the phone's default browser or app.",
                arg("url", "string"),
            )
        )
        .put(
            tool(
                "wait", "Pause while the phone loads or animates (at most 10 seconds).",
                arg("seconds", "number", 1.0),
            )
        )

    // ----------------------------------------------------------- arguments

    private fun JSONObject.present(name: String) = has(name) && !isNull(name)

    private fun JSONObject.num(name: String, default: Double? = null): Double {
        if (!present(name)) return default ?: throw ToolError("Missing argument '$name'.")
        return when (val v = get(name)) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        } ?: throw ToolError("Argument '$name' must be a number.")
    }

    private fun JSONObject.int(name: String, default: Int? = null): Int =
        num(name, default?.toDouble()).roundToInt()

    private fun JSONObject.str(name: String, default: String? = null): String {
        if (!present(name)) return default ?: throw ToolError("Missing argument '$name'.")
        return get(name).toString()
    }

    private fun JSONObject.bool(name: String, default: Boolean): Boolean {
        if (!present(name)) return default
        return when (val v = get(name)) {
            is Boolean -> v
            is String -> v.trim().lowercase() == "true"
            else -> throw ToolError("Argument '$name' must be true or false.")
        }
    }

    // -------------------------------------------------------------- safety

    private fun service(): ControlService = ControlService.instance
        ?: throw ToolError("The accessibility service is off. Open the Phone Control app on the phone and turn it on.")

    /** Refuses to act while a protected app, or this app's own settings, is on screen. */
    private fun guard(s: ControlService) {
        val own = s.packageName
        val pkg = s.visiblePackages().firstOrNull { it == own || Config.isBlocked(it) } ?: return
        Config.log("refused", "package" to pkg)
        if (pkg == own) {
            throw ToolError(
                "Refused: the Phone Control app itself has to be operated by hand. Use press_key('home') to leave."
            )
        }
        throw ToolError(
            "Refused: $pkg is on the protected list (banking and payments). " +
                "The owner has to do this part by hand. Use press_key('home') to leave."
        )
    }

    // ------------------------------------------------------------ dispatch

    private fun text(s: String) = JSONArray().put(JSONObject().put("type", "text").put("text", s))

    /** Runs one tool and returns an MCP tools/call result. */
    fun run(name: String, args: JSONObject): JSONObject = try {
        JSONObject().put("content", synchronized(this) { call(name, args) }).put("isError", false)
    } catch (e: Exception) {
        val why = if (e is ToolError) e.message else "${e.javaClass.simpleName}: ${e.message}"
        JSONObject().put("content", text("Error executing tool $name: $why")).put("isError", true)
    }

    private fun call(name: String, a: JSONObject): JSONArray = when (name) {
        "phone_info" -> {
            val s = service()
            val (w, h) = s.deviceSize()
            val sc = s.scale()
            text(
                JSONObject()
                    .put("model", Build.MODEL)
                    .put("android", Build.VERSION.RELEASE)
                    .put("device_pixels", "${w}x$h")
                    .put("screenshot_pixels", "${(w / sc).roundToInt()}x${(h / sc).roundToInt()}")
                    .put("foreground_app", s.foreground())
                    .toString(2)
            )
        }

        "screenshot" -> {
            val s = service()
            val full = s.capture()
            val sc = max(1.0, max(full.width, full.height) / Config.MAX_SIDE.toDouble())
            val img = if (sc > 1.0) {
                Bitmap.createScaledBitmap(full, (full.width / sc).roundToInt(), (full.height / sc).roundToInt(), true)
            } else full
            val jpeg = ByteArrayOutputStream()
            img.compress(Bitmap.CompressFormat.JPEG, 80, jpeg)
            val note = "${img.width}x${img.height} screenshot, foreground app: ${s.foreground().ifEmpty { "unknown" }}"
            text(note).put(
                JSONObject().put("type", "image")
                    .put("data", Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP))
                    .put("mimeType", "image/jpeg")
            )
        }

        "read_screen" -> text(service().readScreen(a.int("max_elements", 150).coerceAtLeast(1)))

        "tap" -> {
            val s = service()
            val x = a.int("x")
            val y = a.int("y")
            guard(s)
            s.press(x, y, 60)
            Config.log("tap", "x" to x, "y" to y)
            text("Tapped ($x,$y).")
        }

        "long_press" -> {
            val s = service()
            val x = a.int("x")
            val y = a.int("y")
            val ms = a.int("duration_ms", 800).coerceIn(300, 5000)
            guard(s)
            s.press(x, y, ms.toLong())
            Config.log("long_press", "x" to x, "y" to y, "duration_ms" to ms)
            text("Long-pressed ($x,$y) for $ms ms.")
        }

        "swipe" -> {
            val s = service()
            val x1 = a.int("x1")
            val y1 = a.int("y1")
            val x2 = a.int("x2")
            val y2 = a.int("y2")
            val ms = a.int("duration_ms", 300).coerceIn(50, 5000)
            guard(s)
            s.swipe(x1, y1, x2, y2, ms.toLong())
            Config.log("swipe", "x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2, "duration_ms" to ms)
            text("Swiped ($x1,$y1) -> ($x2,$y2).")
        }

        "type_text" -> {
            val s = service()
            val value = a.str("text")
            val enter = a.bool("press_enter", false)
            guard(s)
            s.typeText(value, enter)
            Config.log("type_text", "text" to value, "press_enter" to enter)
            text("Typed ${value.codePointCount(0, value.length)} characters.")
        }

        "press_key" -> {
            val key = a.str("key").trim().lowercase()
            if (key !in KEYS) throw ToolError("Unknown key '$key'. Valid: ${KEYS.joinToString(", ")}")
            val s = service()
            if (key !in ALWAYS_ALLOWED) guard(s)
            val times = a.int("repeat", 1).coerceIn(1, 30)
            for (i in 0 until times) {
                if (i > 0) Thread.sleep(80)
                s.pressKey(key)
            }
            Config.log("press_key", "key" to key, "repeat" to times)
            text("Pressed $key" + if (times > 1) " x$times." else ".")
        }

        "list_apps" -> {
            val pm = App.ctx.packageManager
            val filter = a.str("filter", "").trim().lowercase()
            val includeSystem = a.bool("include_system", false)
            val found = pm.getInstalledApplications(0).filter { app ->
                if (filter.isEmpty()) includeSystem || (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                else filter in app.packageName.lowercase() ||
                    filter in pm.getApplicationLabel(app).toString().lowercase()
            }.map { it.packageName }.sorted()
            text(if (found.isEmpty()) "No matching apps." else found.joinToString("\n"))
        }

        "open_app" -> {
            val s = service()
            val pm = s.packageManager
            val wanted = a.str("app")
            val needle = wanted.trim().lowercase().replace(" ", "")
            if (needle.isEmpty()) throw ToolError("Say which app to open.")
            val apps = pm.getInstalledApplications(0)
            var matches = apps.map { it.packageName }.filter { it.lowercase() == needle }
            if (matches.isEmpty()) {
                val named = apps.map {
                    it.packageName to pm.getApplicationLabel(it).toString().lowercase().replace(" ", "")
                }
                matches = named.filter { it.second == needle }.map { it.first }
                if (matches.isEmpty()) {
                    matches = named.filter { needle in it.first.lowercase() || needle in it.second }.map { it.first }
                }
                // Background pieces of an app share its name; prefer the ones with a screen to open.
                val openable = matches.filter { pm.getLaunchIntentForPackage(it) != null }
                if (openable.isNotEmpty()) matches = openable
            }
            matches = matches.sorted()
            if (matches.isEmpty()) {
                throw ToolError("No installed app matches '$wanted'. Try list_apps, or tap its icon on the home screen.")
            }
            if (matches.size > 1) {
                text("Several apps match, pick one package name:\n" + matches.take(25).joinToString("\n"))
            } else {
                val pkg = matches[0]
                if (Config.isBlocked(pkg)) {
                    Config.log("refused", "package" to pkg)
                    throw ToolError("Refused: $pkg is on the protected list (banking and payments).")
                }
                val launch = pm.getLaunchIntentForPackage(pkg)
                    ?: throw ToolError("$pkg has no launcher screen to open.")
                s.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Config.log("open_app", "package" to pkg)
                text("Opened $pkg.")
            }
        }

        "open_url" -> {
            val s = service()
            val url = a.str("url")
            if (!Regex("^https?://\\S+$").matches(url)) throw ToolError("Only http and https links can be opened.")
            try {
                s.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                throw ToolError("No app on the phone can open this link.")
            }
            Config.log("open_url", "url" to url)
            text("Opened $url.")
        }

        "wait" -> {
            val seconds = a.num("seconds", 1.0).coerceIn(0.0, 10.0)
            Thread.sleep((seconds * 1000).toLong())
            val shown = if (seconds % 1.0 == 0.0) seconds.toInt().toString() else seconds.toString()
            text("Waited $shown s.")
        }

        else -> throw ToolError("Unknown tool: $name")
    }
}
