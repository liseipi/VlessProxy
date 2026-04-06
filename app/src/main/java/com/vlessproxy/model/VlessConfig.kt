package com.vlessproxy.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.net.URI
import java.net.URLDecoder

@Parcelize
data class VlessConfig(
    val name: String = "",
    val uuid: String = "",
    val server: String = "",
    val port: Int = 443,
    val path: String = "/?ed=2560",
    val sni: String = "",
    val wsHost: String = "",
    val security: String = "tls",   // "tls" or "none"
    val listenPort: Int = 1080,
    val rejectUnauthorized: Boolean = false
) : Parcelable {

    fun isValid(): Boolean =
        uuid.isNotBlank() && server.isNotBlank() && port > 0 && port < 65536 &&
                listenPort > 0 && listenPort < 65536

    companion object {
        /**
         * Parse vless:// URI.
         * Example:
         * vless://7e409f0a-745d-485b-9546-a7a38ac2f20b@vs.musicses.vip:443
         *   ?encryption=none&security=tls&sni=vs.musicses.vip
         *   &type=ws&host=vs.musicses.vip&path=/?ed=2560#vs.musicses.vip
         */
        fun fromUri(uriStr: String): VlessConfig? {
            return try {
                // vless:// URIs sometimes have unencoded ? in path param — handle carefully
                val raw = uriStr.trim()
                if (!raw.startsWith("vless://", ignoreCase = true)) return null

                // Split fragment first
                val hashIdx = raw.indexOf('#')
                val fragment = if (hashIdx >= 0) raw.substring(hashIdx + 1) else ""
                val withoutFragment = if (hashIdx >= 0) raw.substring(0, hashIdx) else raw

                // Find query start — first ? after the authority
                val authorityStart = raw.indexOf("://") + 3
                val queryStart = withoutFragment.indexOf('?', authorityStart)
                val authority = if (queryStart >= 0) withoutFragment.substring(0, queryStart)
                else withoutFragment
                val query = if (queryStart >= 0) withoutFragment.substring(queryStart + 1) else ""

                // Parse authority: vless://uuid@host:port
                val authPart = authority.removePrefix("vless://")
                val atIdx = authPart.lastIndexOf('@')
                val uuid = authPart.substring(0, atIdx)
                val hostPort = authPart.substring(atIdx + 1)
                val colonIdx = hostPort.lastIndexOf(':')
                val host = hostPort.substring(0, colonIdx)
                val port = hostPort.substring(colonIdx + 1).toIntOrNull() ?: 443

                // Parse query params
                val params = mutableMapOf<String, String>()
                query.split('&').forEach { kv ->
                    val eqIdx = kv.indexOf('=')
                    if (eqIdx > 0) {
                        val k = kv.substring(0, eqIdx)
                        val v = URLDecoder.decode(kv.substring(eqIdx + 1), "UTF-8")
                        params[k] = v
                    }
                }

                val sni = params["sni"] ?: host
                val wsHost = params["host"] ?: host
                val security = params["security"] ?: "none"
                val path = params["path"] ?: "/"
                val name = if (fragment.isNotBlank()) URLDecoder.decode(fragment, "UTF-8") else host

                VlessConfig(
                    name = name,
                    uuid = uuid,
                    server = host,
                    port = port,
                    path = path,
                    sni = sni,
                    wsHost = wsHost,
                    security = security,
                    listenPort = 1080,
                    rejectUnauthorized = false
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Export back to vless:// URI */
    fun toUri(): String {
        val encodedPath = path.replace("?", "%3F").replace("=", "%3D")
        val name = if (name.isNotBlank()) name else server
        return "vless://$uuid@$server:$port" +
                "?encryption=none&security=$security&sni=$sni" +
                "&type=ws&host=$wsHost&path=$encodedPath#$name"
    }
}
