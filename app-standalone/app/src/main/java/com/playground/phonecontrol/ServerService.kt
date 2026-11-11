package com.playground.phonecontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.io.IOException

/** Keeps the MCP server alive in the background, with a notification that can stop it. */
class ServerService : Service() {

    companion object {
        private const val ACTION_STOP = "com.playground.phonecontrol.STOP"
        private const val CHANNEL = "server"

        @Volatile
        var running = false

        /** Why the last start failed, for the main screen. */
        @Volatile
        var error: String? = null

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ServerService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ServerService::class.java))
        }
    }

    private var server: McpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Config.serverOn = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification())
        }
        if (server == null) {
            try {
                server = McpServer(Config.PORT, Config.allowLan).also { it.start() }
                running = true
                error = null
                Config.log("server_started")
            } catch (e: IOException) {
                error = "Could not start: port ${Config.PORT} is in use."
                Config.serverOn = false
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.let {
            it.stop()
            Config.log("server_stopped")
        }
        server = null
        running = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Server status", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Phone Control is on")
            .setContentText("Connected AI apps can operate this phone.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }
}
