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
import android.net.VpnService
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent

/**
 * Fork (白い熊 考直): the VPN comes back by itself whenever the process starts and the VPN was
 * wanted — after a reboot above all.
 *
 * Upstream starts the VPN after boot from one place only: the boot broadcast schedules
 * `BootStartWorker` (WorkManager, 1.5 s later), which starts the service. On 2026-11-25 the
 * Master rebooted at 19:01; the process was started at 19:01:56 and the VPN stayed down until
 * 白い熊 opened the app at 19:10. Which link broke — the broadcast, the worker, or the service
 * start — could not be read afterwards, because none of them left a line anywhere.
 *
 * So the start no longer hangs on that one chain: every process start (whatever caused it — the
 * boot broadcast, the accessibility service being bound, a job) checks a few times whether the VPN
 * is wanted and missing, and starts it. Every step, upstream's chain included, writes a line to the
 * VPN-restarts trace ([KojikiNetDiag.onEvent]), so the next reboot can be read.
 */
object KojikiBootStart {

    // seconds after the process start at which the VPN is looked for
    private val CHECK_AT_S = longArrayOf(2, 10, 30, 60, 120, 300)
    private const val NEAR_BOOT_MS = 5 * 60_000L
    private val WG_REPORT_AFTER_S = longArrayOf(30, 90)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun trace(line: String) = KojikiNetDiag.onEvent(line)

    fun sinceBoot(): String = "${SystemClock.elapsedRealtime() / 1000} s after boot"

    /** From `RethinkDnsApplication.onCreate`, once Koin is up. */
    fun onProcessStart(app: Context) {
        val ctx = app.applicationContext
        scope.launch {
            var waited = 0L
            for (at in CHECK_AT_S) {
                delay((at - waited) * 1000L)
                waited = at
                val again = try {
                    check(ctx, at)
                } catch (e: Exception) {
                    trace("start-up check +$at s failed: ${e.javaClass.simpleName}: ${e.message}")
                    true
                }
                if (!again) return@launch
            }
            trace("start-up check: gave up after ${CHECK_AT_S.last()} s, vpn still not up")
        }
    }

    /** @return whether to look again later. */
    private suspend fun check(ctx: Context, at: Long): Boolean {
        val ps = KoinJavaComponent.get<PersistentState>(PersistentState::class.java)
        val head = "start-up check +$at s"
        if (VpnController.hasTunnel()) {
            trace("$head: vpn is up")
            reportWireGuard()
            return false
        }
        if (!ps.getVpnEnabled()) {
            trace("$head: vpn not wanted (it was stopped before the process ended), nothing to start")
            return false
        }
        if (SystemClock.elapsedRealtime() < NEAR_BOOT_MS && !ps.prefAutoStartBootUp) {
            trace("$head: vpn wanted, but the start-on-boot setting is off")
            return false
        }
        if (VpnController.isAlwaysOn(ctx)) {
            trace("$head: always-on vpn is set, left to android")
            return false
        }
        val consent = try {
            VpnService.prepare(ctx)
        } catch (e: Exception) {
            trace("$head: android refused the vpn (${e.javaClass.simpleName}: ${e.message})")
            return true
        }
        if (consent != null) {
            trace("$head: vpn wanted, but android asks for the vpn consent again")
            return false
        }
        trace("$head: vpn wanted and not up, starting it")
        VpnController.start(ctx, autoAttempt = true)
        return true
    }

    private fun reportWireGuard() {
        scope.launch {
            var waited = 0L
            for (at in WG_REPORT_AFTER_S) {
                delay((at - waited) * 1000L)
                waited = at
                val s = try { KojikiNetDiag.wgSummary() } catch (e: Exception) { "?: ${e.message}" }
                trace("  wireguard $at s later (name:status:last ok): ${s.ifEmpty { "no active profile, or no tunnel" }}")
            }
        }
    }
}
