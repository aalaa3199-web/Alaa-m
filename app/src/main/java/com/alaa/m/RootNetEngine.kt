
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

    external fun nativeScanDevicePortsJson(
        ip: String,
        hostname: String
    ): String

    external fun nativeResolveHostname(ip: String): String

    external fun nativeBuildIptablesCommand(
        action: String,
        target: String,
        extra: String
    ): String

    private val previousTotals =
        mutableMapOf<String, Pair<Long, Long>>()

    private val hostnameCache =
        mutableMapOf<String, String>()

    private val portCache =
        mutableMapOf<String, List<OpenPortInfo>>()

    private val osCache =
        mutableMapOf<String, String>()

    private val lastPortScan =
        mutableMapOf<String, Long>()

    private var lastSampleTimeMs = 0L
    private var lastDiscoveryTimeMs = 0L

    private const val DISCOVERY_INTERVAL = 30_000L
    private const val PORT_SCAN_INTERVAL = 60_000L

    // --------------------------------------------------
    // ROOT COMMAND
    // --------------------------------------------------

    fun runRootCommand(
        command: String,
        timeoutSec: Long = 8
    ): Pair<Boolean, String> {

        if (command.isBlank()) {
            return Pair(false, "Empty command")
        }

        var process: Process? = null
        val output = StringBuilder()

        return try {

            process = ProcessBuilder(
                "su",
                "-c",
                command
            )
                .redirectErrorStream(true)
                .start()

            val currentProcess = process

            // قراءة المخرجات أثناء تشغيل الأمر
            val readerThread = Thread {

                try {
                    BufferedReader(
                        InputStreamReader(
                            currentProcess.inputStream
                        )
                    ).use { reader ->

                        while (true) {
                            val line = reader.readLine() ?: break

                            synchronized(output) {
                                output.append(line).append('\n')
                            }
                        }
                    }
                } catch (_: Throwable) {
                }

            }

            readerThread.isDaemon = true
            readerThread.start()

            val finished = currentProcess.waitFor(
                timeoutSec,
                TimeUnit.SECONDS
            )

            if (!finished) {

                currentProcess.destroyForcibly()

                try {
                    currentProcess.waitFor(2, TimeUnit.SECONDS)
                } catch (_: Throwable) {
                }

                readerThread.join(1000)

                return Pair(
                    false,
                    "Root command timed out after ${timeoutSec}s"
                )
            }

            readerThread.join(1500)

            val result = synchronized(output) {
                output.toString()
            }

            Pair(
                currentProcess.exitValue() == 0,
                result
            )

        } catch (e: Throwable) {

            try {
                process?.destroyForcibly()
            } catch (_: Throwable) {
            }

            Pair(
                false,
                e.message ?: "Root execution error"
            )
        }
    }

    fun checkRootAccess(): Boolean {

        val (success, output) = runRootCommand(
            "id -u",
            timeoutSec = 6
        )

        return success && output.trim() == "0"
    }

    // --------------------------------------------------
    // NETWORK INFORMATION
    // --------------------------------------------------

    private fun getWifiDhcpInfo(
        context: Context
    ) = try {

        val wifiManager =
            context.applicationContext.getSystemService(
                Context.WIFI_SERVICE
            ) as? WifiManager

        wifiManager?.dhcpInfo

    } catch (_: Throwable) {
        null
    }

    fun getLocalSubnetPrefix(
        context: Context
    ): String {

        return try {

            val ip = getWifiDhcpInfo(context)?.ipAddress ?: 0

            if (ip == 0) return ""

            val b1 = ip and 0xff
            val b2 = (ip shr 8) and 0xff
            val b3 = (ip shr 16) and 0xff

            "$b1.$b2.$b3"

        } catch (_: Throwable) {
            ""
        }
    }

    private fun getLocalNetworkHosts(
        context: Context
    ): List<String> {

        return try {

            val dhcp = getWifiDhcpInfo(context)
                ?: return emptyList()

            val ip = dhcp.ipAddress.toLong() and 0xffffffffL
            val mask = dhcp.netmask.toLong() and 0xffffffffL

            if (ip == 0L) return emptyList()

            val actualMask = if (mask == 0L) {
                0xffffff00L
            } else {
                mask
            }

            val network = ip and actualMask
            val broadcast = network or (
                actualMask.inv() and 0xffffffffL
            )

            val first = network + 1
            val last = broadcast - 1

            if (last < first) return emptyList()

            val result = mutableListOf<String>()

            // الحد الأقصى 254 عنواناً
            var current = first

            while (current <= last && result.size < 254) {

                if (current != ip) {

                    val address = listOf(
                        (current shr 24) and 255,
                        (current shr 16) and 255,
                        (current shr 8) and 255,
                        current and 255
                    ).joinToString(".")

                    result.add(address)
                }

                current++
            }

            result

        } catch (_: Throwable) {
            emptyList()
        }
    }

    // --------------------------------------------------
    // NETWORK DISCOVERY
    // --------------------------------------------------

    private fun runPingSweep(
        context: Context
    ) {

        val hosts = getLocalNetworkHosts(context)

        if (hosts.isEmpty()) return

        val command = StringBuilder()

        hosts.forEachIndexed { index, ip ->

            command.append(
                "(ping -c 1 -W 1 '$ip' >/dev/null 2>&1) & "
            )

            // تنفيذ الفحص على دفعات
            if ((index + 1) % 24 == 0) {
                command.append("wait; ")
            }
        }

        command.append("wait")

        runRootCommand(
            command.toString(),
            timeoutSec = 15
        )
    }

    private fun readArpAndNeighbors():
        List<Pair<String, String>> {

        val result = mutableMapOf<String, String>()

        // قراءة جدول الجيران
        val (_, neighOut) = runRootCommand(
            "ip neigh show 2>/dev/null",
            timeoutSec = 5
        )

        neighOut.lines().forEach { line ->

            val parts = line.trim().split(
                Regex("\\s+")
            )

            if (parts.size < 4) return@forEach

            val ip = parts[0]

            val macIndex = parts.indexOf("lladdr")

            if (macIndex == -1 ||
                macIndex + 1 >= parts.size
            ) {
                return@forEach
            }

            val mac = parts[macIndex + 1].lowercase()
            val state = parts.last().uppercase()

            if (
                isIpv4(ip) &&
                isMac(mac) &&
                state != "FAILED" &&
                state != "INCOMPLETE"
            ) {
                result[ip] = mac
            }
        }

        // قراءة ARP
        val (_, arpOut) = runRootCommand(
            "cat /proc/net/arp 2>/dev/null",
            timeoutSec = 5
        )

        arpOut.lines().drop(1).forEach { line ->

            val cols = line.trim().split(
                Regex("\\s+")
            )

            if (cols.size < 6) return@forEach

            val ip = cols[0]
            val flags = cols[2]
            val mac = cols[3].lowercase()

            val validFlags = try {
                flags.removePrefix("0x").toInt(16) != 0
            } catch (_: Throwable) {
                false
            }

            if (
                validFlags &&
                isIpv4(ip) &&
                isMac(mac) &&
                !result.containsKey(ip)
            ) {
                result[ip] = mac
            }
        }

        return result.entries.map {
            it.key to it.value
        }
    }

    // --------------------------------------------------
    // TRAFFIC COUNTERS
    // --------------------------------------------------

    private fun readTrafficCountersByIp():
        Map<String, Pair<Long, Long>> {

        val stats =
            mutableMapOf<String, Pair<Long, Long>>()

        val (_, output) = runRootCommand(
            "iptables -L FORWARD -v -n -x 2>/dev/null",
            timeoutSec = 5
        )

        output.lines().forEach { line ->

            val tokens = line.trim().split(
                Regex("\\s+")
            )

            // pkts bytes target prot opt in out source destination
            if (tokens.size < 9) return@forEach

            val bytes = tokens[1].toLongOrNull()
                ?: return@forEach

            val source = tokens[7]
            val destination = tokens[8]

            if (isIpv4(source)) {

                val old = stats[source] ?: Pair(0L, 0L)

                stats[source] = Pair(
                    old.first,
                    old.second + bytes
                )
            }

            if (isIpv4(destination)) {

                val old = stats[destination] ?: Pair(0L, 0L)

                stats[destination] = Pair(
                    old.first + bytes,
                    old.second
                )
            }
        }

        return stats
    }

    // --------------------------------------------------
    // OBSERVED CONNECTIONS
    // --------------------------------------------------

    private fun readObservedSitesByIp():
        Map<String, List<String>> {

        val map =
            mutableMapOf<String, LinkedHashSet<String>>()

        val (_, output) = runRootCommand(
            "cat /proc/net/nf_conntrack 2>/dev/null | head -n 250",
            timeoutSec = 5
        )

        output.lines().forEach { line ->

            val src = Regex(
                "src=([0-9.]+)"
            ).find(line)?.groupValues?.get(1)
                ?: return@forEach

            val dst = Regex(
                "dst=([0-9.]+)"
            ).find(line)?.groupValues?.get(1)
                ?: return@forEach

            if (!isIpv4(src) || !isIpv4(dst)) {
                return@forEach
            }

            if (
                dst.startsWith("192.168.") ||
                dst.startsWith("10.") ||
                dst.startsWith("127.")
            ) {
                return@forEach
            }

            val set = map.getOrPut(src) {
                linkedSetOf()
            }

            if (set.size >= 8) return@forEach

            // هذه عناوين اتصالات، وليست إثباتاً
            // بأن المستخدم زار موقعاً معيناً.
            set.add(dst)
        }

        return map.mapValues {
            it.value.toList()
        }
    }

    // --------------------------------------------------
    // HOSTNAME
    // --------------------------------------------------

    private fun resolveHostname(ip: String): String {

        hostnameCache[ip]?.let {
            return it
        }

        val result = if (libraryLoaded) {

            try {
                nativeResolveHostname(ip)
            } catch (_: Throwable) {
                "Device-$ip"
            }

        } else {
            "Device-$ip"
        }

        val finalName = if (
            result.isBlank() ||
            result == ip
        ) {
            "Device-$ip"
        } else {
            result
        }

        hostnameCache[ip] = finalName

        return finalName
    }

    // --------------------------------------------------
    // PORT SCAN
    // --------------------------------------------------

    private fun scanPorts(
        ip: String,
        hostname: String
    ): Triple<String, String, List<OpenPortInfo>> {

        val now = System.currentTimeMillis()

        val previousScan = lastPortScan[ip] ?: 0L

        if (
            now - previousScan < PORT_SCAN_INTERVAL
        ) {

            return Triple(
                hostname,
                osCache[ip] ?: "Unknown",
                portCache[ip] ?: emptyList()
            )
        }

        if (!libraryLoaded) {

            return Triple(
                hostname,
                "Unknown",
                emptyList()
            )
        }

        return try {

            val raw = nativeScanDevicePortsJson(
                ip,
                hostname
            )

            val obj = JSONObject(raw)

            val os = obj.optString(
                "os",
                "Unknown"
            )

            val resolved = obj.optString(
                "hostname",
                hostname
            )

            val ports = mutableListOf<OpenPortInfo>()

            val array: JSONArray =
                obj.optJSONArray("ports") ?: JSONArray()

            for (i in 0 until array.length()) {

                val item = array.optJSONObject(i)
                    ?: continue

                val port = item.optInt("port", -1)

                val service = item.optString(
                    "service",
                    "Unknown"
                )

                if (port in 1..65535) {
                    ports.add(
                        OpenPortInfo(port, service)
                    )
                }
            }

            lastPortScan[ip] = now
            portCache[ip] = ports
            osCache[ip] = os

            val finalName = if (resolved.isBlank()) {
                hostname
            } else {
                resolved
            }

            hostnameCache[ip] = finalName

            Triple(
                finalName,
                os,
                ports
            )

        } catch (_: Throwable) {

            Triple(
                hostname,
                "Unknown",
                emptyList()
            )
        }
    }

    // --------------------------------------------------
    // MAIN NETWORK SCAN
    // --------------------------------------------------

    @Synchronized
    fun scanNetwork(
        context: Context,
        blockedIps: Set<String>,
        blockedMacs: Set<String>,
        blockedSitesByDevice: Map<String, Set<String>>,
        allowedSitesByDevice: Map<String, Set<String>>
    ): List<NetworkDevice> {

        val now = System.currentTimeMillis()

        val elapsedSec = if (lastSampleTimeMs > 0L) {
            ((now - lastSampleTimeMs) / 1000.0)
                .coerceAtLeast(1.0)
        } else {
            2.0
        }

        lastSampleTimeMs = now

        // لا تعِد فحص الشبكة بالكامل كل 8 ثوانٍ
        if (
            now - lastDiscoveryTimeMs >=
            DISCOVERY_INTERVAL
        ) {

            runPingSweep(context)

            lastDiscoveryTimeMs = now
        }

        val entries = readArpAndNeighbors()

        val totalsMap = readTrafficCountersByIp()

        val sitesMap = readObservedSitesByIp()

        val devices = mutableListOf<NetworkDevice>()

        for ((ip, mac) in entries.take(35)) {

            val initialHost = resolveHostname(ip)

            val scanResult = scanPorts(
                ip,
                initialHost
            )

            val finalHost = scanResult.first
            val osName = scanResult.second
            val portsList = scanResult.third

            val pair = totalsMap[ip] ?: Pair(0L, 0L)

            val previous = previousTotals[ip] ?: pair

            val rxRate = (
                (pair.first - previous.first)
                    .coerceAtLeast(0L) / elapsedSec
            ).toLong()

            val txRate = (
                (pair.second - previous.second)
                    .coerceAtLeast(0L) / elapsedSec
            ).toLong()

            previousTotals[ip] = pair

            val deviceBlockedSites =
                blockedSitesByDevice[ip].orEmpty()

            val deviceAllowedSites =
                allowedSitesByDevice[ip].orEmpty()

            val observed = sitesMap[ip].orEmpty()

            val mergedSites = (
                observed +
                deviceBlockedSites +
                deviceAllowedSites
            ).distinct()

            val isBlocked =
                blockedIps.contains(ip) ||
                blockedMacs.contains(mac.lowercase())

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
                    blockedSites = deviceBlockedSites,
                    allowedSites = deviceAllowedSites,
                    isBlocked = isBlocked
                )
            )
        }

        return devices.sortedBy {
            ipToLong(it.ip)
        }
    }

    // --------------------------------------------------
    // DEVICE BLOCK
    // --------------------------------------------------

    fun applyDeviceBlock(
        device: NetworkDevice,
        block: Boolean
    ): Pair<Boolean, String> {

        if (!libraryLoaded) {
            return Pair(
                false,
                "Native library is not loaded"
            )
        }

        return try {

            val actionIp = if (block) {
                "BLOCK_DEVICE_IP"
            } else {
                "UNBLOCK_DEVICE_IP"
            }

            val actionMac = if (block) {
                "BLOCK_DEVICE_MAC"
            } else {
                "UNBLOCK_DEVICE_MAC"
            }

            val cmdIp = nativeBuildIptablesCommand(
                actionIp,
                device.ip,
                ""
            )

            val cmdMac = nativeBuildIptablesCommand(
                actionMac,
                device.mac.lowercase(),
                ""
            )

            val commands = listOf(
                cmdIp,
                cmdMac
            ).filter {
                it.isNotBlank()
            }

            if (commands.isEmpty()) {
                return Pair(false, "No block command generated")
            }

            runRootCommand(
                commands.joinToString("; ")
            )

        } catch (e: Throwable) {

            Pair(
                false,
                e.message ?: "Device block error"
            )
        }
    }

    // --------------------------------------------------
    // BLOCK ALL
    // --------------------------------------------------

    fun applyAllNetworkBlock(
        block: Boolean
    ): Pair<Boolean, String> {

        if (!libraryLoaded) {
            return Pair(
                false,
                "Native library is not loaded"
            )
        }

        return try {

            val action = if (block) {
                "BLOCK_ALL_NETWORK"
            } else {
                "UNBLOCK_ALL_NETWORK"
            }

            val command = nativeBuildIptablesCommand(
                action,
                "",
                ""
            )

            runRootCommand(command)

        } catch (e: Throwable) {

            Pair(
                false,
                e.message ?: "Network block error"
            )
        }
    }

    // --------------------------------------------------
    // SITE RULE
    // --------------------------------------------------

    fun applySiteRule(
        ip: String,
        site: String,
        block: Boolean
    ): Pair<Boolean, String> {

        if (!libraryLoaded) {
            return Pair(
                false,
                "Native library is not loaded"
            )
        }

        if (!isIpv4(ip) || site.isBlank()) {
            return Pair(false, "Invalid IP or site")
        }

        return try {

            val action = if (block) {
                "BLOCK_SITE_FOR_IP"
            } else {
                "ALLOW_SITE_FOR_IP"
            }

            val command = nativeBuildIptablesCommand(
                action,
                ip,
                site.trim()
            )

            runRootCommand(command)

        } catch (e: Throwable) {

            Pair(
                false,
                e.message ?: "Site rule error"
            )
        }
    }

    // --------------------------------------------------
    // VALIDATION
    // --------------------------------------------------

    private fun isIpv4(s: String): Boolean {

        val parts = s.split(".")

        if (parts.size != 4) return false

        return parts.all { part ->

            val number = part.toIntOrNull()

            number != null && number in 0..255
        }
    }

    private fun isMac(s: String): Boolean {

        return Regex(
            "^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$"
        ).matches(s) &&
            s.lowercase() != "00:00:00:00:00:00" &&
            s.lowercase() != "ff:ff:ff:ff:ff:ff"
    }

    private fun ipToLong(ip: String): Long {

        return ip.split(".")
            .mapNotNull { it.toLongOrNull() }
            .fold(0L) { acc, value ->
                (acc shl 8) + value
            }
    }
}
