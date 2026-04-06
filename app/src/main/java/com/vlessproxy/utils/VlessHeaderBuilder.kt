package com.vlessproxy.utils

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Mirrors client.js buildVlessHeader() and ipv6ToBytes() exactly.
 *
 * VLESS request header format (version 0):
 *   [0]      version = 0x00
 *   [1..16]  UUID (16 bytes)
 *   [17]     addon length = 0x00
 *   [18]     command = 0x01 (TCP)
 *   [19..20] port (big-endian uint16)
 *   [21]     address type: 1=IPv4, 2=domain, 3=IPv6
 *   [22..]   address bytes
 */
object VlessHeaderBuilder {

    fun build(uuid: String, host: String, port: Int): ByteArray {
        // Decode UUID hex → 16 bytes
        val uuidHex = uuid.replace("-", "")
        val uid = ByteArray(16) { i -> uuidHex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

        // Determine address type and bytes
        val (atype, addrBytes) = resolveAddress(host)

        val fixed = ByteBuffer.allocate(22)
        fixed.put(0x00.toByte())              // version
        fixed.put(uid)                        // UUID (16 bytes)
        fixed.put(0x00.toByte())              // addon len
        fixed.put(0x01.toByte())              // command TCP
        fixed.putShort(port.toShort())        // port big-endian
        fixed.put(atype.toByte())             // address type

        return fixed.array() + addrBytes
    }

    private fun resolveAddress(host: String): Pair<Int, ByteArray> {
        // Try IPv4
        val v4 = tryParseIPv4(host)
        if (v4 != null) return Pair(1, v4)

        // Try IPv6
        val v6 = tryParseIPv6(host)
        if (v6 != null) return Pair(3, v6)

        // Domain name
        val domainBytes = host.toByteArray(Charsets.UTF_8)
        val buf = ByteArray(1 + domainBytes.size)
        buf[0] = domainBytes.size.toByte()
        domainBytes.copyInto(buf, 1)
        return Pair(2, buf)
    }

    private fun tryParseIPv4(host: String): ByteArray? {
        return try {
            val parts = host.split(".")
            if (parts.size != 4) return null
            val bytes = parts.map { it.toInt() }
            if (bytes.any { it < 0 || it > 255 }) return null
            bytes.map { it.toByte() }.toByteArray()
        } catch (e: Exception) { null }
    }

    private fun tryParseIPv6(host: String): ByteArray? {
        return try {
            val clean = host.removePrefix("[").removeSuffix("]")
            if (!clean.contains(":")) return null
            val addr = InetAddress.getByName(clean)
            if (addr is Inet6Address) addr.address else null
        } catch (e: Exception) { null }
    }
}
