
package com.alaa.m

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl
            ) {
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

    // ------------------------------------------------
    // APPLICATION STATES
    // ------------------------------------------------

    var hasRoot by remember {
        mutableStateOf(false)
    }

    var isScanning by remember {
        mutableStateOf(false)
    }

    var isNetworkLocked by remember {
        mutableStateOf(false)
    }

    var devices by remember {
        mutableStateOf<List<NetworkDevice>>(emptyList())
    }

    var statusMessage by remember {
        mutableStateOf("جاهز لفحص الشبكة المحلية")
    }

    var selectedDeviceForSites by remember {
        mutableStateOf<NetworkDevice?>(null)
    }

    var siteInput by remember {
        mutableStateOf("")
    }

    // منع تداخل عمليات الفحص
    val scanMutex = remember {
        Mutex()
    }

    // ------------------------------------------------
    // BLOCK STATES
    // ------------------------------------------------

    val blockedIps = remember {
        mutableStateMapOf<String, Boolean>()
    }

    val blockedMacs = remember {
        mutableStateMapOf<String, Boolean>()
    }

    // استخدام Set غير قابلة للتغيير داخل StateMap
    val blockedSites =
        remember {
            mutableStateMapOf<String, Set<String>>()
        }

    val allowedSites =
        remember {
            mutableStateMapOf<String, Set<String>>()
        }

    // ------------------------------------------------
    // ROOT CHECK
    // ------------------------------------------------

    suspend fun checkRoot(): Boolean {

        return try {

            withContext(Dispatchers.IO) {
                RootNetEngine.checkRootAccess()
            }

        } catch (e: CancellationException) {
            throw e

        } catch (_: Throwable) {
            false
        }
    }

    fun requestRootNow() {

        scope.launch {

            statusMessage = "جاري التحقق من صلاحية الروت..."

            val rootOk = checkRoot()

            hasRoot = rootOk

            statusMessage = if (rootOk) {
                "تم تأكيد صلاحية Root بنجاح"
            } else {
                "لم يتم الحصول على صلاحية Root. تحقق من Magisk"
            }

            snackbarHostState.showSnackbar(statusMessage)
        }
    }

    // ------------------------------------------------
    // NETWORK SCAN
    // ------------------------------------------------

    suspend fun performScan() {

        // إذا كان هناك فحص آخر فلا تبدأ فحصاً جديداً
        if (!scanMutex.tryLock()) {
            return
        }

        isScanning = true

        try {

            statusMessage = "جاري التحقق من صلاحية Root..."

            val rootOk = checkRoot()

            hasRoot = rootOk

            if (!rootOk) {

                statusMessage =
                    "الروت غير متاح. سيتم محاولة اكتشاف الأجهزة بدون صلاحيات الجذر"
            } else {

                statusMessage =
                    "تم تأكيد الروت. جاري فحص الشبكة..."
            }

            val blockedIpSnapshot =
                blockedIps.filterValues { it }.keys.toSet()

            val blockedMacSnapshot =
                blockedMacs.filterValues { it }.keys.toSet()

            val blockedSitesSnapshot =
                blockedSites.mapValues {
                    it.value.toSet()
                }

            val allowedSitesSnapshot =
                allowedSites.mapValues {
                    it.value.toSet()
                }

            statusMessage = "جاري اكتشاف الأجهزة المتصلة بالشبكة..."

            val scanned = withContext(Dispatchers.IO) {

                RootNetEngine.scanNetwork(
                    context = context,
                    blockedIps = blockedIpSnapshot,
                    blockedMacs = blockedMacSnapshot,
                    blockedSitesByDevice = blockedSitesSnapshot,
                    allowedSitesByDevice = allowedSitesSnapshot
                )
            }

            devices = scanned

            statusMessage = when {

                scanned.isNotEmpty() -> {
                    "اكتمل الفحص • الأجهزة المكتشفة: ${scanned.size}"
                }

                rootOk -> {
                    "انتهى الفحص، لكن لم يتم اكتشاف أجهزة. تحقق من الشبكة"
                }

                else -> {
                    "انتهى الفحص دون نتائج. تحقق من اتصال Wi-Fi وصلاحية Root"
                }
            }

        } catch (e: CancellationException) {

            throw e

        } catch (e: Throwable) {

            statusMessage =
                "فشل الفحص: ${e.message ?: e.javaClass.simpleName}"

        } finally {

            isScanning = false
            scanMutex.unlock()
        }
    }

    // ------------------------------------------------
    // AUTOMATIC SCAN
    // ------------------------------------------------

    LaunchedEffect(Unit) {

        // الفحص الأول
        performScan()

        // الفحص المتكرر كل 15 ثانية
        while (true) {

            delay(15_000L)

            performScan()
        }
    }

    // ------------------------------------------------
    // MANUAL SCAN
    // ------------------------------------------------

    fun triggerScan(showMessage: Boolean = false) {

        if (isScanning) return

        scope.launch {

            performScan()

            if (showMessage) {
                snackbarHostState.showSnackbar(
                    statusMessage
                )
            }
        }
    }

    // ------------------------------------------------
    // DEVICE BLOCK
    // ------------------------------------------------

    fun toggleDeviceBlock(target: NetworkDevice) {

        scope.launch {

            if (!hasRoot) {

                snackbarHostState.showSnackbar(
                    "لا يمكن تنفيذ الحظر دون صلاحية Root"
                )

                return@launch
            }

            val newBlockedState = !target.isBlocked

            val result = withContext(Dispatchers.IO) {

                RootNetEngine.applyDeviceBlock(
                    target,
                    newBlockedState
                )
            }

            val (success, message) = result

            if (success) {

                blockedIps[target.ip] = newBlockedState

                blockedMacs[target.mac.lowercase()] =
                    newBlockedState

                devices = devices.map { device ->

                    if (device.ip == target.ip) {
                        device.copy(
                            isBlocked = newBlockedState
                        )
                    } else {
                        device
                    }
                }

                snackbarHostState.showSnackbar(
                    if (newBlockedState) {
                        "تم إرسال أمر حظر الجهاز ${target.ip}"
                    } else {
                        "تم إرسال أمر فك الحظر ${target.ip}"
                    }
                )

            } else {

                snackbarHostState.showSnackbar(
                    message.ifBlank {
                        "فشل تنفيذ أمر الحظر"
                    }
                )
            }
        }
    }

    // ------------------------------------------------
    // GLOBAL NETWORK BLOCK
    // ------------------------------------------------

    fun toggleNetworkBlock() {

        scope.launch {

            if (!hasRoot) {

                snackbarHostState.showSnackbar(
                    "يجب منح صلاحية Root أولاً"
                )

                return@launch
            }

            val nextLock = !isNetworkLocked

            val (success, message) =
                withContext(Dispatchers.IO) {

                    RootNetEngine.applyAllNetworkBlock(
                        nextLock
                    )
                }

            if (success) {

                isNetworkLocked = nextLock

                snackbarHostState.showSnackbar(
                    if (nextLock) {
                        "تم تنفيذ أمر حظر الشبكة"
                    } else {
                        "تم تنفيذ أمر فك حظر الشبكة"
                    }
                )

            } else {

                snackbarHostState.showSnackbar(
                    message.ifBlank {
                        "تعذر تنفيذ أمر الشبكة"
                    }
                )
            }
        }
    }

    // ------------------------------------------------
    // MAIN INTERFACE
    // ------------------------------------------------

    Scaffold(

        topBar = {

            TopAppBar(

                title = {

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null
                        )

                        Spacer(
                            modifier = Modifier.width(8.dp)
                        )

                        Text(
                            text = "Alaa.m",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },

                actions = {

                    IconButton(
                        onClick = {
                            triggerScan(true)
                        },
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

        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState
            )
        }

    ) { innerPadding ->

        Column(

            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(
                    horizontal = 12.dp,
                    vertical = 8.dp
                )

        ) {

            // ----------------------------------------
            // STATUS CARD
            // ----------------------------------------

            Card(
                modifier = Modifier.fillMaxWidth(),

                colors = CardDefaults.cardColors(
                    containerColor =
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {

                Column(
                    modifier = Modifier.padding(12.dp)
                ) {

                    Row(

                        modifier = Modifier.fillMaxWidth(),

                        horizontalArrangement =
                            Arrangement.SpaceBetween,

                        verticalAlignment =
                            Alignment.CenterVertically

                    ) {

                        Row(
                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {

                            Icon(
                                imageVector =
                                    if (hasRoot) {
                                        Icons.Default.CheckCircle
                                    } else {
                                        Icons.Default.LockOpen
                                    },

                                contentDescription = null,

                                tint =
                                    if (hasRoot) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                            )

                            Spacer(
                                modifier = Modifier.width(6.dp)
                            )

                            Text(
                                text =
                                    if (hasRoot) {
                                        "صلاحية Root: مفعلة"
                                    } else {
                                        "صلاحية Root: غير مؤكدة"
                                    },

                                style =
                                    MaterialTheme.typography.titleSmall,

                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (!hasRoot) {

                            TextButton(
                                onClick = {
                                    requestRootNow()
                                }
                            ) {
                                Text("طلب الروت")
                            }
                        }
                    }

                    Spacer(
                        modifier = Modifier.height(6.dp)
                    )

                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodySmall
                    )

                    if (isScanning) {

                        Spacer(
                            modifier = Modifier.height(10.dp)
                        )

                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(
                        modifier = Modifier.height(10.dp)
                    )

                    Button(

                        onClick = {
                            toggleNetworkBlock()
                        },

                        modifier = Modifier.fillMaxWidth(),

                        colors = ButtonDefaults.buttonColors(
                            containerColor =
                                if (isNetworkLocked) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                        )

                    ) {

                        Icon(
                            imageVector =
                                if (isNetworkLocked) {
                                    Icons.Default.LockOpen
                                } else {
                                    Icons.Default.Lock
                                },

                            contentDescription = null
                        )

                        Spacer(
                            modifier = Modifier.width(8.dp)
                        )

                        Text(
                            text =
                                if (isNetworkLocked) {
                                    "إلغاء حظر الشبكة"
                                } else {
                                    "حظر الشبكة"
                                }
                        )
                    }
                }
            }

            Spacer(
                modifier = Modifier.height(10.dp)
            )

            // ----------------------------------------
            // SCAN RESULTS
            // ----------------------------------------

            if (devices.isEmpty()) {

                Box(

                    modifier = Modifier
                        .fillMaxSize(),

                    contentAlignment =
                        Alignment.Center

                ) {

                    Column(
                        horizontalAlignment =
                            Alignment.CenterHorizontally
                    ) {

                        if (isScanning) {

                            CircularProgressIndicator()

                            Spacer(
                                modifier = Modifier.height(12.dp)
                            )

                            Text(
                                "جاري فحص الأجهزة..."
                            )

                        } else {

                            Icon(
                                imageVector = Icons.Default.Devices,
                                contentDescription = null,
                                modifier = Modifier.size(42.dp)
                            )

                            Spacer(
                                modifier = Modifier.height(12.dp)
                            )

                            Text(
                                "لا توجد أجهزة ظاهرة حالياً"
                            )

                            Spacer(
                                modifier = Modifier.height(8.dp)
                            )

                            OutlinedButton(
                                onClick = {
                                    triggerScan(true)
                                }
                            ) {
                                Text("إعادة الفحص")
                            }
                        }
                    }
                }

            } else {

                LazyColumn(

                    modifier = Modifier.fillMaxSize(),

                    contentPadding = PaddingValues(
                        bottom = 20.dp
                    ),

                    verticalArrangement =
                        Arrangement.spacedBy(10.dp)

                ) {

                    items(
                        items = devices,
                        key = { it.ip }
                    ) { device ->

                        DeviceCard(

                            device = device,

                            onToggleBlockDevice = {
                                toggleDeviceBlock(it)
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

    // ------------------------------------------------
    // SITE MANAGEMENT DIALOG
    // ------------------------------------------------

    val currentTarget = selectedDeviceForSites

    if (currentTarget != null) {

        val ip = currentTarget.ip

        val currentBlockedSet =
            blockedSites[ip].orEmpty()

        val currentAllowedSet =
            allowedSites[ip].orEmpty()

        AlertDialog(

            onDismissRequest = {
                selectedDeviceForSites = null
            },

            title = {

                Text(
                    text = "إدارة مواقع الجهاز ($ip)",
                    fontWeight = FontWeight.Bold
                )
            },

            text = {

                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {

                    Text(
                        "المواقع المرصودة والمحددة:",
                        style = MaterialTheme.typography.labelMedium
                    )

                    Spacer(
                        modifier = Modifier.height(6.dp)
                    )

                    if (currentTarget.visitedSites.isEmpty()) {

                        Text(
                            "لا توجد عناوين مرصودة حالياً. يمكنك إضافة نطاق يدوياً.",
                            style = MaterialTheme.typography.bodySmall
                        )

                    } else {

                        currentTarget.visitedSites
                            .take(8)
                            .forEach { site ->

                                val isBlocked =
                                    site in currentBlockedSet

                                val isAllowed =
                                    site in currentAllowedSet

                                Row(

                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp),

                                    horizontalArrangement =
                                        Arrangement.SpaceBetween,

                                    verticalAlignment =
                                        Alignment.CenterVertically

                                ) {

                                    Text(
                                        text = site,
                                        style =
                                            MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )

                                    Row(
                                        horizontalArrangement =
                                            Arrangement.spacedBy(4.dp)
                                    ) {

                                        FilterChip(

                                            selected = isBlocked,

                                            onClick = {

                                                val nextBlock = !isBlocked

                                                scope.launch {

                                                    if (!hasRoot) {

                                                        snackbarHostState.showSnackbar(
                                                            "صلاحية Root غير متاحة"
                                                        )

                                                        return@launch
                                                    }

                                                    val (success, message) =
                                                        withContext(Dispatchers.IO) {

                                                            RootNetEngine.applySiteRule(
                                                                ip,
                                                                site,
                                                                nextBlock
                                                            )
                                                        }

                                                    if (success) {

                                                        blockedSites[ip] =
                                                            if (nextBlock) {
                                                                currentBlockedSet + site
                                                            } else {
                                                                currentBlockedSet - site
                                                            }

                                                        if (nextBlock) {
                                                            allowedSites[ip] =
                                                                currentAllowedSet - site
                                                        }

                                                        snackbarHostState.showSnackbar(
                                                            if (nextBlock) {
                                                                "تم إرسال أمر حظر $site"
                                                            } else {
                                                                "تم إرسال أمر فك حظر $site"
                                                            }
                                                        )

                                                    } else {

                                                        snackbarHostState.showSnackbar(
                                                            message.ifBlank {
                                                                "تعذر تطبيق قاعدة الموقع"
                                                            }
                                                        )
                                                    }
                                                }
                                            },

                                            label = {
                                                Text(
                                                    if (isBlocked) "محظور" else "حظر"
                                                )
                                            }
                                        )

                                        FilterChip(

                                            selected = isAllowed,

                                            onClick = {

                                                scope.launch {

                                                    if (!hasRoot) {

                                                        snackbarHostState.showSnackbar(
                                                            "صلاحية Root غير متاحة"
                                                        )

                                                        return@launch
                                                    }

                                                    val (success, message) =
                                                        withContext(Dispatchers.IO) {

                                                            RootNetEngine.applySiteRule(
                                                                ip,
                                                                site,
                                                                false
                                                            )
                                                        }

                                                    if (success) {

                                                        allowedSites[ip] =
                                                            currentAllowedSet + site

                                                        blockedSites[ip] =
                                                            currentBlockedSet - site

                                                        snackbarHostState.showSnackbar(
                                                            "تم إرسال أمر السماح للموقع"
                                                        )

                                                    } else {

                                                        snackbarHostState.showSnackbar(
                                                            message.ifBlank {
                                                                "تعذر تطبيق قاعدة الموقع"
                                                            }
                                                        )
                                                    }
                                                }
                                            },

                                            label = {
                                                Text("سماح")
                                            }
                                        )
                                    }
                                }
                            }
                    }

                    Spacer(
                        modifier = Modifier.height(10.dp)
                    )

                    OutlinedTextField(

                        value = siteInput,

                        onValueChange = {
                            siteInput = it
                        },

                        singleLine = true,

                        label = {
                            Text("نطاق مثل example.com")
                        },

                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(
                        modifier = Modifier.height(8.dp)
                    )

                    Row(

                        modifier = Modifier.fillMaxWidth(),

                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp)

                    ) {

                        Button(

                            onClick = {

                                val domain =
                                    siteInput.trim().lowercase()

                                if (domain.isNotEmpty()) {

                                    scope.launch {

                                        if (!hasRoot) {
                                            snackbarHostState.showSnackbar(
                                                "صلاحية Root غير متاحة"
                                            )
                                            return@launch
                                        }

                                        val (success, message) =
                                            withContext(Dispatchers.IO) {

                                                RootNetEngine.applySiteRule(
                                                    ip,
                                                    domain,
                                                    true
                                                )
                                            }

                                        if (success) {

                                            blockedSites[ip] =
                                                currentBlockedSet + domain

                                            allowedSites[ip] =
                                                currentAllowedSet - domain

                                            siteInput = ""

                                            snackbarHostState.showSnackbar(
                                                "تم إرسال أمر حظر النطاق"
                                            )

                                        } else {

                                            snackbarHostState.showSnackbar(
                                                message.ifBlank {
                                                    "تعذر حظر النطاق"
                                                }
                                            )
                                        }
                                    }
                                }
                            },

                            modifier = Modifier.weight(1f)
                        ) {
                            Text("حظر")
                        }

                        OutlinedButton(

                            onClick = {

                                val domain =
                                    siteInput.trim().lowercase()

                                if (domain.isNotEmpty()) {

                                    scope.launch {

                                        if (!hasRoot) {
                                            snackbarHostState.showSnackbar(
                                                "صلاحية Root غير متاحة"
                                            )
                                            return@launch
                                        }

                                        val (success, message) =
                                            withContext(Dispatchers.IO) {

                                                RootNetEngine.applySiteRule(
                                                    ip,
                                                    domain,
                                                    false
                                                )
                                            }

                                        if (success) {

                                            allowedSites[ip] =
                                                currentAllowedSet + domain

                                            blockedSites[ip] =
                                                currentBlockedSet - domain

                                            siteInput = ""

                                            snackbarHostState.showSnackbar(
                                                "تم إرسال أمر السماح للنطاق"
                                            )

                                        } else {

                                            snackbarHostState.showSnackbar(
                                                message.ifBlank {
                                                    "تعذر السماح بالنطاق"
                                                }
                                            )
                                        }
                                    }
                                }
                            },

                            modifier = Modifier.weight(1f)
                        ) {
                            Text("سماح")
                        }
                    }
                }
            },

            confirmButton = {

                TextButton(
                    onClick = {
                        selectedDeviceForSites = null
                    }
                ) {
                    Text("إغلاق")
                }
            }
        )
    }
}

// ====================================================
// DEVICE CARD
// ====================================================

@Composable
private fun DeviceCard(

    device: NetworkDevice,

    onToggleBlockDevice: (NetworkDevice) -> Unit,

    onOpenSitesManager: (NetworkDevice) -> Unit

) {

    val portsSummary =
        if (device.openPorts.isEmpty()) {
            "لا توجد منافذ مكتشفة"
        } else {
            device.openPorts.joinToString("، ") {
                "${it.port} (${it.service})"
            }
        }

    val sitesSummary =
        if (device.visitedSites.isEmpty()) {
            "لا توجد عناوين مرصودة"
        } else {
            device.visitedSites.joinToString(" • ")
        }

    Card(

        modifier = Modifier.fillMaxWidth(),

        colors = CardDefaults.cardColors(

            containerColor =
                if (device.isBlocked) {
                    MaterialTheme.colorScheme.errorContainer.copy(
                        alpha = 0.35f
                    )
                } else {
                    MaterialTheme.colorScheme.surface
                }
        ),

        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        )

    ) {

        Column(
            modifier = Modifier.padding(12.dp)
        ) {

            Row(

                modifier = Modifier.fillMaxWidth(),

                horizontalArrangement =
                    Arrangement.SpaceBetween,

                verticalAlignment =
                    Alignment.CenterVertically

            ) {

                Row(

                    verticalAlignment =
                        Alignment.CenterVertically,

                    modifier = Modifier.weight(1f)

                ) {

                    Icon(
                        imageVector = Icons.Default.Devices,
                        contentDescription = null
                    )

                    Spacer(
                        modifier = Modifier.width(8.dp)
                    )

                    Column {

                        Text(
                            text = device.hostname,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "IP: ${device.ip}",
                            style = MaterialTheme.typography.bodySmall
                        )

                        Text(
                            text = "MAC: ${device.mac}",
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

            Spacer(
                modifier = Modifier.height(8.dp)
            )

            Text(
                text = "النظام المتوقع: ${device.os}",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(
                modifier = Modifier.height(4.dp)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {

                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )

                Spacer(
                    modifier = Modifier.width(4.dp)
                )

                Text(
                    text =
                        "↓ ${formatSpeed(device.rxRateBps)}  " +
                        "↑ ${formatSpeed(device.txRateBps)}",

                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(
                modifier = Modifier.height(4.dp)
            )

            Text(
                text =
                    "إجمالي العدادات: ${formatBytes(device.rxBytes + device.txBytes)}",

                style = MaterialTheme.typography.bodySmall
            )

            Spacer(
                modifier = Modifier.height(4.dp)
            )

            Text(
                text =
                    "المنافذ (${device.openPorts.size}): $portsSummary",

                style = MaterialTheme.typography.bodySmall
            )

            Spacer(
                modifier = Modifier.height(4.dp)
            )

            Text(
                text = "العناوين: $sitesSummary",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(
                modifier = Modifier.height(10.dp)
            )

            Row(

                modifier = Modifier.fillMaxWidth(),

                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)

            ) {

                OutlinedButton(

                    onClick = {
                        onOpenSitesManager(device)
                    },

                    modifier = Modifier.weight(1f)
                ) {
                    Text("المواقع")
                }

                Button(

                    onClick = {
                        onToggleBlockDevice(device)
                    },

                    modifier = Modifier.weight(1f),

                    colors = ButtonDefaults.buttonColors(

                        containerColor =
                            if (device.isBlocked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                    )

                ) {

                    Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )

                    Spacer(
                        modifier = Modifier.width(6.dp)
                    )

                    Text(
                        if (device.isBlocked) {
                            "فك الحظر"
                        } else {
                            "حظر الجهاز"
                        }
                    )
                }
            }
        }
    }
}

// ====================================================
// FORMATTERS
// ====================================================

private fun formatBytes(bytes: Long): String {

    val safe = bytes.coerceAtLeast(0L)

    return when {

        safe >= 1024L * 1024L * 1024L ->

            String.format(
                Locale.US,
                "%.2f GB",
                safe / (1024.0 * 1024.0 * 1024.0)
            )

        safe >= 1024L * 1024L ->

            String.format(
                Locale.US,
                "%.2f MB",
                safe / (1024.0 * 1024.0)
            )

        safe >= 1024L ->

            String.format(
                Locale.US,
                "%.1f KB",
                safe / 1024.0
            )

        else -> "$safe B"
    }
}

private fun formatSpeed(bytesPerSec: Long): String {

    return "${formatBytes(bytesPerSec)}/s"
}
