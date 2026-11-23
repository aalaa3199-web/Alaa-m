package com.alaa.m

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class AlaaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { _, _ ->
            // Prevent hard silent crash loops on MIUI background threads
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme {
                    AlaaNetScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlaaNetScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var hasRoot by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var isNetworkLocked by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<NetworkDevice>>(emptyList()) }
    var statusMessage by remember { mutableStateOf("جاهز لفحص الشبكة المحلية...") }

    val blockedIps = remember { mutableStateMapOf<String, Boolean>() }
    val blockedMacs = remember { mutableStateMapOf<String, Boolean>() }
    val blockedSites = remember { mutableStateMapOf<String, MutableSet<String>>() }
    val allowedSites = remember { mutableStateMapOf<String, MutableSet<String>>() }

    var selectedDeviceForSites by remember { mutableStateOf<NetworkDevice?>(null) }
    var siteInput by remember { mutableStateOf("") }

    fun triggerScan(showToast: Boolean = false) {
        if (isScanning) return
        scope.launch {
            isScanning = true
            try {
                val rootOk = withContext(Dispatchers.IO) { RootNetEngine.checkRootAccess() }
                hasRoot = rootOk

                val scanned = withContext(Dispatchers.IO) {
                    RootNetEngine.scanNetwork(
                        context = context,
                        blockedIps = blockedIps.filterValues { it }.keys,
                        blockedMacs = blockedMacs.filterValues { it }.keys,
                        blockedSitesByDevice = blockedSites.mapValues { it.value.toSet() },
                        allowedSitesByDevice = allowedSites.mapValues { it.value.toSet() }
                    )
                }
                devices = scanned
                statusMessage = if (rootOk) {
                    "تم الفحص بصلاحيات الجذر (Root) • الأجهزة النشطة: ${scanned.size}"
                } else {
                    "تنبيه: لم يتم تأكيد صلاحية الجذر (su) بعد — بعض أوامر الحظر تتطلب Root"
                }
                if (showToast) {
                    snackbarHostState.showSnackbar("تم تحديث بيانات الشبكة والأجهزة")
                }
            } catch (e: Throwable) {
                statusMessage = "حدث خطأ أثناء الفحص: ${e.message ?: "غير معروف"}"
            } finally {
                isScanning = false
            }
        }
    }

    LaunchedEffect(Unit) {
        triggerScan(showToast = false)
        while (true) {
            delay(6000L)
            triggerScan(showToast = false)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Alaa.m",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { triggerScan(showToast = true) },
                        enabled = !isScanning
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "تحديث"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (hasRoot) Icons.Default.CheckCircle else Icons.Default.LockOpen,
                                contentDescription = null,
                                tint = if (hasRoot) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (hasRoot) "صلاحية الجذر: مفعلة" else "صلاحية الجذر: غير مفعلة",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                val nextLock = !isNetworkLocked
                                val (ok, msg) = withContext(Dispatchers.IO) {
                                    RootNetEngine.applyAllNetworkBlock(nextLock)
                                }
                                if (ok) {
                                    isNetworkLocked = nextLock
                                    snackbarHostState.showSnackbar(
                                        if (nextLock) "تم حظر الشبكة بالكامل" else "تم فك حظر الشبكة بالكامل"
                                    )
                                } else {
                                    snackbarHostState.showSnackbar(
                                        if (msg.isBlank()) "تعذر تنفيذ أمر حظر الشبكة" else msg
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isNetworkLocked)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            imageVector = if (isNetworkLocked) Icons.Default.LockOpen else Icons.Default.Lock,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isNetworkLocked) "إلغاء حظر الشبكة بالكامل" else "حظر الشبكة بالكامل"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (devices.isEmpty() && isScanning) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("جاري فحص الأجهزة والمنافذ...")
                    }
                }
            } else if (devices.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("لا توجد أجهزة ظاهرة حالياً. اضغط تحديث لإعادة الفحص.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(devices, key = { it.ip }) { device ->
                        DeviceCard(
                            device = device,
                            onToggleBlockDevice = { target ->
                                scope.launch {
                                    val newBlockedState = !target.isBlocked
                                    val (ok, msg) = withContext(Dispatchers.IO) {
                                        RootNetEngine.applyDeviceBlock(target, newBlockedState)
                                    }
                                    if (ok) {
                                        blockedIps[target.ip] = newBlockedState
                                        blockedMacs[target.mac.lowercase()] = newBlockedState
                                        devices = devices.map {
                                            if (it.ip == target.ip) it.copy(isBlocked = newBlockedState) else it
                                        }
                                        snackbarHostState.showSnackbar(
                                            if (newBlockedState) "تم حظر الجهاز ${target.ip}" else "تم فك حظر الجهاز ${target.ip}"
                                        )
                                    } else {
                                        snackbarHostState.showSnackbar(
                                            if (msg.isBlank()) "فشل تطبيق الحظر على الجهاز" else msg
                                        )
                                    }
                                }
                            },
                            onOpenSitesManager = { target ->
                                siteInput = ""
                                selectedDeviceForSites = target
                            }
                        )
                    }
                }
            }
        }
    }

    val currentTarget = selectedDeviceForSites
    if (currentTarget != null) {
        val currentBlockedSet = blockedSites.getOrPut(currentTarget.ip) { mutableSetOf() }
        val currentAllowedSet = allowedSites.getOrPut(currentTarget.ip) { mutableSetOf() }

        AlertDialog(
            onDismissRequest = { selectedDeviceForSites = null },
            title = {
                Text(
                    text = "إدارة مواقع الجهاز (${currentTarget.ip})",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "المواقع المرصودة والمحددة:",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    if (currentTarget.visitedSites.isEmpty()) {
                        Text(
                            text = "لا توجد مواقع مسجلة حالياً — يمكنك إضافة نطاق يدوياً بالأسفل.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        currentTarget.visitedSites.take(6).forEach { site ->
                            val isSiteBlocked = currentBlockedSet.contains(site)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = site,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    FilterChip(
                                        selected = isSiteBlocked,
                                        onClick = {
                                            val nextBlock = !isSiteBlocked
                                            scope.launch {
                                                val (ok, _) = withContext(Dispatchers.IO) {
                                                    RootNetEngine.applySiteRule(currentTarget.ip, site, nextBlock)
                                                }
                                                if (ok) {
                                                    if (nextBlock) {
                                                        currentBlockedSet.add(site)
                                                        currentAllowedSet.remove(site)
                                                    } else {
                                                        currentBlockedSet.remove(site)
                                                    }
                                                    snackbarHostState.showSnackbar(
                                                        if (nextBlock) "تم حظر $site" else "تم السماح بـ $site"
                                                    )
                                                } else {
                                                    snackbarHostState.showSnackbar("تعذر تحديث قاعدة الموقع")
                                                }
                                            }
                                        },
                                        label = {
                                            Text(if (isSiteBlocked) "محظور" else "حظر")
                                        }
                                    )
                                    FilterChip(
                                        selected = currentAllowedSet.contains(site),
                                        onClick = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    RootNetEngine.applySiteRule(currentTarget.ip, site, false)
                                                }
                                                currentAllowedSet.add(site)
                                                currentBlockedSet.remove(site)
                                                snackbarHostState.showSnackbar("تم ترك $site متاحاً")
                                            }
                                        },
                                        label = { Text("ترك") }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = siteInput,
                        onValueChange = { siteInput = it },
                        singleLine = true,
                        label = { Text("نطاق موقع مثل example.com") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val domain = siteInput.trim().lowercase()
                                if (domain.isNotEmpty()) {
                                    scope.launch {
                                        val (ok, _) = withContext(Dispatchers.IO) {
                                            RootNetEngine.applySiteRule(currentTarget.ip, domain, true)
                                        }
                                        if (ok) {
                                            currentBlockedSet.add(domain)
                                            currentAllowedSet.remove(domain)
                                            siteInput = ""
                                            snackbarHostState.showSnackbar("تم حظر الموقع $domain")
                                        } else {
                                            snackbarHostState.showSnackbar("تعذر حظر النطاق")
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("حظر الموقع")
                        }
                        OutlinedButton(
                            onClick = {
                                val domain = siteInput.trim().lowercase()
                                if (domain.isNotEmpty()) {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            RootNetEngine.applySiteRule(currentTarget.ip, domain, false)
                                        }
                                        currentAllowedSet.add(domain)
                                        currentBlockedSet.remove(domain)
                                        siteInput = ""
                                        snackbarHostState.showSnackbar("تم السماح بالموقع $domain")
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("ترك الموقع")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedDeviceForSites = null }) {
                    Text("إغلاق")
                }
            }
        )
    }
}

@Composable
private fun DeviceCard(
    device: NetworkDevice,
    onToggleBlockDevice: (NetworkDevice) -> Unit,
    onOpenSitesManager: (NetworkDevice) -> Unit
) {
    val portsSummary = if (device.openPorts.isEmpty()) {
        "0"
    } else {
        device.openPorts.joinToString("، ") { "${it.port} (${it.service})" }
    }

    val sitesSummary = if (device.visitedSites.isEmpty()) {
        "لا توجد مواقع ظاهرة حالياً"
    } else {
        device.visitedSites.joinToString(" • ")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (device.isBlocked)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            else
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Devices,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = device.hostname,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "IP: ${device.ip}  |  MAC: ${device.mac}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                if (device.isBlocked) {
                    Text(
                        text = "محظور",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "النظام المتوقع: ${device.os}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "الاستهلاك الفعلي: ↓ ${formatSpeed(device.rxRateBps)}  ↑ ${formatSpeed(device.txRateBps)}  (الإجمالي: ${formatBytes(device.rxBytes + device.txBytes)})",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "المنافذ المفتوحة (${device.openPorts.size}): $portsSummary",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "المواقع: $sitesSummary",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onOpenSitesManager(device) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("المواقع والحظر")
                }

                Button(
                    onClick = { onToggleBlockDevice(device) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (device.isBlocked)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (device.isBlocked) "فك حظر الجهاز" else "حظر الجهاز")
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    return when {
        safe >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", safe / (1024.0 * 1024.0 * 1024.0))
        safe >= 1024L * 1024L -> String.format(Locale.US, "%.2f MB", safe / (1024.0 * 1024.0))
        safe >= 1024L -> String.format(Locale.US, "%.1f KB", safe / 1024.0)
        else -> "$safe B"
    }
}

private fun formatSpeed(bytesPerSec: Long): String {
    return "${formatBytes(bytesPerSec)}/s"
}
