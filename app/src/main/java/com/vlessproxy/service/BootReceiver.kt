package com.vlessproxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.vlessproxy.model.ConfigRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val repo = ConfigRepository(context)
        if (!repo.autoStart) return
        val configs = repo.loadConfigs()
        val idx = repo.activeIndex
        val cfg = configs.getOrNull(idx) ?: return
        val svc = ProxyService.startIntent(context, cfg)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.startForegroundService(svc)
        else
            context.startService(svc)
    }
}
