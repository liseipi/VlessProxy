package com.vlessproxy.model

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class ConfigRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("vless_configs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_CONFIGS = "configs"
        private const val KEY_ACTIVE_IDX = "active_index"
        private const val KEY_AUTO_START = "auto_start"
    }

    fun saveConfigs(configs: List<VlessConfig>) {
        val arr = JSONArray()
        configs.forEach { cfg ->
            val obj = JSONObject().apply {
                put("name", cfg.name)
                put("uuid", cfg.uuid)
                put("server", cfg.server)
                put("port", cfg.port)
                put("path", cfg.path)
                put("sni", cfg.sni)
                put("wsHost", cfg.wsHost)
                put("security", cfg.security)
                put("listenPort", cfg.listenPort)
                put("rejectUnauthorized", cfg.rejectUnauthorized)
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_CONFIGS, arr.toString()).apply()
    }

    fun loadConfigs(): MutableList<VlessConfig> {
        val json = prefs.getString(KEY_CONFIGS, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                VlessConfig(
                    name = obj.optString("name", ""),
                    uuid = obj.optString("uuid", ""),
                    server = obj.optString("server", ""),
                    port = obj.optInt("port", 443),
                    path = obj.optString("path", "/"),
                    sni = obj.optString("sni", ""),
                    wsHost = obj.optString("wsHost", ""),
                    security = obj.optString("security", "tls"),
                    listenPort = obj.optInt("listenPort", 1080),
                    rejectUnauthorized = obj.optBoolean("rejectUnauthorized", false)
                )
            }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    var activeIndex: Int
        get() = prefs.getInt(KEY_ACTIVE_IDX, 0)
        set(value) = prefs.edit().putInt(KEY_ACTIVE_IDX, value).apply()

    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()
}
