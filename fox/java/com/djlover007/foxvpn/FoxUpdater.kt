package com.djlover007.foxvpn

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val version: Int, val url: String, val size: Long, val notes: List<String>)

/** App-Update über GitHub-Releases, wie bei den anderen Fox-Apps. */
class FoxUpdater(private val context: Context) {
    companion object {
        private const val REPO = "Djlover007/Fox-VPN"
        private const val ACTION_RESULT = "com.djlover007.foxvpn.INSTALL_RESULT"
        private const val PREFS = "fox_update"
        private const val CHECK_EVERY_MS = 6 * 3600 * 1000L
    }

    var available by mutableStateOf<UpdateInfo?>(null)
    var showDialog by mutableStateOf(false)
    var downloading by mutableStateOf(false)
    var percent by mutableIntStateOf(0)
    var message by mutableStateOf<String?>(null)

    private var downloadedFor: Int? = null
    private var receiver: BroadcastReceiver? = null
    private val apkFile get() = File(context.cacheDir, "update.apk")

    val installedVersion: Long
        get() = try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        } catch (e: Exception) {
            0
        }

    val installedName: String
        get() = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }

    fun autoCheckDue(): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong("last", 0)
        if (System.currentTimeMillis() - last < CHECK_EVERY_MS) return false
        prefs.edit().putLong("last", System.currentTimeMillis()).apply()
        return true
    }

    /** Liefert true, wenn ein Update gefunden wurde. */
    suspend fun check(manual: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
            c.connectTimeout = 12_000
            c.readTimeout = 12_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "FoxVPN")
            if (c.responseCode != 200) throw IllegalStateException("GitHub ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val version = Regex("(\\d+)\\s*$").find(j.optString("tag_name"))?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val assets = j.optJSONArray("assets")
            var url = ""
            var size = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        url = a.optString("browser_download_url")
                        size = a.optLong("size")
                        break
                    }
                }
            }
            if (url.isEmpty()) throw IllegalStateException("keine APK im Release")
            val notes = j.optString("body").lines()
                .map { it.trim().trimStart('-', '*', '•', ' ').trim() }
                .filter {
                    it.isNotEmpty() && !it.startsWith("Co-Authored-By", true) &&
                        !it.startsWith("Claude-Session", true) && !it.startsWith("http")
                }
                .take(12)
            if (version > installedVersion) {
                withContext(Dispatchers.Main) {
                    available = UpdateInfo(version, url, size, notes)
                    showDialog = true
                    message = null
                }
                true
            } else {
                if (manual) withContext(Dispatchers.Main) { message = "Du hast die neueste Version ($installedName)." }
                false
            }
        } catch (e: Exception) {
            if (manual) withContext(Dispatchers.Main) { message = "Update-Prüfung fehlgeschlagen: ${e.message}" }
            false
        }
    }

    /** Lädt herunter (falls nötig) und startet die Installation. */
    suspend fun downloadAndInstall() {
        val info = available ?: return
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            message = "Bitte erlaube Fox VPN „Apps installieren“ und tippe danach nochmal auf „Jetzt aktualisieren“."
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(i)
            } catch (_: Exception) {
            }
            return
        }
        try {
            if (downloadedFor != info.version || !apkFile.exists()) {
                downloading = true
                percent = 0
                download(info)
                downloadedFor = info.version
            }
            downloading = false
            withContext(Dispatchers.IO) { install() }
            showDialog = false
        } catch (e: Exception) {
            downloading = false
            message = "Update fehlgeschlagen: ${e.message}"
        }
    }

    private suspend fun download(info: UpdateInfo) = withContext(Dispatchers.IO) {
        val c = URL(info.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "FoxVPN")
        if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
        val total = c.contentLengthLong.takeIf { it > 0 } ?: info.size
        val tmp = File(context.cacheDir, "update.part")
        c.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(65536)
                var done = 0L
                var lastPct = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val p = (done * 100 / total).toInt()
                        if (p != lastPct) {
                            lastPct = p
                            withContext(Dispatchers.Main) { percent = p }
                        }
                    }
                }
            }
        }
        apkFile.delete()
        if (!tmp.renameTo(apkFile)) throw IllegalStateException("Datei konnte nicht gespeichert werden")
    }

    private fun install() {
        val f = apkFile
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            f.inputStream().use { input ->
                session.openWrite("update.apk", 0, f.length()).use { out ->
                    input.copyTo(out, 65536)
                    session.fsync(out)
                }
            }
            registerReceiver()
            val cb = Intent(ACTION_RESULT).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, id, cb, flags)
            session.commit(pending.intentSender)
        }
    }

    private fun registerReceiver() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            try {
                                c.startActivity(confirm)
                            } catch (_: Exception) {
                            }
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> {}
                    PackageInstaller.STATUS_FAILURE_ABORTED -> message = "Installation abgebrochen."
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        message = "Die Signatur passt nicht zur installierten App. Deinstalliere Fox VPN einmal " +
                            "und installiere die neueste Version von GitHub von Hand."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> message = "Auf dem Handy ist zu wenig Speicher frei."
                    else -> message = "Android hat die Installation abgelehnt ($msg)."
                }
            }
        }
        val filter = IntentFilter(ACTION_RESULT)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(r, filter)
        }
        receiver = r
    }
}
