package com.playground.phonecontrol

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = this
        Config.init(this)
    }

    companion object {
        lateinit var ctx: App
    }
}

/** Settings, the protected-app list and the action log. Mirrors config.json of the adb server. */
object Config {
    const val PORT = 8765

    /** Screenshots are scaled so the long side is at most this; tool coordinates are screenshot pixels. */
    const val MAX_SIDE = 1344

    val DEFAULT_KEYWORDS = listOf("bank", "upi", "wallet", "paytm", "phonepe", "payment")
    val DEFAULT_PACKAGES = listOf(
        "com.google.android.apps.nbu.paisa.user",
        "com.dreamplug.androidapp",
        "com.snapwork.hdfc",
        "com.csam.icici.bank.imobile",
        "com.sbi.lotusintouch",
        "com.sbi.SBIFreedomPlus",
        "com.axis.mobile",
        "com.msf.kbank.mobile",
        "com.mobikwik_new",
    )

    private lateinit var prefs: SharedPreferences
    private lateinit var logFile: File
    private val logLock = Any()
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)
        logFile = File(ctx.filesDir, "actions.log")
    }

    /** Secret path segment of the server address; generated on first use. */
    fun secret(): String = synchronized(this) {
        prefs.getString("remote_key", null) ?: newSecret()
    }

    fun newSecret(): String = synchronized(this) {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val s = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        prefs.edit().putString("remote_key", s).commit()
        s
    }

    var serverOn: Boolean
        get() = prefs.getBoolean("server_on", false)
        set(v) { prefs.edit().putBoolean("server_on", v).apply() }

    /** Off: only this phone (and adb forward / a tunnel app on it) can reach the server. */
    var allowLan: Boolean
        get() = prefs.getBoolean("allow_lan", false)
        set(v) { prefs.edit().putBoolean("allow_lan", v).apply() }

    var keywords: List<String>
        get() = prefs.getString("blocked_keywords", null)?.let(::splitLines) ?: DEFAULT_KEYWORDS
        set(v) { prefs.edit().putString("blocked_keywords", v.joinToString("\n")).apply() }

    var packages: List<String>
        get() = prefs.getString("blocked_packages", null)?.let(::splitLines) ?: DEFAULT_PACKAGES
        set(v) { prefs.edit().putString("blocked_packages", v.joinToString("\n")).apply() }

    fun resetBlocklist() {
        prefs.edit().remove("blocked_keywords").remove("blocked_packages").apply()
    }

    fun splitLines(s: String): List<String> = s.lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun isBlocked(pkg: String): Boolean {
        val p = pkg.lowercase()
        if (p.isEmpty()) return false
        if (packages.any { it.lowercase() == p }) return true
        return keywords.any { it.lowercase() in p }
    }

    fun log(action: String, vararg details: Pair<String, Any?>) {
        val o = JSONObject()
        o.put("time", LocalDateTime.now().format(stamp))
        o.put("action", action)
        for ((k, v) in details) o.put(k, v)
        synchronized(logLock) {
            if (logFile.length() > 400_000) {
                logFile.writeText(logFile.readLines().takeLast(500).joinToString("\n", postfix = "\n"))
            }
            logFile.appendText(o.toString() + "\n")
        }
    }

    /** One line per HTTP request, kept in files/http.log for troubleshooting connections. */
    fun trace(line: String) {
        synchronized(logLock) {
            val file = File(logFile.parentFile, "http.log")
            if (file.length() > 100_000) {
                file.writeText(file.readLines().takeLast(200).joinToString("\n", postfix = "\n"))
            }
            file.appendText(LocalDateTime.now().format(stamp) + " " + line + "\n")
        }
    }

    fun readLog(max: Int): List<String> = synchronized(logLock) {
        if (logFile.exists()) logFile.readLines().takeLast(max) else emptyList()
    }

    fun clearLog() {
        synchronized(logLock) { logFile.delete() }
    }
}
