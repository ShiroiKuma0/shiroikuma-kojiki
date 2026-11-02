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

import com.celzero.bravedns.customui.KojikiDiag
import com.celzero.bravedns.customui.KojikiDiag.Group
import com.celzero.bravedns.util.UIUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fork (白い熊 考直): the two network diagnostics (see [KojikiDiag]), both off by default.
 *
 * **VPN restarts** ([Group.VPN]) — every engine start, recycle and stop with its reason, and a count
 * of *orphaned alg ips*: flows aimed at an alg address (100.64/10, 64:ff9b:1:da19:100::/80) that the
 * engine has no record of. The alg table lives in the engine's memory only, so every restart of the
 * process — an app update, a crash, a force-stop — orphans the alg ips apps still hold; on
 * 2026-11-02 that left ~1150 dead flows and ~5 minutes without connectivity after one install. The
 * count is reported 60 s and 5 min after each engine start, with the first few flows named.
 *
 * **WireGuard health** ([Group.WG]) — every minute, per active tunnel: status, handshake age, rx/tx
 * and the last error, written when something changed or a handshake is overdue, plus a heartbeat.
 */
object KojikiNetDiag {

    private const val SAMPLE_ORPHANS = 5
    private const val WG_EVERY_MS = 60_000L
    private const val WG_HEARTBEAT_MS = 15 * 60_000L
    private const val WG_HANDSHAKE_STALE_MS = 3 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- VPN restarts ----

    private val orphans = AtomicInteger(0)
    @Volatile private var engineSince = 0L
    private var reportJob: Job? = null

    fun init() {
        KojikiDiag.addListener { g, on ->
            if (g == Group.WG) { if (on) startWg() else stopWg() }
        }
        if (KojikiDiag.isOn(Group.WG)) startWg()
    }

    /** The engine (and with it a fresh, empty alg table) was created — [how] says why. */
    fun onEngineUp(how: String) {
        if (!KojikiDiag.isOn(Group.VPN)) return
        orphans.set(0)
        engineSince = System.currentTimeMillis()
        KojikiDiag.log(Group.VPN, "engine up: $how")
        synchronized(this) {
            reportJob?.cancel()
            reportJob = scope.launch {
                delay(60_000L)
                KojikiDiag.log(Group.VPN, "  orphaned alg ips in the first 60 s: ${orphans.get()}")
                delay(240_000L)
                KojikiDiag.log(Group.VPN, "  orphaned alg ips in the first 5 min: ${orphans.get()}")
            }
        }
    }

    fun onEvent(what: String) {
        KojikiDiag.log(Group.VPN, what)
    }

    /** Called from BraveVPNService.flow for every new flow; must stay cheap when off. */
    fun onFlow(uid: Int, dst: String?, realIps: String?, undidAlg: Boolean) {
        if (!KojikiDiag.isOn(Group.VPN)) return
        if (undidAlg || !realIps.isNullOrEmpty() || dst == null || !isAlgSpace(dst)) return
        val n = orphans.incrementAndGet()
        if (n <= SAMPLE_ORPHANS) {
            val after = if (engineSince > 0) (System.currentTimeMillis() - engineSince) / 1000 else -1
            KojikiDiag.log(Group.VPN, "  orphaned alg ip #$n: uid $uid → $dst (${after}s after engine up)")
        }
    }

    // "100.74.49.249:443" or "[64:ff9b:1:da19:100:ed49:cae5:bc60]:443"
    internal fun isAlgSpace(dst: String): Boolean {
        val host = if (dst.startsWith("[")) {
            dst.substring(1, dst.indexOf(']').let { if (it < 1) dst.length else it })
        } else {
            dst.substringBeforeLast(':')
        }
        if (host.contains(':')) return host.lowercase().startsWith("64:ff9b:1:da19:100:")
        val p = host.split('.')
        return p.size == 4 && p[0] == "100" && (p[1].toIntOrNull() ?: -1) in 64..127
    }

    // ---- WireGuard health ----

    private var wgJob: Job? = null

    private data class WgSeen(val status: Int?, val lastErr: String, val stale: Boolean)

    private fun startWg() = synchronized(this) {
        if (wgJob?.isActive == true) return
        wgJob = scope.launch {
            val seen = HashMap<String, WgSeen>()
            var lastBeat = 0L
            while (isActive) {
                val now = System.currentTimeMillis()
                val beat = now - lastBeat >= WG_HEARTBEAT_MS
                if (VpnController.hasTunnel()) {
                    for (m in WireguardManager.getAllMappings().filter { it.isActive }) {
                        val id = ProxyManager.ID_WG_BASE + m.id
                        val status = runCatching { VpnController.getProxyStatusById(id).first }.getOrNull()
                        val st = runCatching { VpnController.getProxyStats(id) }.getOrNull()
                        val lastOk = st?.lastOK ?: 0L
                        val age = if (lastOk > 0) (now - lastOk) / 1000 else -1
                        val stale = lastOk <= 0 || now - lastOk > WG_HANDSHAKE_STALE_MS
                        val err = st?.lastErr.orEmpty()
                        val cur = WgSeen(status, err, stale)
                        if (beat || seen[id] != cur) {
                            seen[id] = cur
                            KojikiDiag.log(
                                Group.WG,
                                "${m.name} ($id): ${statusName(status)}, handshake " +
                                    (if (age < 0) "never" else "${age}s ago") +
                                    ", rx ${st?.rx ?: 0} tx ${st?.tx ?: 0}" +
                                    (if (err.isNotEmpty()) ", last err: ${err.take(160)}" else "")
                            )
                        }
                    }
                } else if (beat) {
                    KojikiDiag.log(Group.WG, "no tunnel")
                }
                if (beat) lastBeat = now
                delay(WG_EVERY_MS)
            }
        }
    }

    private fun stopWg() = synchronized(this) {
        wgJob?.cancel()
        wgJob = null
    }

    private fun statusName(id: Int?): String =
        UIUtils.ProxyStatus.entries.firstOrNull { it.id == id }?.name ?: "unknown($id)"
}
