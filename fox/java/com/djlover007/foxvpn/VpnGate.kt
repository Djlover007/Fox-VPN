package com.djlover007.foxvpn

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class VpnServer(
    val host: String,
    val ip: String,
    val score: Long,
    val ping: Int?,
    val speed: Long,
    val countryShort: String,
    val countryLong: String,
    val sessions: Int,
    val configBase64: String,
) {
    val countryName: String get() = countryDisplayName(countryShort, countryLong)
    val flag: String get() = flagEmoji(countryShort)
    val speedMbit: Long get() = speed / 1_000_000
}

/** Kostenlose Server vom VPN-Gate-Projekt (Universität Tsukuba). */
object VpnGate {
    private val urls = listOf(
        "https://www.vpngate.net/api/iphone/",
        "http://www.vpngate.net/api/iphone/",
    )

    suspend fun load(): List<VpnServer> = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (u in urls) {
            try {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 40_000
                conn.setRequestProperty("User-Agent", "FoxVPN/1.0 (Android)")
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val list = parse(text)
                if (list.isNotEmpty()) return@withContext list
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Keine Server gefunden")
    }

    fun parse(text: String): List<VpnServer> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("*") && !it.startsWith("#") }
        .mapNotNull { line ->
            val p = line.split(",")
            if (p.size < 15) return@mapNotNull null
            try {
                VpnServer(
                    host = p[0],
                    ip = p[1],
                    score = p[2].toLongOrNull() ?: 0,
                    ping = p[3].toIntOrNull(),
                    speed = p[4].toLongOrNull() ?: 0,
                    countryLong = p[5],
                    countryShort = p[6].uppercase(Locale.ROOT),
                    sessions = p[7].toIntOrNull() ?: 0,
                    // Die Konfiguration ist immer das letzte Feld (die Nachricht davor kann Kommas enthalten)
                    configBase64 = p.last().trim(),
                )
            } catch (e: Exception) {
                null
            }
        }
        .filter { it.configBase64.length > 100 }
        .toList()

    fun config(server: VpnServer): String {
        val raw = String(Base64.decode(server.configBase64, Base64.DEFAULT), Charsets.UTF_8)
            .replace("\r\n", "\n")
        // VPN Gate nutzt noch AES-128-CBC – für neuere OpenVPN-Versionen ausdrücklich erlauben
        return raw.trimEnd() + "\n\ndata-ciphers AES-256-GCM:AES-128-GCM:AES-128-CBC\n"
    }
}

object IpCheck {
    suspend fun load(): String = withContext(Dispatchers.IO) {
        try {
            val conn = URL("https://ipwho.is/").openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val flag = j.optJSONObject("flag")?.optString("emoji").orEmpty()
            val city = j.optString("city")
            val country = j.optString("country")
            "$flag ${j.optString("ip")} · ${listOf(city, country).filter { it.isNotBlank() }.joinToString(", ")}".trim()
        } catch (e: Exception) {
            "IP konnte nicht ermittelt werden"
        }
    }
}

fun flagEmoji(code: String): String {
    if (code.length != 2 || !code.all { it in 'A'..'Z' }) return "🌐"
    return code.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}

fun countryDisplayName(code: String, fallback: String): String {
    val name = Locale("", code).getDisplayCountry(Locale.GERMAN)
    return if (name.isBlank() || name.equals(code, ignoreCase = true)) fallback else name
}
