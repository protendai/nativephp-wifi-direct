package com.protendai.wifidirect

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.utils.NativeActionCoordinator
import org.json.JSONObject

/**
 * Owns the WifiP2pManager channel and the connection state. A group is a
 * private Wi-Fi network: the owner is always 192.168.49.1, members get an
 * address on 192.168.49.0/24, and traffic never touches the router.
 *
 * Joining uses WifiP2pConfig.Builder (API 29+) with a known network name and
 * passphrase, which connects silently: no "accept invitation" dialog.
 */
object WifiDirectManager {

    const val TAG = "WifiDirect"
    const val OWNER_IP = "192.168.49.1"
    private const val EVENT_NAMESPACE = "Protendai\\WifiDirect\\Events\\"
    private const val CONNECT_TIMEOUT_MS = 30_000L

    private var appContext: Context? = null
    private var activityRef: FragmentActivity? = null
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var p2pEnabled = false
    @Volatile private var lastState: JSONObject? = null
    private var connectTimeout: Runnable? = null

    fun attach(activity: FragmentActivity) {
        activityRef = activity
        if (appContext != null) return

        val context = activity.applicationContext
        appContext = context
        manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = manager?.initialize(context, Looper.getMainLooper()) {
            Log.w(TAG, "p2p channel lost")
            channel = null
        }

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        // System broadcasts: delivered even to a not-exported receiver.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    // ── Capabilities & permission ────────────────────────────────

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermission(): Boolean {
        val context = appContext ?: return false
        return requiredPermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    fun status(): Map<String, Any> {
        val context = appContext
        val wifi = context?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val supported = context?.packageManager?.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) == true && manager != null
        val state = lastState

        return mapOf(
            "supported" to supported,
            "enabled" to p2pEnabled,
            "fiveGhz" to (wifi?.is5GHzBandSupported == true),
            "permission" to hasPermission(),
            "connected" to (state?.optBoolean("connected") ?: false),
            "isGroupOwner" to (state?.optBoolean("isGroupOwner") ?: false),
            "groupOwnerIp" to (state?.optString("groupOwnerIp") ?: ""),
        )
    }

    // ── Hosting ──────────────────────────────────────────────────

    fun createGroup(band: String): Boolean {
        val p2p = manager ?: return fail("unsupported")
        val ch = channel ?: return fail("unsupported")
        if (!hasPermission()) return fail("permission")

        // Drop any group we already own so the new name/passphrase take effect.
        p2p.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = startGroup(bandCode(band), allowFallback = band == "5ghz")
            override fun onFailure(reason: Int) = startGroup(bandCode(band), allowFallback = band == "5ghz")
        })
        return true
    }

    @SuppressLint("MissingPermission")
    private fun startGroup(band: Int, allowFallback: Boolean) {
        val p2p = manager ?: return
        val ch = channel ?: return
        val name = "DIRECT-" + randomToken(2) + "-" + randomToken(6)
        val passphrase = randomToken(16)

        val config = WifiP2pConfig.Builder()
            .setNetworkName(name)
            .setPassphrase(passphrase)
            .setGroupOperatingBand(band)
            .enablePersistentMode(false)
            .build()

        p2p.createGroup(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "group requested: $name band=$band")
                reportGroup(name, passphrase, attempt = 0)
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "createGroup failed: ${reasonName(reason)} band=$band")
                // Not every chipset can run 5 GHz P2P alongside its current Wi-Fi link.
                if (allowFallback) startGroup(WifiP2pConfig.GROUP_OWNER_BAND_AUTO, allowFallback = false)
                else fail(reasonName(reason))
            }
        })
    }

    /** Group info lags createGroup's success callback by a beat; poll briefly. */
    @SuppressLint("MissingPermission")
    private fun reportGroup(name: String, passphrase: String, attempt: Int) {
        val p2p = manager ?: return
        val ch = channel ?: return

        p2p.requestGroupInfo(ch) { group ->
            if (group != null && group.isGroupOwner) {
                dispatch(
                    "GroupCreated",
                    JSONObject()
                        .put("networkName", group.networkName ?: name)
                        .put("passphrase", group.passphrase ?: passphrase)
                        .put("ownerIp", OWNER_IP)
                        .put("frequency", group.frequency)
                        .put("band", if (group.frequency >= 4900) "5ghz" else "2.4ghz"),
                )
            } else if (attempt < 10) {
                main.postDelayed({ reportGroup(name, passphrase, attempt + 1) }, 500)
            } else {
                fail("group not ready")
            }
        }
    }

    // ── Joining ──────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun connect(networkName: String, passphrase: String, band: String): Boolean {
        val p2p = manager ?: return fail("unsupported")
        val ch = channel ?: return fail("unsupported")
        if (!hasPermission()) return fail("permission")

        val config = WifiP2pConfig.Builder()
            .setNetworkName(networkName)
            .setPassphrase(passphrase)
            .setGroupOperatingBand(bandCode(band))
            .enablePersistentMode(false)
            .build()

        clearTimeout()
        connectTimeout = Runnable {
            if (lastState?.optBoolean("connected") != true) {
                runCatching { p2p.cancelConnect(ch, null) }
                fail("timeout")
            }
        }.also { main.postDelayed(it, CONNECT_TIMEOUT_MS) }

        p2p.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "connect requested: $networkName")
            }
            override fun onFailure(reason: Int) {
                clearTimeout()
                fail(reasonName(reason))
            }
        })
        return true
    }

    fun disconnect() {
        clearTimeout()
        val p2p = manager ?: return
        val ch = channel ?: return
        runCatching { p2p.cancelConnect(ch, null) }
        p2p.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = markDisconnected()
            override fun onFailure(reason: Int) = markDisconnected()
        })
    }

    // ── Connection state ─────────────────────────────────────────

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    p2pEnabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> refreshConnection()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshConnection() {
        val p2p = manager ?: return
        val ch = channel ?: return

        p2p.requestConnectionInfo(ch) { info ->
            if (info == null || !info.groupFormed) {
                markDisconnected()
                return@requestConnectionInfo
            }

            if (!hasPermission()) return@requestConnectionInfo
            p2p.requestGroupInfo(ch) { group ->
                val clients = group?.clientList?.size ?: 0
                val state = JSONObject()
                    .put("connected", true)
                    .put("isGroupOwner", info.isGroupOwner)
                    .put("groupOwnerIp", info.groupOwnerAddress?.hostAddress ?: OWNER_IP)
                    .put("clients", clients)
                    .put("frequency", group?.frequency ?: 0)

                // An owner with no members is a hosted group, not a connection
                // (also how the owner notices its last member leaving).
                if (info.isGroupOwner && clients == 0) {
                    markDisconnected()
                    return@requestGroupInfo
                }

                if (state.toString() != lastState?.toString()) {
                    lastState = state
                    clearTimeout()
                    dispatch("Connected", state)
                }
            }
        }
    }

    private fun markDisconnected() {
        val wasConnected = lastState?.optBoolean("connected") == true
        lastState = null
        if (wasConnected) dispatch("Disconnected", JSONObject())
    }

    // ── Helpers ──────────────────────────────────────────────────

    fun dispatch(event: String, payload: JSONObject) {
        val activity = activityRef ?: return
        activity.runOnUiThread {
            NativeActionCoordinator.dispatchEvent(activity, EVENT_NAMESPACE + event, payload.toString())
        }
    }

    private fun fail(reason: String): Boolean {
        Log.w(TAG, "failed: $reason")
        dispatch("ConnectionFailed", JSONObject().put("reason", reason))
        return false
    }

    private fun clearTimeout() {
        connectTimeout?.let { main.removeCallbacks(it) }
        connectTimeout = null
    }

    private fun bandCode(band: String): Int = when (band) {
        "5ghz" -> WifiP2pConfig.GROUP_OWNER_BAND_5GHZ
        "2.4ghz" -> WifiP2pConfig.GROUP_OWNER_BAND_2GHZ
        else -> WifiP2pConfig.GROUP_OWNER_BAND_AUTO
    }

    private fun reasonName(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "unsupported"
        WifiP2pManager.BUSY -> "busy"
        WifiP2pManager.ERROR -> "error"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "no service requests"
        else -> "error $reason"
    }

    private fun randomToken(length: Int): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = java.security.SecureRandom()
        return (1..length).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }
}
