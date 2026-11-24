package com.alaa.m

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class OpenPortInfo(
    val port: Int,
    val service: String
)

data class NetworkDevice(
    val ip: String,
    val mac: String,
    val hostname: String,
    val os: String,
    val rxBytes: Long,
    val txBytes: Long,
    val rxRateBps: Long,
    val txRateBps: Long,
    val openPorts: List<OpenPortInfo>,
    val visitedSites: List<String>,
    val blockedSites: Set<String>,
    val allowedSites: Set<String>,
    val isBlocked: Boolean
)

object RootNetEngine {

    private var libraryLoaded = false

    init {
        try {
            System.loadLibrary("alaa_net_native")
            libraryLoaded = true
        } catch (_: Throwable) {
            libraryLoaded = false
        }
    }

    external fun nativeScanDevicePortsJson(ip: String, hostname: String): String
    external fun nativeResolveHostname(ip: String): String
    external fun nativeBuildIptablesCommand(action: String, target: String, extra: String): String

    private val previousTotals = mutableMapOf<String, Pair<Long, Long>>()
    private var lastSampleTimeMs: Long = 0L

    fun runRootCommand(command: String, timeoutSec: Long = 8): Pair<Boolean, String> {
    if (command.isBlank()) return Pair(false, "Empty command")
    return try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()

        val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return Pair(false, "Root command timed out")
        }

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.exitValue()
        Pair(exitCode == 0, output)
    } catch (e: Throwable) {
        Pair(false, e.message ?: "Root execution error")
    }
}

fun checkRootAccess(): Boolean {
    val (ok, out) = runRootCommand("id -u", timeoutSec = 6)
    return ok && out.trim() == "0"
}



    fun getLocalSubnetPrefix(context: Context): String {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcp = wifiManager?.dhcpInfo
            val ipInt = dhcp?.ipAddress ?: 0
            if (ipInt != 0) {
                val b1 = ipInt and 0xff
                val b2 = (ipInt shr 8) and 0xff
                val b3 = (ipInt shr 16) and 0xff
                "$b1.$b2.$b3"
            } else {
                "192.168.1"
            }
        } catch (_: Throwable) {
            "192.168.1"
        }
    }

    private fun readArpAndNeighbors(): List<Pair<String, String>> {
        val result = mutableMapOf<String, String>()

        val (_, neighOut) = runRootCommand("ip neigh show 2>/dev/null", timeoutSec = 5)
        neighOut.lines().forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 4) {
                val ip = parts[0]
                val lladdrIndex = parts.indexOf("lladdr")
                val mac = if (lladdrIndex != -1 && lladdrIndex + 1 < parts.size) {
                    parts[lladdrIndex + 1].lowercase()
                } else {
                    ""
                }
                val state = parts.lastOrNull()?.uppercase() ?: ""
                if (isIpv4(ip) && isMac(mac) && state != "FAILED" && state != "INCOMPLETE") {
                    result[ip] = mac
                }
            }
        }

        val (_, arpOut) = runRootCommand("cat /proc/net/arp 2>/dev/null", timeoutSec = 5)
        arpOut.lines().drop(1).forEach { line ->
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size >= 4) {
                val ip = cols[0]
                val mac = cols[3].lowercase()
                if (isIpv4(ip) && isMac(mac) && !result.containsKey(ip)) {
                    result[ip] = mac
                }
            }
        }

        return result.entries.map { it.key to it.value }
    }

    private fun readTrafficCountersByIp(): Map<String, Pair<Long, Long>> {
        val stats = mutableMapOf<String, Long>()
        val (_, iptablesOut) = runRootCommand("iptables -L FORWARD -v -n -x 2>/dev/null; iptables -L INPUT -v -n -x 2>/dev/null", timeoutSec = 5)
        iptablesOut.lines().forEach { line ->
            val tokens = line.trim().split(Regex("\\s+"))
            if (tokens.size >= 8) {
                val bytes = tokens[1].toLongOrNull() ?: 0L
                val src = tokens[6]
                val dst = tokens[7]
                if (isIpv4(src)) {
                    stats[src] = (stats[src] ?: 0L) + bytes
                }
                if (isIpv4(dst)) {
                    stats[dst] = (stats[dst] ?: 0L) + bytes
                }
            }
        }
        return stats.mapValues { (_, total) -> Pair(total / 2, total / 2) }
    }

    private fun readObservedSitesByIp(): Map<String, List<String>> {
        val map = mutableMapOf<String, LinkedHashSet<String>>()
        val (_, conntrackOut) = runRootCommand(
            "cat /proc/net/nf_conntrack 2>/dev/null | head -n 250",
            timeoutSec = 5
        )
        conntrackOut.lines().forEach { line ->
            val srcMatch = Regex("src=([0-9.]+)").find(line)?.groupValues?.get(1) ?: return@forEach
            val dstMatch = Regex("dst=([0-9.]+)").find(line)?.groupValues?.get(1) ?: return@forEach
            if (!isIpv4(srcMatch) || !isIpv4(dstMatch)) return@forEach
            if (dstMatch.startsWith("192.168.") || dstMatch.startsWith("10.") || dstMatch.startsWith("127.")) {
                return@forEach
            }
            val hostLabel = if (libraryLoaded) {
                try {
                    val h = nativeResolveHostname(dstMatch)
                    if (h.startsWith("Device-")) dstMatch else h
                } catch (_: Throwable) {
                    dstMatch
                }
            } else {
                dstMatch
            }
            val set = map.getOrPut(srcMatch) { linkedSetOf() }
            if (set.size < 8) {
                set.add(hostLabel)
            }
        }
        return map.mapValues { it.value.toList() }
    }

    fun scanNetwork(
        context: Context,
        blockedIps: Set<String>,
        blockedMacs: Set<String>,
        blockedSitesByDevice: Map<String, Set<String>>,
        allowedSitesByDevice: Map<String, Set<String>>
    ): List<NetworkDevice> {
        val nowMs = System.currentTimeMillis()
        val elapsedSec = if (lastSampleTimeMs > 0L) {
            ((nowMs - lastSampleTimeMs) / 1000.0).coerceAtLeast(1.0)
        } else {
            2.0
        }
        lastSampleTimeMs = nowMs

        val subnet = getLocalSubnetPrefix(context)
        runRootCommand("for i in $(seq 1 254); do ping -c 1 -W 1 $subnet.\$i >/dev/null 2>&1 & done; wait", timeoutSec = 6)

        val entries = readArpAndNeighbors()
        val totalsMap = readTrafficCountersByIp()
        val sitesMap = readObservedSitesByIp()

        val devices = mutableListOf<NetworkDevice>()
        for ((ip, mac) in entries.take(35)) {
            val initialHost = if (libraryLoaded) {
                try { nativeResolveHostname(ip) } catch (_: Throwable) { "Device-$ip" }
            } else {
                "Device-$ip"
            }

            var osName = "Linux / Android"
            val portsList = mutableListOf<OpenPortInfo>()
            var finalHost = initialHost

            if (libraryLoaded) {
                try {
                    val rawJson = nativeScanDevicePortsJson(ip, initialHost)
                    val obj = JSONObject(rawJson)
                    osName = obj.optString("os", osName)
                    val resolved = obj.optString("hostname", finalHost)
                    if (resolved.isNotBlank()) finalHost = resolved
                    val arr: JSONArray = obj.optJSONArray("ports") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val p = item.optInt("port", -1)
                        val svc = item.optString("service", "")
                        if (p > 0) {
                            portsList.add(OpenPortInfo(p, svc))
                        }
                    }
                } catch (_: Throwable) {
                    // Keep safe defaults on scan error
                }
            }

            val pair = totalsMap[ip] ?: Pair(0L, 0L)
            val prevPair = previousTotals[ip] ?: pair
            val rxRate = (((pair.first - prevPair.first).coerceAtLeast(0L)) / elapsedSec).toLong()
            val txRate = (((pair.second - prevPair.second).coerceAtLeast(0L)) / elapsedSec).toLong()
            previousTotals[ip] = pair

            val dBlockedSites = blockedSitesByDevice[ip].orEmpty()
            val dAllowedSites = allowedSitesByDevice[ip].orEmpty()
            val observed = sitesMap[ip].orEmpty()
            val mergedSites = (observed + dBlockedSites + dAllowedSites)
                .filterNot { site -> dAllowedSites.isNotEmpty() && site !in dAllowedSites && site !in dBlockedSites }
                .distinct()

            val isBlocked = blockedIps.contains(ip) || blockedMacs.contains(mac.lowercase())

            devices.add(
                NetworkDevice(
                    ip = ip,
                    mac = mac.uppercase(),
                    hostname = finalHost,
                    os = osName,
                    rxBytes = pair.first,
                    txBytes = pair.second,
                    rxRateBps = rxRate,
                    txRateBps = txRate,
                    openPorts = portsList,
                    visitedSites = mergedSites,
                    blockedSites = dBlockedSites,
                    allowedSites = dAllowedSites,
                    isBlocked = isBlocked
                )
            )
        }

        return devices.sortedBy { ipToLong(it.ip) }
    }

    fun applyDeviceBlock(device: NetworkDevice, block: Boolean): Pair<Boolean, String> {
        val actionIp = if (block) "BLOCK_DEVICE_IP" else "UNBLOCK_DEVICE_IP"
        val actionMac = if (block) "BLOCK_DEVICE_MAC" else "UNBLOCK_DEVICE_MAC"

        val cmdIp = if (libraryLoaded) nativeBuildIptablesCommand(actionIp, device.ip, "") else ""
        val cmdMac = if (libraryLoaded) nativeBuildIptablesCommand(actionMac, device.mac.lowercase(), "") else ""

        val fullCmd = listOf(cmdIp, cmdMac).filter { it.isNotBlank() }.joinToString("; ")
        return runRootCommand(fullCmd)
    }

    fun applyAllNetworkBlock(block: Boolean): Pair<Boolean, String> {
        val action = if (block) "BLOCK_ALL_NETWORK" else "UNBLOCK_ALL_NETWORK"
        val cmd = if (libraryLoaded) nativeBuildIptablesCommand(action, "", "") else ""
        return runRootCommand(cmd)
    }

    fun applySiteRule(ip: String, site: String, block: Boolean): Pair<Boolean, String> {
        val action = if (block) "BLOCK_SITE_FOR_IP" else "ALLOW_SITE_FOR_IP"
        val cmd = if (libraryLoaded) nativeBuildIptablesCommand(action, ip, site.trim()) else ""
        return runRootCommand(cmd)
    }

    private fun isIpv4(s: String): Boolean {
        val parts = s.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            val n = part.toIntOrNull()
            n != null && n in 0..255
        }
    }

    private fun isMac(s: String): Boolean {
        return Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$").matches(s) &&
            s.lowercase() != "00:00:00:00:00:00" &&
            s.lowercase() != "ff:ff:ff:ff:ff:ff"
    }

    private fun ipToLong(ip: String): Long {
        return ip.split(".").mapNotNull { it.toLongOrNull() }.fold(0L) { acc, v -> (acc shl 8) + v }
    }
}
