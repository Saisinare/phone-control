package com.playground.phonecontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/** The hands and eyes: screenshots, gestures, text entry, global keys and the UI tree. */
class ControlService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ControlService? = null
    }

    @Volatile
    private var lastPackage = ""
    private val shotExecutor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        shotExecutor.shutdown()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.let { lastPackage = it.toString() }
        }
    }

    // ------------------------------------------------------------ geometry

    fun deviceSize(): Pair<Int, Int> {
        val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return b.width() to b.height()
    }

    /** Device pixels per screenshot pixel (same on both axes, any rotation). */
    fun scale(): Double {
        val (w, h) = deviceSize()
        return max(1.0, max(w, h) / Config.MAX_SIDE.toDouble())
    }

    private fun toDevice(x: Int, y: Int): Pair<Float, Float> {
        val s = scale()
        val (w, h) = deviceSize()
        return (x * s).roundToInt().coerceIn(0, w - 1).toFloat() to
            (y * s).roundToInt().coerceIn(0, h - 1).toFloat()
    }

    // ---------------------------------------------------------- foreground

    fun foreground(): String = rootInActiveWindow?.packageName?.toString() ?: lastPackage

    /** Every app with a window on screen (split screen, or an app behind the shade). */
    fun visiblePackages(): Set<String> {
        val out = LinkedHashSet<String>()
        foreground().takeIf { it.isNotEmpty() }?.let(out::add)
        try {
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                w.root?.packageName?.toString()?.let(out::add)
            }
        } catch (_: Exception) {
        }
        return out
    }

    // ---------------------------------------------------------- screenshot

    fun capture(): Bitmap {
        repeat(3) {
            val latch = CountDownLatch(1)
            var bitmap: Bitmap? = null
            var error = 0
            takeScreenshot(Display.DEFAULT_DISPLAY, shotExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    try {
                        bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally {
                        buffer.close()
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    error = errorCode
                    latch.countDown()
                }
            })
            if (!latch.await(10, TimeUnit.SECONDS)) throw ToolError("The screenshot timed out.")
            bitmap?.let { return it }
            // Android allows about three screenshots a second; wait and try again.
            if (error != ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                throw ToolError("The screen could not be captured (code $error). Try read_screen.")
            }
            Thread.sleep(400)
        }
        throw ToolError("The screen could not be captured. Try again in a second.")
    }

    // ------------------------------------------------------------ gestures

    private fun gesture(path: Path, durationMs: Long) {
        val latch = CountDownLatch(1)
        var completed = false
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val sent = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) {
                    completed = true
                    latch.countDown()
                }

                override fun onCancelled(d: GestureDescription?) {
                    latch.countDown()
                }
            },
            null,
        )
        if (!sent) throw ToolError("The gesture could not be sent.")
        if (!latch.await(durationMs + 5000, TimeUnit.MILLISECONDS) || !completed) {
            throw ToolError("The gesture was interrupted.")
        }
    }

    fun press(x: Int, y: Int, durationMs: Long) {
        val (dx, dy) = toDevice(x, y)
        gesture(Path().apply { moveTo(dx, dy); lineTo(dx, dy) }, durationMs)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
        val (ax, ay) = toDevice(x1, y1)
        val (bx, by) = toDevice(x2, y2)
        gesture(Path().apply { moveTo(ax, ay); lineTo(bx, by) }, durationMs)
    }

    // ---------------------------------------------------------------- text

    /**
     * The service keeps a cache of the UI tree that is refreshed by the events it receives
     * (all types are subscribed for that reason). Dropping it before a read rules out stale text.
     */
    private fun freshTree() {
        if (Build.VERSION.SDK_INT >= 34) clearCache()
    }

    private fun focusedInput(): AccessibilityNodeInfo? {
        freshTree()
        return findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }

    private fun focusedField(): AccessibilityNodeInfo =
        focusedInput()?.takeIf { it.isEditable }?.also { it.refresh() }
            ?: throw ToolError("No text field has focus. Tap the field first, then try again.")

    /** Text of a field and its cursor range. Password fields hide their text, so they read as empty. */
    private fun contents(node: AccessibilityNodeInfo): Triple<String, Int, Int> {
        val text = if (node.isShowingHintText || node.isPassword) "" else node.text?.toString() ?: ""
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0 || start > text.length || end > text.length) {
            start = text.length
            end = text.length
        }
        return Triple(text, minOf(start, end), maxOf(start, end))
    }

    private fun setText(node: AccessibilityNodeInfo, text: String, cursor: Int) {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            throw ToolError("This field does not accept typed text.")
        }
        val sel = Bundle()
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
    }

    /** Inserts at the cursor, replacing any selection, like typing on a keyboard. */
    fun typeText(text: String, pressEnter: Boolean) {
        val node = focusedField()
        val (current, start, end) = contents(node)
        setText(node, current.substring(0, start) + text + current.substring(end), start + text.length)
        if (pressEnter) enter()
    }

    private fun backspace() {
        val node = focusedField()
        if (node.isPassword) {
            setText(node, "", 0) // the hidden text cannot be edited in place
            return
        }
        val (current, start, end) = contents(node)
        val from = if (start < end) start else if (start > 0) current.offsetByCodePoints(start, -1) else return
        setText(node, current.removeRange(from, end), from)
    }

    private fun enter() {
        val node = focusedInput()
        if (node != null && node.isEditable &&
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        ) return
        if (Build.VERSION.SDK_INT >= 33 && performGlobalAction(GLOBAL_ACTION_DPAD_CENTER)) return
        throw ToolError("Enter needs a focused text field. Tap the field first.")
    }

    // ---------------------------------------------------------------- keys

    private fun global(action: Int, key: String) {
        if (!performGlobalAction(action)) throw ToolError("The phone did not accept '$key'.")
    }

    private fun dpad(action: Int, key: String) {
        if (Build.VERSION.SDK_INT < 33) throw ToolError("'$key' needs Android 13 or newer.")
        global(action, key)
    }

    fun pressKey(key: String) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (key) {
            "home" -> global(GLOBAL_ACTION_HOME, key)
            "back", "escape" -> global(GLOBAL_ACTION_BACK, key)
            "recents" -> global(GLOBAL_ACTION_RECENTS, key)
            "notifications" -> global(GLOBAL_ACTION_NOTIFICATIONS, key)
            "quick_settings" -> global(GLOBAL_ACTION_QUICK_SETTINGS, key)
            "power" -> global(GLOBAL_ACTION_LOCK_SCREEN, key)
            "wake" -> {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("DEPRECATION")
                pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "phonecontrol:wake",
                ).acquire(3000)
            }
            "enter" -> enter()
            "backspace" -> backspace()
            "tab" -> {
                val next = focusedInput()?.focusSearch(View.FOCUS_FORWARD)
                    ?: throw ToolError("There is no next field to move to.")
                if (!next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                    throw ToolError("The next field could not take focus.")
                }
            }
            "paste" -> if (!focusedField().performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                throw ToolError("Nothing could be pasted here.")
            }
            "up" -> dpad(GLOBAL_ACTION_DPAD_UP, key)
            "down" -> dpad(GLOBAL_ACTION_DPAD_DOWN, key)
            "left" -> dpad(GLOBAL_ACTION_DPAD_LEFT, key)
            "right" -> dpad(GLOBAL_ACTION_DPAD_RIGHT, key)
            "volume_up" -> audio.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "volume_down" -> audio.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "media_play_pause" -> {
                val now = SystemClock.uptimeMillis()
                val code = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
                audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
            }
            else -> throw ToolError("The '$key' key is not available without a PC (it needs adb).")
        }
    }

    // ------------------------------------------------------------- UI tree

    fun readScreen(maxElements: Int): String {
        freshTree()
        var root: AccessibilityNodeInfo? = null
        for (attempt in 0 until 3) {
            root = rootInActiveWindow
            if (root != null) break
            Thread.sleep(400)
        }
        if (root == null) {
            throw ToolError("The screen could not be read (it may be animating or secured). Try screenshot.")
        }
        val s = scale()
        val lines = ArrayList<String>()
        val r = Rect()

        fun visit(node: AccessibilityNodeInfo): Boolean {
            if (!node.isVisibleToUser) return true
            val text = node.text?.toString()?.trim().orEmpty() // an empty field reports its hint
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val clickable = node.isClickable
            val editable = node.isEditable
            node.getBoundsInScreen(r)
            if ((text.isNotEmpty() || desc.isNotEmpty() || clickable || editable) && r.width() > 0 && r.height() > 0) {
                val cx = ((r.left + r.right) / 2.0 / s).roundToInt()
                val cy = ((r.top + r.bottom) / 2.0 / s).roundToInt()
                val kind = node.className?.toString()?.substringAfterLast('.') ?: ""
                val flags = listOf(
                    "tap" to clickable, "edit" to editable, "scroll" to node.isScrollable,
                    "checked" to node.isChecked, "focused" to node.isFocused,
                ).filter { it.second }.joinToString(" ") { it.first }
                var label = text.ifEmpty { desc }.ifEmpty { "(no label)" }
                if (text.isNotEmpty() && desc.isNotEmpty() && desc != text) label = "$text / $desc"
                lines.add("($cx,$cy) $kind: ${label.take(120)}" + if (flags.isNotEmpty()) " [$flags]" else "")
                if (lines.size >= maxElements) {
                    lines.add("... more elements not shown")
                    return false
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (!visit(child)) return false
            }
            return true
        }
        visit(root)
        val header = "foreground app: ${foreground().ifEmpty { "unknown" }}"
        return header + "\n" + if (lines.isEmpty()) "(no readable elements)" else lines.joinToString("\n")
    }
}
