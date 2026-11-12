package com.persica.imdbtv

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object SmartDns : Dns {
    private data class CacheEntry(val ips: List<InetAddress>, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val dohEndpoints = listOf(
        "https://cloudflare-dns.com/dns-query",
        "https://dns.google/resolve"
    )

    // Bootstrap IPs so DoH endpoints never depend on poisoned operator DNS
    private val bootstrapClient = OkHttpClient.Builder()
        .dns(Dns { host ->
            when (host) {
                "cloudflare-dns.com" -> listOf(
                    InetAddress.getByAddress(host, byteArrayOf(1, 1, 1, 1)),
                    InetAddress.getByAddress(host, byteArrayOf(1, 0, 0, 1))
                )
                "dns.google" -> listOf(
                    InetAddress.getByAddress(host, byteArrayOf(8, 8, 8, 8)),
                    InetAddress.getByAddress(host, byteArrayOf(8, 8, 4, 4))
                )
                else -> Dns.SYSTEM.lookup(host)
            }
        })
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname == "localhost" || hostname == "127.0.0.1") {
            return Dns.SYSTEM.lookup(hostname)
        }
        val now = System.currentTimeMillis()
        cache[hostname]?.let {
            if (it.expiresAt > now && it.ips.isNotEmpty()) return it.ips
        }

        for (ep in dohEndpoints) {
            try {
                val req = Request.Builder()
                    .url("$ep?name=$hostname&type=A")
                    .header("Accept", "application/dns-json")
                    .build()
                bootstrapClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: return@use
                    val answers = JSONObject(body).optJSONArray("Answer") ?: return@use
                    val resolved = mutableListOf<InetAddress>()
                    for (i in 0 until answers.length()) {
                        val obj = answers.optJSONObject(i) ?: continue
                        if (obj.optInt("type") == 1) {
                            val ipStr = obj.optString("data").trim()
                            if (ipStr.isNotEmpty() && !ipStr.startsWith("10.10.34.")) {
                                resolved.add(InetAddress.getByName(ipStr))
                            }
                        }
                    }
                    if (resolved.isNotEmpty()) {
                        cache[hostname] = CacheEntry(resolved, now + 600_000L)
                        return resolved
                    }
                }
            } catch (_: Exception) {}
        }

        val sys = try {
            Dns.SYSTEM.lookup(hostname).filter { !(it.hostAddress ?: "").startsWith("10.10.34.") }
        } catch (_: Exception) {
            emptyList()
        }
        if (sys.isNotEmpty()) return sys
        throw UnknownHostException("SmartDns could not resolve $hostname")
    }
}
