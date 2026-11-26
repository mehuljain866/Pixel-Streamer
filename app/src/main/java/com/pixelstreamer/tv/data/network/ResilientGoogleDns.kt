package com.pixelstreamer.tv.data.network

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolves Google Photos shortlinks and CDN domains robustly even on networks/ISPs
 * where system DNS returns sinkhole IPs (e.g. 49.44.79.x) or fails for photos.app.goo.gl.
 * Falls back to Google Public DNS-over-HTTPS via direct IP (https://8.8.8.8/resolve).
 */
class ResilientGoogleDns : Dns {

    private data class CachedEntry(val addresses: List<InetAddress>, val expiresAtMs: Long)

    private val cache = ConcurrentHashMap<String, CachedEntry>()

    // Direct IP client avoids recursive DNS calls during DoH resolution.
    private val dohClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override fun lookup(hostname: String): List<InetAddress> {
        val lower = hostname.lowercase()
        val now = System.currentTimeMillis()
        cache[lower]?.let { entry ->
            if (entry.expiresAtMs > now && entry.addresses.isNotEmpty()) {
                return entry.addresses
            }
        }

        val preferDohFirst = lower == "photos.app.goo.gl" || lower == "goo.gl"
        if (preferDohFirst) {
            resolveViaDoh(lower)?.let { addrs ->
                cache[lower] = CachedEntry(addrs, now + CACHE_TTL_MS)
                return addrs
            }
        }

        val systemAddrs = try {
            Dns.SYSTEM.lookup(hostname)
                .sortedBy { if (it is Inet4Address) 0 else 1 }
        } catch (e: UnknownHostException) {
            emptyList()
        }

        val looksBlocked = systemAddrs.isEmpty() || systemAddrs.any { isSinkholeIp(it.hostAddress.orEmpty()) }
        if (looksBlocked) {
            resolveViaDoh(lower)?.let { addrs ->
                cache[lower] = CachedEntry(addrs, now + CACHE_TTL_MS)
                return addrs
            }
        }

        if (systemAddrs.isNotEmpty()) {
            return systemAddrs
        }
        throw UnknownHostException("Unable to resolve host: $hostname")
    }

    private fun resolveViaDoh(hostname: String): List<InetAddress>? {
        return try {
            val req = Request.Builder()
                .url("https://8.8.8.8/resolve?name=$hostname&type=A")
                .header("Accept", "application/dns-json")
                .build()
            dohClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                parseDohJsonAnswer(body).takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val CACHE_TTL_MS = 5 * 60 * 1000L

        fun parseDohJsonAnswer(body: String): List<InetAddress> {
            val json = JSONObject(body)
            val answers = json.optJSONArray("Answer") ?: return emptyList()
            val result = mutableListOf<InetAddress>()
            for (i in 0 until answers.length()) {
                val ans = answers.optJSONObject(i) ?: continue
                if (ans.optInt("type") == 1) {
                    val ip = ans.optString("data").trim()
                    if (ip.isNotEmpty() && !isSinkholeIp(ip)) {
                        parseNumericIpv4(ip)?.let { result.add(it) }
                    }
                }
            }
            return result
        }

        fun parseNumericIpv4(ip: String): InetAddress? {
            val parts = ip.split('.')
            if (parts.size != 4) return null
            val bytes = ByteArray(4)
            for (i in 0 until 4) {
                val v = parts[i].toIntOrNull() ?: return null
                if (v !in 0..255) return null
                bytes[i] = v.toByte()
            }
            return InetAddress.getByAddress(ip, bytes)
        }

        fun isSinkholeIp(ip: String): Boolean {
            return ip.startsWith("49.44.79.") || ip == "0.0.0.0" || ip == "127.0.0.1"
        }
    }
}
