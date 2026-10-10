package com.protendai.wifidirect

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
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
    private const val MAX_BUSY_RETRIES = 3

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
        if (appContext != null) {
            if (channel == null) channel = manager?.initialize(appContext, Looper.getMainLooper()) { channel = null }
            return
        }

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

    /**
     * Host a group, trying in order: our name/passphrase on the requested
     * band, then any band, then the legacy no-config API (vendor ROMs that
     * reject custom configs still accept it). Each step gets BUSY retries
     * after clearing stale P2P state.
     */
    fun createGroup(band: String): Boolean {
        if (!ready()) return false

        val full = buildList {
            if (band != "auto") add(Attempt.Configured(bandCode(band)))
            add(Attempt.Configured(WifiP2pConfig.GROUP_OWNER_BAND_AUTO))
            // Some chips (Samsung A05s with no Wi-Fi network joined) refuse to
            // beacon on the default channel 1; try fixed channels 6 and 11.
            add(Attempt.Frequency(2437))
            add(Attempt.Frequency(2462))
            add(Attempt.Legacy)
        }
        // Start from whatever worked last time on this device (e.g. a ROM that
        // rejects 5 GHz or never forms configured groups), keeping later fallbacks.
        val plan = lastWorking?.let { worked -> full.indexOf(worked).takeIf { it > 0 }?.let { full.drop(it) } } ?: full
        val gen = ++createGeneration
        resetP2p { runCreate(plan, step = 0, busyRetries = 0, gen = gen) }
        return true
    }

    private sealed class Attempt {
        data class Configured(val band: Int) : Attempt()
        data class Frequency(val mhz: Int) : Attempt()
        object Legacy : Attempt()
    }

    @SuppressLint("MissingPermission")
    private fun runCreate(plan: List<Attempt>, step: Int, busyRetries: Int, gen: Int) {
        if (gen != createGeneration) return
        val p2p = manager ?: return
        val ch = channel ?: return
        val attempt = plan[step]

        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "group requested ($attempt)")
                val (name, pass) = pendingCredentials ?: (null to null)
                reportGroup(name, pass, plan, step, gen, poll = 0)
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "createGroup failed: ${reasonName(reason)} ($attempt, retry $busyRetries)")
                when {
                    reason == WifiP2pManager.BUSY && busyRetries < MAX_BUSY_RETRIES ->
                        resetP2p(delayMs = 1_000L * (busyRetries + 1)) { runCreate(plan, step, busyRetries + 1, gen) }
                    step + 1 < plan.size -> resetP2p { runCreate(plan, step + 1, 0, gen) }
                    else -> fail(reasonName(reason))
                }
            }
        }

        when (attempt) {
            is Attempt.Configured -> {
                val name = "DIRECT-" + randomToken(2) + "-" + randomToken(6)
                val passphrase = randomToken(16)
                pendingCredentials = name to passphrase
                val config = WifiP2pConfig.Builder()
                    .setNetworkName(name)
                    .setPassphrase(passphrase)
                    .setGroupOperatingBand(attempt.band)
                    .enablePersistentMode(false)
                    .build()
                p2p.createGroup(ch, config, listener)
            }
            is Attempt.Frequency -> {
                val name = "DIRECT-" + randomToken(2) + "-" + randomToken(6)
                val passphrase = randomToken(16)
                pendingCredentials = name to passphrase
                val config = WifiP2pConfig.Builder()
                    .setNetworkName(name)
                    .setPassphrase(passphrase)
                    .setGroupOperatingFrequency(attempt.mhz)
                    .enablePersistentMode(false)
                    .build()
                p2p.createGroup(ch, config, listener)
            }
            Attempt.Legacy -> {
                pendingCredentials = null
                p2p.createGroup(ch, listener)
            }
        }
    }

    private var pendingCredentials: Pair<String, String>? = null
    private var lastWorking: Attempt? = null
    private var createGeneration = 0

    /**
     * Group info lags createGroup's success callback, by several seconds on
     * some ROMs (Samsung A05s: >5 s), and some accept a configured group that
     * never forms. Poll, then move on to the next attempt instead of failing.
     */
    @SuppressLint("MissingPermission")
    private fun reportGroup(name: String?, passphrase: String?, plan: List<Attempt>, step: Int, gen: Int, poll: Int) {
        if (gen != createGeneration) return
        val p2p = manager ?: return
        val ch = channel ?: return
        val maxPolls = when (plan[step]) { Attempt.Legacy -> 30; is Attempt.Frequency -> 10; else -> 16 } // × 500 ms

        p2p.requestGroupInfo(ch) { group ->
            if (gen != createGeneration) return@requestGroupInfo
            val groupName = group?.networkName ?: name
            val groupPass = group?.passphrase ?: passphrase
            if (group != null && group.isGroupOwner && groupName != null && groupPass != null) {
                lastWorking = plan[step]
                dispatch(
                    "GroupCreated",
                    JSONObject()
                        .put("networkName", groupName)
                        .put("passphrase", groupPass)
                        .put("ownerIp", OWNER_IP)
                        .put("frequency", group.frequency)
                        .put("band", if (group.frequency >= 4900) "5ghz" else "2.4ghz"),
                )
            } else if (poll < maxPolls) {
                main.postDelayed({ reportGroup(name, passphrase, plan, step, gen, poll + 1) }, 500)
            } else if (step + 1 < plan.size) {
                Log.w(TAG, "group not formed after ${maxPolls / 2} s (${plan[step]}), trying next")
                resetP2p { runCreate(plan, step + 1, 0, gen) }
            } else {
                fail("group not ready")
            }
        }
    }

    // ── Joining ──────────────────────────────────────────────────

    fun connect(networkName: String, passphrase: String, band: String): Boolean {
        if (!ready()) return false

        clearTimeout()
        connectTimeout = Runnable {
            if (lastState?.optBoolean("connected") != true) {
                channel?.let { ch -> runCatching { manager?.cancelConnect(ch, null) } }
                fail("timeout")
            }
        }.also { main.postDelayed(it, CONNECT_TIMEOUT_MS) }

        resetP2p { runConnect(networkName, passphrase, bandCode(band), busyRetries = 0) }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun runConnect(networkName: String, passphrase: String, band: Int, busyRetries: Int) {
        val p2p = manager ?: return
        val ch = channel ?: return

        val config = WifiP2pConfig.Builder()
            .setNetworkName(networkName)
            .setPassphrase(passphrase)
            .setGroupOperatingBand(band)
            .enablePersistentMode(false)
            .build()

        p2p.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "connect requested: $networkName")
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "connect failed: ${reasonName(reason)} (retry $busyRetries)")
                if (reason == WifiP2pManager.BUSY && busyRetries < MAX_BUSY_RETRIES) {
                    resetP2p(delayMs = 1_000L * (busyRetries + 1)) { runConnect(networkName, passphrase, band, busyRetries + 1) }
                } else {
                    clearTimeout()
                    fail(reasonName(reason))
                }
            }
        })
    }

    // ── Preconditions & cleanup ──────────────────────────────────

    /** Fail fast, with a specific reason, on the things users can fix. */
    private fun ready(): Boolean {
        if (manager == null) return fail("unsupported")
        if (channel == null) return fail("unsupported")
        if (!hasPermission()) return fail("permission")
        if (!wifiEnabled()) return fail("wifi off")
        // Android 12 and older (and some vendor builds) refuse P2P with location off.
        if (Build.VERSION.SDK_INT < 33 && !locationEnabled()) return fail("location off")
        return true
    }

    /**
     * Clear whatever might hold the P2P state machine (a lingering group,
     * a half-finished connect, a discovery another request started), then
     * continue. Every step ignores its own failure.
     */
    private fun resetP2p(delayMs: Long = 400L, then: () -> Unit) {
        val p2p = manager
        val ch = channel
        if (p2p == null || ch == null) {
            then()
            return
        }

        val ignore = { next: () -> Unit ->
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() = next()
                override fun onFailure(reason: Int) = next()
            }
        }

        p2p.stopPeerDiscovery(ch, ignore {
            p2p.cancelConnect(ch, ignore {
                p2p.removeGroup(ch, ignore {
                    main.postDelayed({ then() }, delayMs)
                })
            })
        })
    }

    private fun wifiEnabled(): Boolean =
        (appContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true

    private fun locationEnabled(): Boolean {
        val location = appContext?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return LocationManagerCompat.isLocationEnabled(location)
    }

    fun disconnect() {
        clearTimeout()
        createGeneration++ // abandon any createGroup still polling
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
        // Several vendor builds answer BUSY/ERROR when Location is off, even on Android 13+.
        val effective = if (reason in setOf("busy", "error") && !locationEnabled()) "location off" else reason
        Log.w(TAG, "failed: $effective (raw: $reason, p2pEnabled=$p2pEnabled)")
        dispatch(
            "ConnectionFailed",
            JSONObject()
                .put("reason", effective)
                .put("detail", "raw=$reason p2p=${if (p2pEnabled) "on" else "off"} wifi=${if (wifiEnabled()) "on" else "off"} location=${if (locationEnabled()) "on" else "off"} sdk=${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL}"),
        )
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
