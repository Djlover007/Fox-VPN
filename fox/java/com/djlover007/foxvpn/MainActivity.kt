package com.djlover007.foxvpn

import android.Manifest
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Fox {
    val orange = Color(0xFFFF7A1A)
    val orangeDark = Color(0xFF3A2410)
    val bg = Color(0xFF101014)
    val surface = Color(0xFF1C1C22)
    val surface2 = Color(0xFF26262E)
    val text = Color(0xFFF2F2F2)
    val muted = Color(0xFF9A9AA5)
    val green = Color(0xFF3DDC84)
    val red = Color(0xFFFF5A5A)
}

@Composable
fun FoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Fox.orange,
            onPrimary = Color.Black,
            secondaryContainer = Fox.orangeDark,
            onSecondaryContainer = Fox.orange,
            background = Fox.bg,
            onBackground = Fox.text,
            surface = Fox.surface,
            onSurface = Fox.text,
            surfaceVariant = Fox.surface2,
            onSurfaceVariant = Fox.muted,
        ),
        content = content,
    )
}

class MainActivity : ComponentActivity() {

    private lateinit var browser: FoxBrowser

    private var servers by mutableStateOf<List<VpnServer>>(emptyList())
    private var loading by mutableStateOf(false)
    private var selectedCountry by mutableStateOf<String?>(null) // null = automatisch schnellster
    private var serverOffset by mutableIntStateOf(0)
    private var currentServer by mutableStateOf<VpnServer?>(null)
    private var info by mutableStateOf<String?>(null)
    private var ipInfo by mutableStateOf("…")
    private var pendingServer: VpnServer? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val s = pendingServer
            pendingServer = null
            if (result.resultCode == RESULT_OK && s != null) {
                connectTo(s)
            } else {
                info = "Freigabe wurde abgelehnt – ohne Freigabe kann Fox VPN nicht verbinden."
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FoxEngine.register()
        browser = FoxBrowser(this)
        if (Build.VERSION.SDK_INT >= 33) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { FoxTheme { FoxApp() } }
        loadServers()
    }

    override fun onDestroy() {
        browser.destroy()
        super.onDestroy()
    }

    // ---------- Logik ----------

    private fun loadServers() {
        if (loading) return
        loading = true
        lifecycleScope.launch {
            try {
                servers = VpnGate.load()
                info = null
            } catch (e: Exception) {
                info = "Serverliste konnte nicht geladen werden (${e.message ?: "Netzwerkfehler"})."
            } finally {
                loading = false
            }
        }
    }

    private fun candidates(): List<VpnServer> = servers
        .filter { selectedCountry == null || it.countryShort == selectedCountry }
        .sortedByDescending { it.score }

    private fun pickServer(): VpnServer? {
        val list = candidates()
        if (list.isEmpty()) return null
        return list[serverOffset % list.size]
    }

    private fun connectTo(server: VpnServer) {
        val vpnPermission = VpnService.prepare(this)
        if (vpnPermission != null) {
            pendingServer = server
            permissionLauncher.launch(vpnPermission)
            return
        }
        currentServer = server
        info = null
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { FoxEngine.start(applicationContext, server) }
            } catch (e: Exception) {
                info = "Fehler beim Verbinden: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun disconnect() {
        try {
            FoxEngine.stop(this)
        } catch (e: Exception) {
            info = "Trennen fehlgeschlagen: ${e.message}"
        }
    }

    // ---------- Oberfläche ----------

    @Composable
    private fun FoxApp() {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var showCountries by remember { mutableStateOf(false) }
        Scaffold(
            containerColor = Fox.bg,
            bottomBar = {
                NavigationBar(containerColor = Fox.surface) {
                    val colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Fox.orange,
                        selectedTextColor = Fox.orange,
                        indicatorColor = Fox.orangeDark,
                        unselectedIconColor = Fox.muted,
                        unselectedTextColor = Fox.muted,
                    )
                    NavigationBarItem(
                        selected = tab == 0, onClick = { tab = 0 },
                        icon = { Icon(Icons.Filled.Shield, contentDescription = null) },
                        label = { Text("VPN") }, colors = colors,
                    )
                    NavigationBarItem(
                        selected = tab == 1, onClick = { tab = 1 },
                        icon = { Icon(Icons.Filled.Public, contentDescription = null) },
                        label = { Text("Browser") }, colors = colors,
                    )
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when {
                    tab == 0 && showCountries -> CountryScreen(onClose = { showCountries = false })
                    tab == 0 -> VpnScreen(onPickCountry = { showCountries = true })
                    else -> BrowserScreen()
                }
            }
        }
    }

    @Composable
    private fun VpnScreen(onPickCountry: () -> Unit) {
        val state = FoxEngine.state.value
        val conn = FoxEngine.conn()

        LaunchedEffect(conn) {
            if (conn != Conn.CONNECTING) {
                ipInfo = "…"
                delay(1500)
                ipInfo = IpCheck.load()
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🦊 Fox VPN", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Fox.orange)
            Text("Land wählen, tippen, fertig.", color = Fox.muted, fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))

            // Großer Knopf
            val ringColor = when (conn) {
                Conn.ON -> Fox.green
                Conn.CONNECTING -> Fox.orange
                Conn.ERROR -> Fox.red
                Conn.OFF -> Fox.surface2
            }
            Box(
                Modifier
                    .size(190.dp)
                    .clip(CircleShape)
                    .background(if (conn == Conn.ON) Fox.orange else Fox.surface)
                    .border(6.dp, ringColor, CircleShape)
                    .clickable {
                        when (conn) {
                            Conn.ON, Conn.CONNECTING -> disconnect()
                            else -> {
                                val s = pickServer()
                                if (s == null) {
                                    info = if (loading) "Serverliste lädt noch …" else "Keine Server da – Liste neu laden."
                                } else connectTo(s)
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (conn == Conn.CONNECTING) {
                    CircularProgressIndicator(color = Fox.orange, modifier = Modifier.size(80.dp), strokeWidth = 5.dp)
                } else {
                    Icon(
                        Icons.Filled.PowerSettingsNew, contentDescription = "Verbinden",
                        tint = if (conn == Conn.ON) Color.Black else Fox.orange,
                        modifier = Modifier.size(84.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            val statusColor = when (conn) {
                Conn.ON -> Fox.green
                Conn.ERROR -> Fox.red
                Conn.CONNECTING -> Fox.orange
                Conn.OFF -> Fox.muted
            }
            Text(
                if (conn == Conn.ON) "Geschützt" else stateText(state),
                color = statusColor, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            )
            currentServer?.takeIf { conn != Conn.OFF }?.let {
                Text("${it.flag} ${it.countryName} · ${it.host}", color = Fox.muted, fontSize = 13.sp)
            }
            Text(
                if (conn == Conn.ON || conn == Conn.CONNECTING) "Zum Trennen nochmal tippen" else "Zum Verbinden tippen",
                color = Fox.muted, fontSize = 12.sp,
            )

            Spacer(Modifier.height(20.dp))

            // Länderwahl
            val label = selectedCountry?.let { code ->
                val first = servers.firstOrNull { it.countryShort == code }
                "${flagEmoji(code)}  ${first?.countryName ?: code}"
            } ?: "⚡  Schnellster Server (automatisch)"
            Card(
                colors = CardDefaults.cardColors(containerColor = Fox.surface),
                modifier = Modifier.fillMaxWidth().clickable { onPickCountry() },
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Standort", color = Fox.muted, fontSize = 12.sp)
                        Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = Fox.orange)
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        serverOffset += 1
                        val s = pickServer()
                        if (s != null && conn != Conn.OFF) connectTo(s)
                        else info = s?.let { "Nächster Versuch: ${it.flag} ${it.host}" }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.SwapHoriz, null, tint = Fox.orange)
                    Spacer(Modifier.width(6.dp))
                    Text("Anderer Server", color = Fox.text)
                }
                OutlinedButton(onClick = { loadServers() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Refresh, null, tint = Fox.orange)
                    Spacer(Modifier.width(6.dp))
                    Text(if (loading) "Lädt …" else "Neu laden", color = Fox.text)
                }
            }

            info?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = Fox.red, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            if (conn == Conn.ERROR || state == "CONNECTRETRY") {
                Text(
                    "Tipp: Mit „Anderer Server“ klappt es meist.",
                    color = Fox.muted, fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Fox.surface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Deine sichtbare IP", color = Fox.muted, fontSize = 12.sp)
                    Text(ipInfo, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${servers.size} Server in ${servers.map { it.countryShort }.distinct().size} Ländern",
                        color = Fox.muted, fontSize = 12.sp,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Server: VPN Gate (Uni Tsukuba, von Freiwilligen betrieben). Gut für Länderwechsel und Streaming – " +
                    "für Banking und Passwörter lieber ohne. VPN-Motor: OpenVPN (ics-openvpn, GPLv2).",
                color = Fox.muted, fontSize = 11.sp, textAlign = TextAlign.Center,
            )
        }
    }

    @Composable
    private fun CountryScreen(onClose: () -> Unit) {
        BackHandler { onClose() }
        val groups = remember(servers) {
            servers.groupBy { it.countryShort }
                .map { (code, list) -> Triple(code, list.first().countryName, list) }
                .sortedBy { it.second }
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück", tint = Fox.orange)
                }
                Text("Standort wählen", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    CountryRow(
                        title = "⚡  Schnellster Server",
                        subtitle = "Automatisch der beste aus ${servers.size} Servern",
                        selected = selectedCountry == null,
                    ) {
                        selectedCountry = null; serverOffset = 0; onClose()
                    }
                }
                items(groups, key = { it.first }) { (code, name, list) ->
                    val bestPing = list.mapNotNull { it.ping }.filter { it > 0 }.minOrNull()
                    val bestSpeed = list.maxOf { it.speedMbit }
                    CountryRow(
                        title = "${flagEmoji(code)}  $name",
                        subtitle = "${list.size} Server" +
                            (bestPing?.let { " · $it ms" } ?: "") +
                            " · bis $bestSpeed Mbit/s",
                        selected = selectedCountry == code,
                    ) {
                        selectedCountry = code; serverOffset = 0; onClose()
                    }
                }
                if (servers.isEmpty()) {
                    item {
                        Text(
                            if (loading) "Lade Serverliste …" else "Keine Server geladen.",
                            color = Fox.muted, modifier = Modifier.padding(20.dp),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun CountryRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(if (selected) Fox.orangeDark else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Text(title, fontSize = 17.sp, color = if (selected) Fox.orange else Fox.text)
            Text(subtitle, fontSize = 12.sp, color = Fox.muted)
        }
        HorizontalDivider(color = Fox.surface2)
    }

    @Composable
    private fun BrowserScreen() {
        val focus = LocalFocusManager.current
        var input by remember { mutableStateOf(browser.url) }
        LaunchedEffect(browser.url) { input = browser.url }
        LaunchedEffect(Unit) { if (browser.webView.url == null) browser.home() }
        BackHandler(enabled = browser.canBack) { browser.webView.goBack() }

        Column(Modifier.fillMaxSize().imePadding()) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                placeholder = { Text("Suchen oder Adresse eingeben") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
                keyboardActions = KeyboardActions(onGo = {
                    browser.open(input)
                    focus.clearFocus()
                }),
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Fox.orange,
                    unfocusedBorderColor = Fox.surface2,
                    cursorColor = Fox.orange,
                ),
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { browser.webView.goBack() }, enabled = browser.canBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", tint = if (browser.canBack) Fox.orange else Fox.muted)
                }
                IconButton(onClick = { browser.webView.goForward() }, enabled = browser.canForward) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "Vor", tint = if (browser.canForward) Fox.orange else Fox.muted)
                }
                IconButton(onClick = { browser.webView.reload() }) {
                    Icon(Icons.Filled.Refresh, "Neu laden", tint = Fox.orange)
                }
                IconButton(onClick = { browser.home() }) {
                    Icon(Icons.Filled.Home, "Start", tint = Fox.orange)
                }
                IconButton(onClick = { browser.clearTraces() }) {
                    Icon(Icons.Filled.DeleteSweep, "Spuren löschen", tint = Fox.orange)
                }
                Spacer(Modifier.weight(1f))
                val vpnOn = FoxEngine.conn() == Conn.ON
                Icon(
                    if (vpnOn) Icons.Filled.Shield else Icons.Filled.Bolt, null,
                    tint = if (vpnOn) Fox.green else Fox.muted, modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("${browser.blocked} blockiert", color = Fox.muted, fontSize = 12.sp)
                Spacer(Modifier.width(10.dp))
            }
            if (browser.progress < 100) {
                LinearProgressIndicator(
                    progress = { browser.progress / 100f },
                    color = Fox.orange,
                    trackColor = Fox.surface2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AndroidView(
                factory = {
                    (browser.webView.parent as? ViewGroup)?.removeView(browser.webView)
                    browser.webView
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}
