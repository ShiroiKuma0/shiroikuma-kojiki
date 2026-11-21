/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap

/**
 * Fork (白い熊 考直): the per-app **Roaming** control — the third toggle beside WiFi and mobile data.
 *
 * An app whose roaming toggle is off gets no network while the phone's data runs over a roaming
 * mobile network. Unlike the WiFi / data toggles it holds under **every** firewall status: a bypass
 * does not lift it (the check sits at the head of [TunFirewallManager.firewall], before either
 * bypass can return), and neither does Exclude — an excluded app is outside the tunnel where nothing
 * can be blocked, so while roaming it is taken back **into** the tunnel ([filterExcluded], consumed
 * by the VPN builder) and blocked there like any other.
 *
 * Stored by **package name** in its own prefs file — no Room column, so no migration and nothing for
 * a rebase to collide with. A rule is checked per uid, so a toggle writes every package of the uid.
 *
 * "Roaming" is read off the network the phone's traffic actually leaves by — the best non-VPN
 * internet network — being cellular without `NET_CAPABILITY_NOT_ROAMING`. On WiFi abroad nothing is
 * blocked: the SIM may be roaming, the data is not.
 */
object KojikiRoaming {

    private const val PREFS = "kojiki_roaming"
    private const val KEY_BLOCKED = "blocked"

    @Volatile private var blocked: Set<String> = emptySet()
    @Volatile private var loaded = false
    @Volatile private var registered = false

    /** The underlying network is cellular (whether or not it roams). */
    @Volatile private var onCellular = false
    /** The underlying network is cellular and roaming, as the system reports it. */
    @Volatile private var systemRoaming = false

    /**
     * Test aid: treat any mobile-data network as roaming. Held in memory only, so it cannot outlive
     * the process and be forgotten on.
     */
    @Volatile var simulate = false
        private set

    /** Whether data currently leaves over a roaming mobile network. */
    val onRoamingData: Boolean
        get() = systemRoaming || (simulate && onCellular)

    /** Set by the VPN service: called (on any thread) when [onRoamingData] or the store changes. */
    @Volatile var onChange: (() -> Unit)? = null

    fun init(context: Context) {
        load(context)
        if (registered) return
        registered = true
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        // NOT_VPN is in a request by default, so this never sees our own tunnel.
        val request =
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                cm.registerBestMatchingNetworkCallback(
                    request, BestCallback(), Handler(Looper.getMainLooper()))
            } else {
                cm.registerNetworkCallback(request, AllCallback())
            }
        } catch (_: Exception) {
            registered = false
        }
    }

    private fun load(context: Context) {
        if (loaded) return
        blocked =
            HashSet(
                context.applicationContext
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet())
        loaded = true
    }

    /** Whether [packageName]'s roaming toggle is off. */
    fun isBlocked(context: Context, packageName: String): Boolean {
        load(context)
        return packageName in blocked
    }

    fun blockedPackages(context: Context): Set<String> {
        load(context)
        return blocked
    }

    /** Turn roaming off ([block]) or back on for every package in [packages]. */
    @Synchronized
    fun setBlocked(context: Context, packages: Collection<String>, block: Boolean) {
        load(context)
        val next = HashSet(blocked)
        val changed = if (block) next.addAll(packages) else next.removeAll(packages.toSet())
        if (!changed) return
        blocked = next
        // commit(): an import is followed by a process restart, which would lose an apply()
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_BLOCKED, HashSet(next))
            .commit()
        onChange?.invoke()
    }

    fun setSimulate(on: Boolean) {
        if (simulate == on) return
        simulate = on
        KojikiNetDiag.onEvent("roaming: simulate=$on, roaming-data=$onRoamingData")
        onChange?.invoke()
    }

    /**
     * Roaming is mobile data: an app whose mobile-data toggle is off ([connectionStatusId] blocks
     * metered networks) has no roaming either, whatever its roaming toggle says. The firewall
     * already refuses it (the app's own block); this is for the toggle, which must not read
     * "allowed" beside a struck mobile-data toggle.
     */
    fun dataBlocked(connectionStatusId: Int): Boolean =
        connectionStatusId == FirewallManager.ConnectionStatus.METERED.id ||
            connectionStatusId == FirewallManager.ConnectionStatus.BOTH.id

    /** Whether any package of [uid] has its roaming toggle off. */
    suspend fun uidBlocked(uid: Int): Boolean {
        val set = blocked
        if (set.isEmpty()) return false
        return FirewallManager.getPackageNamesByUid(uid).any { it in set }
    }

    /** The firewall's question: must [uid] be refused right now? */
    suspend fun blocksNow(uid: Int): Boolean = onRoamingData && uidBlocked(uid)

    /**
     * The packages to keep **out** of the tunnel: [excluded] as the user set it, minus — while data
     * roams — those whose roaming toggle is off, which must be inside the tunnel to be blocked.
     */
    fun filterExcluded(excluded: Collection<String>): MutableSet<String> {
        val out: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val set = blocked
        if (!onRoamingData || set.isEmpty()) {
            out.addAll(excluded)
        } else {
            excluded.filterTo(out) { it !in set }
        }
        return out
    }

    private fun update(cellular: Boolean, roaming: Boolean) {
        val before = onRoamingData
        onCellular = cellular
        systemRoaming = roaming
        if (before == onRoamingData) return
        KojikiNetDiag.onEvent("roaming: roaming-data=$onRoamingData (system=$roaming, simulate=$simulate)")
        onChange?.invoke()
    }

    private fun isCellular(caps: NetworkCapabilities) =
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

    private fun isRoaming(caps: NetworkCapabilities) =
        isCellular(caps) && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)

    /** API 31+: the system hands us the one best underlying network, and each change of it. */
    private class BestCallback : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            update(isCellular(caps), isRoaming(caps))
        }

        override fun onLost(network: Network) {
            update(cellular = false, roaming = false)
        }
    }

    /**
     * Before API 31: every internet network is reported, so the data is taken to roam only when a
     * roaming cellular network is up and nothing else (WiFi, ethernet) is there to carry it.
     */
    private class AllCallback : ConnectivityManager.NetworkCallback() {
        private val nets = ConcurrentHashMap<Network, NetworkCapabilities>()

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            nets[network] = caps
            recompute()
        }

        override fun onLost(network: Network) {
            nets.remove(network)
            recompute()
        }

        private fun recompute() {
            val all = nets.values
            val onlyCellular = all.isNotEmpty() && all.all { isCellular(it) }
            update(onlyCellular, onlyCellular && all.any { isRoaming(it) })
        }
    }
}
