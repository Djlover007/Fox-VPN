package com.djlover007.foxvpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import de.blinkt.openvpn.R
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import java.io.StringReader

enum class Conn { OFF, CONNECTING, ON, ERROR }

/** Eingebauter VPN-Motor (OpenVPN, aus ics-openvpn). Keine Zusatz-App nötig. */
object FoxEngine : VpnStatus.StateListener {
    val state = mutableStateOf("NOPROCESS")
    val level = mutableStateOf(ConnectionStatus.LEVEL_NOTCONNECTED)

    private val main = Handler(Looper.getMainLooper())
    private var registered = false

    fun register() {
        if (registered) return
        VpnStatus.addStateListener(this)
        registered = true
    }

    override fun updateState(
        state: String?,
        logmessage: String?,
        localizedResId: Int,
        level: ConnectionStatus?,
        intent: Intent?,
    ) {
        main.post {
            this.state.value = state.orEmpty()
            this.level.value = level ?: ConnectionStatus.UNKNOWN_LEVEL
        }
    }

    override fun setConnectedVPN(uuid: String?) {}

    fun conn(): Conn = when (level.value) {
        ConnectionStatus.LEVEL_CONNECTED -> Conn.ON
        ConnectionStatus.LEVEL_NOTCONNECTED -> Conn.OFF
        ConnectionStatus.LEVEL_AUTH_FAILED -> Conn.ERROR
        ConnectionStatus.UNKNOWN_LEVEL ->
            if (state.value == "NOPROCESS" || state.value == "EXITING" || state.value.isEmpty()) Conn.OFF else Conn.CONNECTING
        else -> Conn.CONNECTING
    }

    /** Erwartet, dass die VPN-Freigabe (VpnService.prepare) schon erteilt ist. */
    fun start(context: Context, server: VpnServer) {
        val parser = ConfigParser()
        parser.parseConfig(StringReader(VpnGate.config(server)))
        val profile = parser.convertProfile()
        profile.mName = "Fox VPN · ${server.countryName}"
        val check = profile.checkProfile(context)
        if (check != R.string.no_error_found) throw IllegalStateException(context.getString(check))
        ProfileManager.setTemporaryProfile(context, profile)
        main.post {
            state.value = "WAIT"
            level.value = ConnectionStatus.LEVEL_START
        }
        VPNLaunchHelper.startOpenVpn(profile, context.applicationContext, "Fox VPN", true)
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        ProfileManager.setConnectedVpnProfileDisconnected(app)
        val intent = Intent(app, OpenVPNService::class.java).setAction(OpenVPNService.START_SERVICE)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    IOpenVPNServiceInternal.Stub.asInterface(binder).stopVPN(false)
                } catch (_: Exception) {
                }
                try {
                    app.unbindService(this)
                } catch (_: Exception) {
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }
}

fun stateText(state: String): String = when (state) {
    "CONNECTED" -> "Verbunden"
    "NOPROCESS", "EXITING", "" -> "Nicht verbunden"
    "AUTH_FAILED" -> "Anmeldung fehlgeschlagen"
    "NONETWORK" -> "Kein Netz"
    "CONNECTRETRY", "RECONNECTING" -> "Neuer Versuch …"
    "RESOLVE" -> "Suche Server …"
    "TCP_CONNECT", "WAIT", "VPN_GENERATE_CONFIG" -> "Verbinde …"
    "AUTH" -> "Anmelden …"
    "GET_CONFIG" -> "Hole Einstellungen …"
    "ASSIGN_IP", "ADD_ROUTES" -> "Fast fertig …"
    "USER_VPN_PERMISSION" -> "Warte auf Freigabe …"
    "USERPAUSE" -> "Pausiert"
    else -> "Verbinde …"
}
