// app/src/main/java/com/vlessproxy/service/VlessVpnService.kt
package com.vlessproxy.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vlessproxy.R
import com.vlessproxy.Tun2Socks
import com.vlessproxy.model.VlessConfig
import com.vlessproxy.ui.MainActivity
import kotlinx.coroutines.*

private const val TAG = "VlessVpnService"

class VlessVpnService : VpnService() {

    inner class LocalBinder : Binder() {
        fun getService() = this@VlessVpnService
    }

    private val binder = LocalBinder()
    private var tunFd: ParcelFileDescriptor? = null
    private var proxyServer: ProxyServer? = null
    private var currentConfig: VlessConfig? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var running = false

    companion object {
        const val ACTION_START = "com.vlessproxy.VPN_START"
        const val ACTION_STOP  = "com.vlessproxy.VPN_STOP"
        const val EXTRA_CONFIG = "config"
        const val CHANNEL_ID   = "vpn_channel"
        const val NOTIF_ID     = 2

        // tun2socks 监听的本地 SOCKS5 地址
        private const val SOCKS_HOST = "127.0.0.1"

        fun startIntent(ctx: Context, cfg: VlessConfig) =
            Intent(ctx, VlessVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONFIG, cfg)
            }

        fun stopIntent(ctx: Context) =
            Intent(ctx, VlessVpnService::class.java).apply { action = ACTION_STOP }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val cfg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(EXTRA_CONFIG, VlessConfig::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_CONFIG)
                cfg?.let { startVpn(it) }
            }
            ACTION_STOP -> stopVpn()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    val isRunning: Boolean get() = running
    val config: VlessConfig? get() = currentConfig

    fun startVpn(cfg: VlessConfig): Boolean {
        if (running) stopVpn()
        currentConfig = cfg

        // 1. 启动本地 SOCKS5 代理（连接到 VLESS 服务器）
        val proxy = ProxyServer(cfg)
        if (!proxy.start()) {
            Log.e(TAG, "Failed to start proxy server")
            return false
        }
        proxyServer = proxy

        // 2. 建立 TUN 虚拟网卡
        val builder = Builder()
            .setSession("VLESS VPN")
            .addAddress("10.0.0.2", 24)
            .addRoute("0.0.0.0", 0)          // 全局路由
            .addDnsServer("8.8.8.8")
            .addDnsServer("8.8.4.4")
            .setMtu(1500)
            // 排除 app 自身流量，防止死循环
            .addDisallowedApplication(packageName)

        tunFd = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish() failed: ${e.message}")
            proxy.stop()
            return false
        }

        if (tunFd == null) {
            Log.e(TAG, "tunFd is null — VPN permission not granted?")
            proxy.stop()
            return false
        }

        // 3. 启动 tun2socks，将 TUN 流量转发到本地 SOCKS5
        val fd = tunFd!!.fd
        val socksPort = cfg.listenPort
        scope.launch {
            runTun2Socks(fd, SOCKS_HOST, socksPort)
        }

        running = true
        startForeground(NOTIF_ID, buildNotification(cfg))
        Log.i(TAG, "VPN started: TUN fd=$fd → SOCKS5 $SOCKS_HOST:$socksPort → ${cfg.server}:${cfg.port}")
        return true
    }

    fun stopVpn() {
        running = false
        Tun2Socks.stopTun2Socks()
        scope.coroutineContext.cancelChildren()
        try { tunFd?.close() } catch (_: Exception) {}
        tunFd = null
        proxyServer?.stop()
        proxyServer = null
        currentConfig = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "VPN stopped")
    }

    /**
     * 在后台线程调用 tun2socks_start，阻塞直到 tun2socks_terminate() 被调用。
     *
     * 等价命令行：
     *   badvpn-tun2socks
     *     --tunfd=<fd>
     *     --tunmtu=1500
     *     --socks-server-addr=127.0.0.1:<port>
     *     --loglevel=0
     */
    private fun runTun2Socks(tunFd: Int, socksHost: String, socksPort: Int) {
        // 保护文件描述符，防止 close-on-exec
        protect(tunFd)

        val args = arrayOf(
            "badvpn-tun2socks",
            "--tunfd=$tunFd",
            "--tunmtu=1500",
            "--socks-server-addr=$socksHost:$socksPort",
            "--loglevel=0"
        )
        Log.d(TAG, "tun2socks args: ${args.joinToString(" ")}")
        val result = Tun2Socks.start_tun2socks(args)
        Log.i(TAG, "tun2socks exited with $result")
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(cfg: VlessConfig): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VLESS VPN Running")
            .setContentText("${cfg.name} · ${cfg.server}:${cfg.port}")
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(R.drawable.ic_stop, "Stop", stopPi)
            .build()
    }

    private fun createNotificationChannel() {
        val chan = NotificationChannel(
            CHANNEL_ID, "VPN Status", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows VPN connection status"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
    }

    override fun onDestroy() {
        stopVpn()
        scope.cancel()
        super.onDestroy()
    }
}