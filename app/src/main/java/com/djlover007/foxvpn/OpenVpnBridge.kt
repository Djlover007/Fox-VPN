package com.djlover007.foxvpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import de.blinkt.openvpn.api.IOpenVPNAPIService
import de.blinkt.openvpn.api.IOpenVPNStatusCallback

enum class Conn { OFF, CONNECTING, ON, ERROR }

/**
 * Verbindung zum VPN-Motor "OpenVPN for Android" (Open Source, de.blinkt.openvpn).
 * Fox VPN steuert ihn komplett über seine offizielle Schnittstelle.
 */
class OpenVpnBridge(private val context: Context) {
    companion object {
        const val PKG = "de.blinkt.openvpn"
    }

    var service: IOpenVPNAPIService? = null
        private set
    val state = mutableStateOf("NOPROCESS")
    val message = mutableStateOf("")
    val bound = mutableStateOf(false)

    private val main = Handler(Looper.getMainLooper())
    private var callbackRegistered = false
    private var binding = false

    private val callback = object : IOpenVPNStatusCallback.Stub() {
        override fun newStatus(uuid: String?, state: String?, message: String?, level: String?) {
            main.post {
                this@OpenVpnBridge.state.value = state.orEmpty()
                this@OpenVpnBridge.message.value = message.orEmpty()
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IOpenVPNAPIService.Stub.asInterface(binder)
            bound.value = true
            binding = false
            registerCallback()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound.value = false
            callbackRegistered = false
            binding = false
        }
    }

    fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(PKG, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun bind() {
        if (service != null || binding) return
        val intent = Intent(IOpenVPNAPIService::class.java.name).setPackage(PKG)
        binding = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            false
        }
    }

    /** Klappt erst, nachdem der Nutzer Fox VPN einmal im Motor freigegeben hat. */
    fun registerCallback() {
        if (callbackRegistered) return
        try {
            service?.registerStatusCallback(callback)
            callbackRegistered = service != null
        } catch (e: Exception) {
            callbackRegistered = false
        }
    }

    fun unbind() {
        try {
            if (callbackRegistered) service?.unregisterStatusCallback(callback)
        } catch (_: Exception) {
        }
        try {
            if (service != null || binding) context.unbindService(connection)
        } catch (_: Exception) {
        }
        service = null
        bound.value = false
        callbackRegistered = false
        binding = false
    }

    fun conn(): Conn = when (state.value) {
        "CONNECTED" -> Conn.ON
        "NOPROCESS", "EXITING", "" -> Conn.OFF
        "AUTH_FAILED" -> Conn.ERROR
        else -> Conn.CONNECTING
    }
}

fun stateText(state: String): String = when (state) {
    "CONNECTED" -> "Verbunden"
    "NOPROCESS", "EXITING", "" -> "Nicht verbunden"
    "AUTH_FAILED" -> "Anmeldung fehlgeschlagen"
    "NONETWORK" -> "Kein Netz"
    "CONNECTRETRY", "RECONNECTING" -> "Neuer Versuch …"
    "RESOLVE" -> "Suche Server …"
    "TCP_CONNECT", "WAIT" -> "Verbinde …"
    "AUTH" -> "Anmelden …"
    "GET_CONFIG" -> "Hole Einstellungen …"
    "ASSIGN_IP", "ADD_ROUTES" -> "Fast fertig …"
    "USER_VPN_PERMISSION" -> "Warte auf Freigabe …"
    else -> "Verbinde …"
}
