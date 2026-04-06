package com.vlessproxy.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.vlessvpn.R
import com.vlessproxy.model.VlessConfig
import com.vlessproxy.ui.MainActivity

class ProxyService : LifecycleService() {

    inner class LocalBinder : Binder() {
        fun getService() = this@ProxyService
    }

    private val binder = LocalBinder()
    private var proxyServer: ProxyServer? = null
    private var currentConfig: VlessConfig? = null

    companion object {
        const val ACTION_START  = "com.vlessproxy.START"
        const val ACTION_STOP   = "com.vlessproxy.STOP"
        const val EXTRA_CONFIG  = "config"
        const val CHANNEL_ID    = "proxy_channel"
        const val NOTIF_ID      = 1

        fun startIntent(ctx: Context, cfg: VlessConfig) =
            Intent(ctx, ProxyService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONFIG, cfg)
            }

        fun stopIntent(ctx: Context) =
            Intent(ctx, ProxyService::class.java).apply { action = ACTION_STOP }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val cfg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(EXTRA_CONFIG, VlessConfig::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_CONFIG)

                cfg?.let { startProxy(it) }
            }
            ACTION_STOP -> stopProxy()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    val isRunning: Boolean get() = proxyServer?.isRunning == true
    val config: VlessConfig? get() = currentConfig

    fun startProxy(cfg: VlessConfig): Boolean {
        stopProxy()
        currentConfig = cfg
        val server = ProxyServer(cfg)
        return if (server.start()) {
            proxyServer = server
            startForeground(NOTIF_ID, buildNotification(cfg))
            true
        } else {
            false
        }
    }

    fun stopProxy() {
        proxyServer?.stop()
        proxyServer = null
        currentConfig = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(cfg: VlessConfig): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VLESS Proxy Running")
            .setContentText("${cfg.name} · SOCKS5/HTTP → 127.0.0.1:${cfg.listenPort}")
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(R.drawable.ic_stop, "Stop", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val chan = NotificationChannel(
            CHANNEL_ID,
            "Proxy Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows proxy connection status"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
    }
}
