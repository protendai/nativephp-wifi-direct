package com.protendai.wifidirect

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.utils.NativeActionCoordinator
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * Owns the WifiP2pManager channel and the connection state. A group is a
 * private Wi-Fi network: the owner is always 192.168.49.1, members get an
 * address on 192.168.49.0/24, and traffic never touches the router.
 *
 * Joining uses WifiP2pConfig.Builder (API 29+) with a known network name and
 * passphrase, which connects silently: no "accept invitation" dialog.
 *
 * Threading: bridge functions run on the PHP thread. Every public entry point
 * that touches the P2P state posts to the main looper, so the channel, the
 * mode and the generation counter are only ever read and written on main.
 * status() reads @Volatile snapshots instead.
 *
 * Cancellation: createGroup(), connect() and disconnect() each start a new
 * operation. That bumps [generation] and removes every delayed step of the
 * previous one (connect timeout, create deadline, BUSY retries, polls), all
 * posted with the [OP] token. Every async callback checks its generation
 * before acting, so nothing from an abandoned operation can fire later.
 */
object WifiDirectManager {

    const val TAG = "WifiDirect"
    const val OWNER_IP = "192.168.49.1"
    private const val EVENT_NAMESPACE = "Protendai\\WifiDirect\\Events\\"
    private const val CONNECT_TIMEOUT_MS = 30_000L
    private const val MAX_BUSY_RETRIES = 3
    private const val HOST_POLL_MS = 2_000L
    private const val PREFS = "protendai_wifidirect"

    /** Same rule WifiP2pConfig.Builder.setNetworkName() enforces (it throws otherwise). */
    private val NETWORK_NAME = Regex("^DIRECT-[a-zA-Z0-9]{2}.*")

    /** Token for every delayed step of the current operation. */
    private val OP = Any()
    private val main = Handler(Looper.getMainLooper())

    private enum class Mode { IDLE, HOSTING, HOSTED, JOINING, JOINED }

    // Set once in attach(), readable from any thread.
    @Volatile private var appContext: Context? = null
    @Volatile private var manager: WifiP2pManager? = null
    @Volatile private var activityRef: WeakReference<FragmentActivity>? = null

    // Snapshots for status().
    @Volatile private var p2pEnabled = false
    @Volatile private var linkState: JSONObject? = null

    // Main thread only.
    private var channel: WifiP2pManager.Channel? = null
    private var mode = Mode.IDLE
    private var generation = 0
    private var pendingCredentials: Pair<String, String>? = null
    /** Network name of the group being joined, hosted, or set up. */
    private var currentNetwork: String? = null
    /** A group we gave up on; removed if it forms anyway. */
    private var abandonedNetwork: String? = null

    /** Called by every bridge function with the activity that hosts the app. */
    fun attach(activity: FragmentActivity) {
        if (activityRef?.get() !== activity) activityRef = WeakReference(activity)
        if (appContext != null) return

        synchronized(this) {
            if (appContext != null) return
            val context = activity.applicationContext
            manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

            // The P2P state broadcast is sticky: read it now so the first status() is right.
            val sticky = ContextCompat.registerReceiver(
                context, receiver, IntentFilter(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            sticky?.let { p2pEnabled = it.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED }

            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                // Fired when a client associates with a group we own (also legacy Wi-Fi clients).
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            }
            // System broadcasts are delivered even to a not-exported receiver.
            // Registered on the application context for the life of the process.
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

            (context as? Application)?.registerActivityLifecycleCallbacks(lifecycle)
            appContext = context
        }

        main.post { ensureChannel() }
    }

    private fun ensureChannel() {
        if (channel != null) return
        val context = appContext ?: return
        channel = runCatching {
            manager?.initialize(context, Looper.getMainLooper()) {
                Log.w(TAG, "p2p channel lost")
                channel = null
            }
        }.getOrNull()
    }

    // ── Capabilities & permission ────────────────────────────────

    /**
     * What to ask for. Android 12/12L ignore a request for FINE location
     * without COARSE, so both go together there.
     */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** The permission Wi-Fi Direct actually checks (approximate location is not enough). */
    private fun essentialPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION

    private fun granted(permission: String): Boolean {
        val context = appContext ?: return false
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun hasPermission(): Boolean = granted(essentialPermission())

    /**
     * Answer a permission request with PermissionResult. Called by
     * PermissionFragment with whichever activity is current at that point.
     */
    fun reportPermission(activity: FragmentActivity, rationaleBefore: Boolean, interrupted: Boolean) {
        attach(activity)
        val payload = JSONObject()

        if (hasPermission()) {
            payload.put("granted", true).put("permanentlyDenied", false)
        } else {
            val essential = essentialPermission()
            val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val askedBefore = prefs.getBoolean("asked", false)
            val rationaleNow = ActivityCompat.shouldShowRequestPermissionRationale(activity, essential)
            // Best guess: Android only says "don't show the rationale", which is also
            // true before the first denial; hence the remembered earlier request.
            val permanently = !interrupted && !rationaleNow && (rationaleBefore || askedBefore)
            val approximateOnly = Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.ACCESS_COARSE_LOCATION)

            payload.put("granted", false)
                .put("permanentlyDenied", permanently)
                .put(
                    "reason",
                    when {
                        interrupted -> "interrupted"
                        approximateOnly -> "approximate"
                        permanently -> "permanently denied"
                        else -> "denied"
                    },
                )
        }

        if (!interrupted) {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("asked", true).apply()
        }
        dispatch("PermissionResult", payload)
    }

    fun status(): Map<String, Any> {
        val context = appContext
        val wifi = context?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val supported = context?.packageManager?.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) == true && manager != null
        val state = linkState

        return mapOf(
            "supported" to supported,
            "canHost" to supported,
            "enabled" to p2pEnabled,
            "fiveGhz" to (wifi?.is5GHzBandSupported == true),
            "permission" to hasPermission(),
            "wifiJoined" to wifiJoined(),
            "connected" to (state?.optBoolean("connected") ?: false),
            "isGroupOwner" to (state?.optBoolean("isGroupOwner") ?: false),
            "groupOwnerIp" to (state?.optString("groupOwnerIp") ?: ""),
        )
    }

    // ── Validation ───────────────────────────────────────────────

    /**
     * Why a network name / passphrase would be refused (and would make
     * WifiP2pConfig.Builder throw), or null when they are usable.
     */
    fun credentialsError(networkName: String, passphrase: String): String? = when {
        !NETWORK_NAME.matches(networkName) -> "network name must start with DIRECT-xy"
        networkName.toByteArray(Charsets.UTF_8).size > 32 -> "network name is longer than 32 bytes"
        passphrase.length !in 8..63 -> "passphrase must be 8 to 63 characters"
        passphrase.any { it.code !in 0x20..0x7E } -> "passphrase must be printable ASCII"
        else -> null
    }

    // ── Public entry points (post to main) ───────────────────────

    /** Host a group. Answers with GroupCreated or ConnectionFailed within [timeoutMs]. */
    fun createGroup(band: String, timeoutMs: Long) {
        main.post { startHosting(band, timeoutMs) }
    }

    /** Join a group. The caller has validated the credentials with [credentialsError]. */
    fun connect(networkName: String, passphrase: String, band: String) {
        main.post { startJoining(networkName, passphrase, band) }
    }

    /** Leave or tear down the group, cancelling anything in flight. Always answers with Disconnected. */
    fun disconnect() {
        main.post { leave() }
    }

    // ── Operations ───────────────────────────────────────────────

    /**
     * Start a new operation: report the end of any link we announced, cancel
     * everything the previous operation still had scheduled, and invalidate
     * its callbacks.
     */
    private fun beginOperation(): Int {
        endLink()
        abandon()
        ensureChannel()
        return generation
    }

    /** Cancel the current operation without reporting anything. */
    private fun abandon() {
        generation++
        main.removeCallbacksAndMessages(OP)
        currentNetwork?.let { abandonedNetwork = it }
        currentNetwork = null
        pendingCredentials = null
        mode = Mode.IDLE
    }

    /** Clear the reported link; dispatch Disconnected if we had announced one. */
    private fun endLink(): Boolean {
        val wasConnected = linkState?.optBoolean("connected") == true
        linkState = null
        if (wasConnected) dispatch("Disconnected", JSONObject())
        return wasConnected
    }

    private fun later(delayMs: Long, block: () -> Unit) {
        main.postDelayed(Runnable { block() }, OP, delayMs)
    }

    private fun leave() {
        abandon()
        linkState = null
        teardown(null)
        dispatch("Disconnected", JSONObject())
    }

    // ── Hosting ──────────────────────────────────────────────────

    private sealed class Attempt {
        data class Configured(val band: Int) : Attempt()
        data class Frequency(val mhz: Int) : Attempt()
        object Legacy : Attempt()
    }

    /**
     * Host a group, trying in order: our name/passphrase on the requested
     * band, then any band, then the legacy no-config API (vendor ROMs that
     * reject custom configs still accept it). Each step gets BUSY retries
     * after clearing stale P2P state.
     */
    private fun startHosting(band: String, timeoutMs: Long) {
        val gen = beginOperation()
        if (!ready()) return
        mode = Mode.HOSTING

        val plan = buildList {
            // The requested band first (5 GHz is what makes transfers fast;
            // chips that can't do it refuse instantly).
            if (band != "auto") add(Attempt.Configured(bandCode(band)))
            // Single-channel chips can only run a group on the channel their
            // Wi-Fi connection uses.
            joinedFrequency()?.let { add(Attempt.Frequency(it)) }
            add(Attempt.Configured(WifiP2pConfig.GROUP_OWNER_BAND_AUTO))
            // Some chips with no Wi-Fi network joined refuse to beacon on the
            // default channel 1; try fixed channels 6 and 11.
            add(Attempt.Frequency(2437))
            add(Attempt.Frequency(2462))
            add(Attempt.Legacy)
        }

        // Hard stop: never leave the caller waiting past the deadline.
        later(timeoutMs.coerceAtLeast(1_000L)) {
            if (gen == generation && mode == Mode.HOSTING) {
                Log.w(TAG, "createGroup gave up after ${timeoutMs / 1000} s")
                abandon()
                teardown(null)
                failHosting("group not ready")
            }
        }
        resetP2p(gen) { runCreate(plan, step = 0, busyRetries = 0, gen = gen) }
    }

    @SuppressLint("MissingPermission")
    private fun runCreate(plan: List<Attempt>, step: Int, busyRetries: Int, gen: Int) {
        if (gen != generation) return
        val p2p = manager
        val ch = channel
        if (p2p == null || ch == null) {
            abandon()
            fail("unsupported", "p2p channel lost")
            return
        }
        val attempt = plan[step]

        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (gen != generation) return
                Log.d(TAG, "group requested ($attempt)")
                val (name, pass) = pendingCredentials ?: (null to null)
                reportGroup(name, pass, plan, step, gen, poll = 0)
            }

            override fun onFailure(reason: Int) {
                if (gen != generation) return
                Log.w(TAG, "createGroup failed: ${reasonName(reason)} ($attempt, retry $busyRetries)")
                when {
                    reason == WifiP2pManager.BUSY && busyRetries < MAX_BUSY_RETRIES ->
                        resetP2p(gen, delayMs = 1_000L * (busyRetries + 1)) { runCreate(plan, step, busyRetries + 1, gen) }
                    step + 1 < plan.size -> resetP2p(gen) { runCreate(plan, step + 1, 0, gen) }
                    else -> {
                        abandon()
                        failHosting(reasonName(reason))
                    }
                }
            }
        }

        try {
            when (attempt) {
                is Attempt.Configured -> p2p.createGroup(ch, configuredGroup { it.setGroupOperatingBand(attempt.band) }, listener)
                is Attempt.Frequency -> p2p.createGroup(ch, configuredGroup { it.setGroupOperatingFrequency(attempt.mhz) }, listener)
                Attempt.Legacy -> {
                    pendingCredentials = null
                    currentNetwork = null
                    p2p.createGroup(ch, listener)
                }
            }
        } catch (e: SecurityException) {
            abandon()
            fail("permission", e.javaClass.simpleName)
        } catch (e: Exception) {
            // A refused config (IllegalArgumentException) counts as a failed step.
            Log.w(TAG, "createGroup threw ${e.javaClass.simpleName} ($attempt)")
            listener.onFailure(WifiP2pManager.ERROR)
        }
    }

    /** A fresh random DIRECT-xy name and passphrase, remembered for GroupCreated. */
    private fun configuredGroup(operating: (WifiP2pConfig.Builder) -> WifiP2pConfig.Builder): WifiP2pConfig {
        val name = "DIRECT-" + randomToken(2) + "-" + randomToken(6)
        val passphrase = randomToken(16)
        pendingCredentials = name to passphrase
        currentNetwork = name
        val builder = WifiP2pConfig.Builder()
            .setNetworkName(name)
            .setPassphrase(passphrase)
            .enablePersistentMode(false)
        return operating(builder).build()
    }

    /**
     * Hosting failed for good. Some chips can only run a group while Wi-Fi is
     * joined to a network; say so, so the app can offer the other direction
     * (the other phone hosts, this one scans).
     */
    private fun failHosting(raw: String) {
        fail(if (wifiEnabled() && !wifiJoined() && raw !in setOf("busy", "unsupported")) "needs network" else raw, raw)
    }

    /**
     * Wi-Fi is on AND connected to an access point (not just switched on).
     * Via ConnectivityManager: WifiInfo.networkId is redacted to -1 for apps
     * without location permission.
     */
    fun wifiJoined(): Boolean {
        val connectivity = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return runCatching {
            @Suppress("DEPRECATION")
            connectivity.allNetworks.any { network ->
                connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }.getOrDefault(false)
    }

    /** Frequency (MHz) of the access point we're joined to, if any. */
    @Suppress("DEPRECATION")
    private fun joinedFrequency(): Int? {
        if (!wifiJoined()) return null
        val wifi = appContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        return runCatching { wifi.connectionInfo?.frequency }.getOrNull()?.takeIf { it > 0 }
    }

    /**
     * Group info lags createGroup's success callback, by several seconds on
     * some ROMs, and some accept a configured group that never forms. Poll,
     * then move on to the next attempt instead of failing.
     */
    @SuppressLint("MissingPermission")
    private fun reportGroup(name: String?, passphrase: String?, plan: List<Attempt>, step: Int, gen: Int, poll: Int) {
        if (gen != generation) return
        val p2p = manager ?: return
        val ch = channel ?: return
        val maxPolls = when (plan[step]) { Attempt.Legacy -> 30; is Attempt.Frequency -> 10; else -> 16 } // × 500 ms

        val requested = runCatching {
            p2p.requestGroupInfo(ch) { group ->
                if (gen != generation) return@requestGroupInfo
                val groupName = group?.networkName ?: name
                val groupPass = group?.passphrase ?: passphrase
                if (group != null && group.isGroupOwner && groupName != null && groupPass != null) {
                    // Hosted: drop the deadline and anything else still scheduled.
                    main.removeCallbacksAndMessages(OP)
                    mode = Mode.HOSTED
                    currentNetwork = groupName
                    pendingCredentials = null
                    abandonedNetwork = null
                    dispatch(
                        "GroupCreated",
                        JSONObject()
                            .put("networkName", groupName)
                            .put("passphrase", groupPass)
                            .put("ownerIp", OWNER_IP)
                            .put("frequency", group.frequency)
                            .put("band", if (group.frequency >= 4900) "5ghz" else "2.4ghz"),
                    )
                    pollHost(gen)
                } else if (poll < maxPolls) {
                    later(500) { reportGroup(name, passphrase, plan, step, gen, poll + 1) }
                } else if (step + 1 < plan.size) {
                    Log.w(TAG, "group not formed after ${maxPolls / 2} s (${plan[step]}), trying next")
                    resetP2p(gen) { runCreate(plan, step + 1, 0, gen) }
                } else {
                    abandon()
                    teardown(null)
                    failHosting("group not ready")
                }
            }
        }
        if (requested.isFailure) {
            abandon()
            fail(if (requested.exceptionOrNull() is SecurityException) "permission" else "error", requested.exceptionOrNull()?.javaClass?.simpleName)
        }
    }

    /**
     * While hosting, re-read the group every few seconds. Members normally
     * announce themselves with a broadcast, but a plain Wi-Fi client (an
     * iPhone joining with the passphrase) may not on every ROM.
     */
    private fun pollHost(gen: Int) {
        later(HOST_POLL_MS) {
            if (gen == generation && mode == Mode.HOSTED) {
                refreshConnection(null)
                pollHost(gen)
            }
        }
    }

    // ── Joining ──────────────────────────────────────────────────

    private fun startJoining(networkName: String, passphrase: String, band: String) {
        val gen = beginOperation()
        if (!ready()) return
        mode = Mode.JOINING
        currentNetwork = networkName

        later(CONNECT_TIMEOUT_MS) {
            if (gen == generation && mode == Mode.JOINING) {
                // Cancel the join itself too, so a late group can't follow the failure.
                abandon()
                teardown(null)
                fail("timeout")
            }
        }

        resetP2p(gen) { runConnect(networkName, passphrase, bandCode(band), busyRetries = 0, gen = gen) }
    }

    @SuppressLint("MissingPermission")
    private fun runConnect(networkName: String, passphrase: String, band: Int, busyRetries: Int, gen: Int) {
        if (gen != generation) return
        val p2p = manager
        val ch = channel
        if (p2p == null || ch == null) {
            abandon()
            fail("unsupported", "p2p channel lost")
            return
        }

        val config = try {
            WifiP2pConfig.Builder()
                .setNetworkName(networkName)
                .setPassphrase(passphrase)
                .setGroupOperatingBand(band)
                .enablePersistentMode(false)
                .build()
        } catch (e: Exception) {
            abandon()
            fail("invalid", e.message ?: e.javaClass.simpleName)
            return
        }

        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (gen == generation) Log.d(TAG, "join requested")
            }

            override fun onFailure(reason: Int) {
                if (gen != generation) return
                Log.w(TAG, "connect failed: ${reasonName(reason)} (retry $busyRetries)")
                if (reason == WifiP2pManager.BUSY && busyRetries < MAX_BUSY_RETRIES) {
                    resetP2p(gen, delayMs = 1_000L * (busyRetries + 1)) { runConnect(networkName, passphrase, band, busyRetries + 1, gen) }
                } else {
                    abandon()
                    fail(reasonName(reason))
                }
            }
        }

        try {
            p2p.connect(ch, config, listener)
        } catch (e: SecurityException) {
            abandon()
            fail("permission", e.javaClass.simpleName)
        } catch (e: Exception) {
            abandon()
            fail("error", e.javaClass.simpleName)
        }
    }

    // ── Preconditions & cleanup ──────────────────────────────────

    /** Fail fast, with a specific reason, on the things users can fix. */
    private fun ready(): Boolean {
        if (manager == null || channel == null) return fail("unsupported")
        if (!hasPermission()) return fail("permission")
        if (!wifiEnabled()) return fail("wifi off")
        // Android 12 and older (and some vendor builds) refuse P2P with location off.
        if (Build.VERSION.SDK_INT < 33 && !locationEnabled()) return fail("location off")
        return true
    }

    /**
     * Stop discovery, cancel a half-finished connect and remove our group.
     * Every step ignores its own failure; [then] runs on main afterwards.
     */
    private fun teardown(then: (() -> Unit)?) {
        val p2p = manager
        val ch = channel
        if (p2p == null || ch == null) {
            then?.invoke()
            return
        }

        fun step(action: (WifiP2pManager.ActionListener) -> Unit, next: () -> Unit) {
            val listener = object : WifiP2pManager.ActionListener {
                override fun onSuccess() = next()
                override fun onFailure(reason: Int) = next()
            }
            try {
                action(listener)
            } catch (e: Exception) {
                next()
            }
        }

        step({ p2p.stopPeerDiscovery(ch, it) }) {
            step({ p2p.cancelConnect(ch, it) }) {
                step({ p2p.removeGroup(ch, it) }) { then?.invoke() }
            }
        }
    }

    /**
     * Clear whatever might hold the P2P state machine (a lingering group, a
     * half-finished connect, a discovery another request started), then
     * continue with [then] unless the operation was cancelled meanwhile.
     */
    private fun resetP2p(gen: Int, delayMs: Long = 400L, then: () -> Unit) {
        teardown {
            if (gen == generation) later(delayMs) { if (gen == generation) then() }
        }
    }

    private fun wifiEnabled(): Boolean =
        (appContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true

    private fun locationEnabled(): Boolean {
        val location = appContext?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return LocationManagerCompat.isLocationEnabled(location)
    }

    // ── Connection state ─────────────────────────────────────────

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    p2pEnabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val group = intent.getParcelableExtra<WifiP2pGroup>(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
                    refreshConnection(group)
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (mode == Mode.HOSTED) refreshConnection(null)
                }
            }
        }
    }

    /**
     * Re-read the system's view of the group and turn it into events for the
     * current mode. [broadcastGroup] is the group carried by a connection
     * broadcast, used as a second opinion on the member count.
     */
    @SuppressLint("MissingPermission")
    private fun refreshConnection(broadcastGroup: WifiP2pGroup?) {
        val p2p = manager ?: return
        val ch = channel ?: return
        val gen = generation

        runCatching {
            p2p.requestConnectionInfo(ch) { info ->
                if (gen != generation) return@requestConnectionInfo
                if (info == null || !info.groupFormed) {
                    onGroupGone()
                    return@requestConnectionInfo
                }
                if (!hasPermission()) return@requestConnectionInfo

                runCatching {
                    p2p.requestGroupInfo(ch) { group ->
                        if (gen == generation) onGroup(info, group, broadcastGroup)
                    }
                }
            }
        }.onFailure { Log.w(TAG, "connection info unavailable: ${it.javaClass.simpleName}") }
    }

    private fun onGroupGone() {
        when (mode) {
            Mode.JOINED -> {
                abandon()
                endLink()
            }
            Mode.HOSTED -> {
                // The group we host went away (by the system or another app).
                abandon()
                if (!endLink()) dispatch("Disconnected", JSONObject())
            }
            // Setting up: groups come and go while resetP2p clears old state.
            else -> Unit
        }
    }

    private fun onGroup(info: WifiP2pInfo, group: WifiP2pGroup?, broadcastGroup: WifiP2pGroup?) {
        val clients = maxOf(group?.clientList?.size ?: 0, broadcastGroup?.clientList?.size ?: 0)
        val state = JSONObject()
            .put("connected", true)
            .put("isGroupOwner", info.isGroupOwner)
            .put("groupOwnerIp", info.groupOwnerAddress?.hostAddress ?: OWNER_IP)
            .put("clients", clients)
            .put("frequency", group?.frequency ?: 0)

        when (mode) {
            Mode.JOINING -> if (!info.isGroupOwner) {
                main.removeCallbacksAndMessages(OP) // the connect timeout
                mode = Mode.JOINED
                linkState = state
                dispatch("Connected", state)
            }
            Mode.JOINED -> if (!info.isGroupOwner) linkState = state
            Mode.HOSTED -> if (info.isGroupOwner) {
                val previous = linkState
                when {
                    clients > 0 && previous?.optInt("clients") != clients -> {
                        linkState = state
                        dispatch("Connected", state)
                    }
                    clients > 0 -> linkState = state
                    // An owner with no members is a hosted group, not a link
                    // (also how the owner notices its last member leaving).
                    else -> endLink()
                }
            }
            Mode.IDLE -> {
                val name = group?.networkName
                if (name != null && name == abandonedNetwork) {
                    // A join or group we cancelled formed anyway: leave it quietly.
                    Log.i(TAG, "removing a group that formed after it was cancelled")
                    abandonedNetwork = null
                    teardown(null)
                }
            }
            Mode.HOSTING -> Unit // reportGroup() announces the group
        }
    }

    // ── Activity lifecycle ───────────────────────────────────────

    /**
     * Leave the group when the app's activity is really destroyed (not on a
     * configuration change), so a hosted group or join doesn't outlive the app.
     */
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityDestroyed(activity: Activity) {
            if (activity !== activityRef?.get() || activity.isChangingConfigurations) return
            activityRef = null
            if (mode != Mode.IDLE || linkState != null) {
                Log.i(TAG, "activity destroyed: leaving the group")
                abandon()
                linkState = null
                teardown(null)
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Send an event to PHP through the current activity. Dropped (with a log
     * line) when there is no live activity to deliver it to.
     */
    fun dispatch(event: String, payload: JSONObject) {
        val activity = activityRef?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "no live activity, dropped $event")
            return
        }
        activity.runOnUiThread {
            if (activity.isDestroyed) return@runOnUiThread
            try {
                NativeActionCoordinator.dispatchEvent(activity, EVENT_NAMESPACE + event, payload.toString())
            } catch (e: Exception) {
                Log.w(TAG, "could not dispatch $event: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun fail(reason: String, raw: String? = null): Boolean {
        // Several vendor builds answer BUSY/ERROR when Location is off, even on Android 13+.
        val effective = if (reason in setOf("busy", "error") && !locationEnabled()) "location off" else reason
        Log.w(TAG, "failed: $effective (raw: ${raw ?: reason})")
        dispatch(
            "ConnectionFailed",
            JSONObject()
                .put("reason", effective)
                .put(
                    "detail",
                    "raw=${raw ?: reason} p2p=${onOff(p2pEnabled)} wifi=${onOff(wifiEnabled())} location=${onOff(locationEnabled())} " +
                        "sdk=${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL}",
                ),
        )
        return false
    }

    private fun onOff(value: Boolean) = if (value) "on" else "off"

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

    private val random = java.security.SecureRandom()

    private fun randomToken(length: Int): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..length).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }
}
