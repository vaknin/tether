package com.kivan.tether

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.kivan.tether.core.TetherNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors

/**
 * Answers the laptop's "where is adb?" (dibs reconnecting adb), as live frames on the `_adb`
 * channel, so adb comes back on any network with no stored IP or port. With the screen off the
 * Wi-Fi filter drops incoming multicast, so the laptop's mDNS never sees the phone. The answer
 * gives the laptop the phone's addresses, which it then asks by unicast mDNS (that passes the
 * filter), and a multicast lock is held for [LOCK_MS] so the laptop's own mDNS works meanwhile.
 * The phone looks up its port itself (NsdManager) only with ACCESS_LOCAL_NETWORK: without it,
 * Android 17 answers a discovery with a picker screen instead (seen 2026-10-05).
 *
 * Ask: `{"op":"endpoint","enable":bool}`. Answer: `adb_wifi` (Wireless debugging on, null when
 * unreadable), `wifi` (on a Wi-Fi network), `addrs` (its IPv4 `ip/prefix`), `port` (the TLS port,
 * checked to be listening; null when not found), `name` (the service name), `can_enable`
 * (WRITE_SECURE_SETTINGS granted), `enabled` (this answer turned it on), `refused` (Android turned it
 * back off: this network isn't one the user allowed, so it needs one tap on the phone).
 */
object Adb {
    const val CHANNEL = "_adb"
    private const val TAG = "Tether"
    private const val SETTING = "adb_wifi_enabled"
    private const val SERVICE = "_adb-tls-connect._tcp"
    private const val HOLD = "adb"
    private const val DISCOVER_MS = 6_000L
    private const val ENABLE_MS = 4_000L
    private const val LOCK_MS = 20_000L
    /** Android 17 (API 37) gates mDNS discovery behind this runtime permission. */
    private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    fun onAsk(context: Context, data: String) {
        val ask = runCatching { JSONObject(data) }.getOrNull() ?: return
        if (ask.optString("op") != "endpoint") return
        Core.scope.launch {
            val node = Core.acquire(HOLD)
            val lock = context.getSystemService(WifiManager::class.java)
                .createMulticastLock("tether-adb").apply { setReferenceCounted(false) }
            lock.acquire()
            Core.scope.launch {
                delay(LOCK_MS)
                lock.release()
            }
            try {
                val answer = runCatching { answer(context.applicationContext, ask.optBoolean("enable")) }
                    .getOrElse { JSONObject().put("error", it.toString()) }
                Log.i(TAG, "adb: $answer")
                node.sendAppLive(CHANNEL, answer.toString())
            } finally {
                Core.release(HOLD)
            }
        }
    }

    private suspend fun answer(context: Context, enable: Boolean): JSONObject {
        val out = JSONObject()
        val addrs = wifiAddrs(context)
        out.put("wifi", addrs.isNotEmpty())
        out.put("addrs", JSONArray(addrs.map { "${it.first.hostAddress}/${it.second}" }))
        val canEnable = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
        out.put("can_enable", canEnable)
        var on = setting(context)
        if (enable && on != true && canEnable && addrs.isNotEmpty()) {
            Settings.Global.putInt(context.contentResolver, SETTING, 1)
            out.put("enabled", true)
            // AdbService writes 0 back at once on a network the user hasn't allowed.
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < ENABLE_MS) {
                delay(250)
                if (setting(context) == false) {
                    out.put("refused", true)
                    break
                }
            }
            on = setting(context)
        }
        out.put("adb_wifi", on ?: JSONObject.NULL)
        val mayDiscover = Build.VERSION.SDK_INT < 37 ||
            context.checkSelfPermission(LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
        if (on != false && addrs.isNotEmpty() && mayDiscover) {
            discover(context, addrs.map { it.first }.toSet())?.let { (name, port) ->
                out.put("port", port)
                out.put("name", name)
            }
        }
        return out
    }

    /** `adb_wifi_enabled`; null when this Android doesn't let the app read it. */
    private fun setting(context: Context): Boolean? =
        runCatching { Settings.Global.getInt(context.contentResolver, SETTING) == 1 }.getOrNull()

    /** The IPv4 addresses (with prefix length) of every Wi-Fi network the phone is on. */
    private fun wifiAddrs(context: Context): List<Pair<InetAddress, Int>> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        return cm.allNetworks.filter {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }.flatMap { net ->
            cm.getLinkProperties(net)?.linkAddresses.orEmpty()
                .filter { it.address is Inet4Address }
                .map { it.address to it.prefixLength }
        }
    }

    /**
     * Finds this phone's own `_adb-tls-connect` service: one whose address is the phone's and whose
     * port is really in use here (a stale record from an earlier enable isn't).
     */
    private suspend fun discover(context: Context, own: Set<InetAddress>): Pair<String, Int>? {
        val nsd = context.getSystemService(NsdManager::class.java)
        val found = CompletableDeferred<Pair<String, Int>>()
        val exec = Executors.newSingleThreadExecutor()
        val resolving = mutableListOf<NsdManager.ServiceInfoCallback>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                val cb = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceUpdated(s: NsdServiceInfo) {
                        val mine = s.hostAddresses.any { it in own }
                        if (mine && s.port > 0 && inUse(s.port)) found.complete(s.serviceName to s.port)
                    }
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                    override fun onServiceLost() {}
                    override fun onServiceInfoCallbackUnregistered() {}
                }
                runCatching { nsd.registerServiceInfoCallback(info, exec, cb) }
                    .onSuccess { synchronized(resolving) { resolving += cb } }
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "adb: discovery failed ($errorCode)")
                found.cancel()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        return try {
            nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
            withTimeoutOrNull(DISCOVER_MS) { runCatching { found.await() }.getOrNull() }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
            synchronized(resolving) { resolving.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } } }
            exec.shutdown()
        }
    }

    /** True when something (adbd) listens on [port]: binding it on loopback fails. */
    private fun inUse(port: Int): Boolean = try {
        ServerSocket().use { it.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port)) }
        false
    } catch (_: IOException) {
        true
    }
}
