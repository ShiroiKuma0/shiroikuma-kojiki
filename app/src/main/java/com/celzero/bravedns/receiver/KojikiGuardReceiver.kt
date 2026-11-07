/*
 * Copyright 2020 RethinkDNS and its authors
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
package com.celzero.bravedns.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.SystemClock
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.service.KojikiDnsWatchdog
import com.celzero.bravedns.service.KojikiNetDiag
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_VPN
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Fork (白い熊 考直): the guard intents — 自由作業盤 (Jiyūsagyōban)'s 生存管理 VPN/internet guard
 * asks for kojiki's state, and cures a wedge by recycling the engine or stopping and starting the
 * VPN (2026-11-07, after DNS was dead all night). The guard probes the internet itself — it is an
 * ordinary tunnelled app; kojiki's own traffic bypasses the tunnel — and uses these to act.
 *
 * Actions: [ACTION_STATUS], [ACTION_RECYCLE], [ACTION_VPN_STOP], [ACTION_VPN_START]. Extras, all
 * strings: `token` (the shared automation token; checked only while "require token" is on, like the
 * automation data door — PersistentState.refuseAutomation),
 * `reply_package` + `reply_action` (where the answer goes; without them the action still runs, it
 * just answers no one), `request_id` (echoed). When refused nothing runs; the answer is only
 * op / ok=false / error=bad-token|automation-disabled / request_id, and the VPN-restarts trace
 * records it.
 *
 * The answer is a broadcast with `reply_action`, sent only to `reply_package`, every extra a string:
 * request_id · op · ok · error · elapsed_ms · version · vpn_wanted · tunnel_up · stopping · network ·
 * dns_answer_age_s · dns_unanswered · dns_stall · resolver · resolver_fallback · wg. STATUS answers
 * at once; RECYCLE once the new engine is up, VPN_STOP once the service is really gone, VPN_START
 * once the tunnel is up — each within [WAIT_MS], else ok=false error=timeout.
 *
 * Always addressed explicitly:
 *   adb shell am broadcast -n shiroikuma.kojiki/com.celzero.bravedns.receiver.KojikiGuardReceiver \
 *     -a com.celzero.bravedns.intent.action.KOJIKI_STATUS --es token <TOKEN> \
 *     --es reply_package <pkg> --es reply_action <pkg>.KOJIKI_REPLY --es request_id t1
 * After a force-stop the sender must add FLAG_INCLUDE_STOPPED_PACKAGES (`am broadcast -f 0x20`).
 */
class KojikiGuardReceiver : BroadcastReceiver(), KoinComponent {

    private val persistentState by inject<PersistentState>()

    companion object {
        const val ACTION_STATUS = "com.celzero.bravedns.intent.action.KOJIKI_STATUS"
        const val ACTION_RECYCLE = "com.celzero.bravedns.intent.action.KOJIKI_RECYCLE"
        const val ACTION_VPN_STOP = "com.celzero.bravedns.intent.action.KOJIKI_VPN_STOP"
        const val ACTION_VPN_START = "com.celzero.bravedns.intent.action.KOJIKI_VPN_START"
        private const val TAG = "KojikiGuardReceiver"

        // a background broadcast may run 60 s under goAsync; the guard waits 20 s per step
        private const val WAIT_MS = 20_000L
        private const val POLL_MS = 250L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val op = when (intent.action) {
            ACTION_STATUS -> "status"
            ACTION_RECYCLE -> "recycle"
            ACTION_VPN_STOP -> "stop"
            ACTION_VPN_START -> "start"
            else -> return
        }
        // The same gate as the automation data door (PersistentState.refuseAutomation): the token is
        // checked only while Settings → "require token" is on, and the automation master switch
        // can refuse everything. 白い熊's call, 2026-11-07 — unlike SET_APP_RULE / SET_WG_STATE,
        // which still demand the token unconditionally.
        val refused = persistentState.refuseAutomation(intent.getStringExtra("token"))
        if (refused != null) {
            val error = if (refused.contains("disabled")) "automation-disabled" else "bad-token"
            // Answered and traced, so the caller can tell "refused" from "not received"
            // (2026-11-07: a stale token looked exactly like a hung kojiki). The answer carries no
            // state at all — only op / ok=false / error / request_id.
            Logger.w(LOG_TAG_VPN, "$TAG: $op: refused ($error)")
            KojikiNetDiag.onEvent("guard $op: refused — $error")
            val pkg = intent.getStringExtra("reply_package")?.trim().orEmpty()
            val action = intent.getStringExtra("reply_action")?.trim().orEmpty()
            if (pkg.isNotEmpty() && action.isNotEmpty()) {
                context.sendBroadcast(
                    Intent(action).setPackage(pkg)
                        .putExtra("request_id", intent.getStringExtra("request_id").orEmpty())
                        .putExtra("op", op)
                        .putExtra("ok", "false")
                        .putExtra("error", error)
                )
            }
            return
        }
        val appCtx = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val started = SystemClock.elapsedRealtime()
            val (ok, error) = try {
                when (op) {
                    "status" -> true to ""
                    "recycle" -> recycle()
                    "stop" -> stop(appCtx)
                    else -> start(appCtx)
                }
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "$TAG: $op: ${e.message}", e)
                false to "error: ${e.message}"
            }
            try {
                KojikiNetDiag.onEvent("guard $op: ok? $ok${if (error.isNotEmpty()) " ($error)" else ""}")
                reply(appCtx, intent, op, ok, error, SystemClock.elapsedRealtime() - started)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun recycle(): Pair<Boolean, String> {
        if (!VpnController.hasTunnel()) return false to "not-running"
        val before = KojikiDnsWatchdog.tunnelUps
        VpnController.kojikiForceEngineRestart("kojikiGuard, at: ${SystemClock.elapsedRealtime()}")
        return waitFor { KojikiDnsWatchdog.tunnelUps != before && VpnController.hasTunnel() }
    }

    private suspend fun stop(ctx: Context): Pair<Boolean, String> {
        val id = VpnController.kojikiServiceId()
        if (id == 0) return true to "" // already off
        VpnController.stop("guard", ctx)
        return waitFor { VpnController.kojikiServiceId() != id }
    }

    private suspend fun start(ctx: Context): Pair<Boolean, String> {
        if (VpnController.hasTunnel()) return true to "" // already up
        // VPN consent can only be given from an activity; without it a start cannot work
        if (VpnService.prepare(ctx) != null) return false to "no-permission"
        VpnController.start(ctx)
        return waitFor { VpnController.hasTunnel() }
    }

    private suspend fun waitFor(done: () -> Boolean): Pair<Boolean, String> {
        val until = SystemClock.elapsedRealtime() + WAIT_MS
        while (SystemClock.elapsedRealtime() < until) {
            if (done()) return true to ""
            delay(POLL_MS)
        }
        return if (done()) true to "" else false to "timeout"
    }

    private suspend fun reply(ctx: Context, req: Intent, op: String, ok: Boolean, error: String, elapsed: Long) {
        val pkg = req.getStringExtra("reply_package")?.trim().orEmpty()
        val action = req.getStringExtra("reply_action")?.trim().orEmpty()
        if (pkg.isEmpty() || action.isEmpty()) return
        val out = Intent(action).setPackage(pkg)
            .putExtra("request_id", req.getStringExtra("request_id").orEmpty())
            .putExtra("op", op)
            .putExtra("ok", ok.toString())
            .putExtra("error", error)
            .putExtra("elapsed_ms", elapsed.toString())
            .putExtra("version", BuildConfig.VERSION_NAME)
            .putExtra("vpn_wanted", persistentState.getVpnEnabled().toString())
            .putExtra("tunnel_up", VpnController.hasTunnel().toString())
            .putExtra("stopping", VpnController.kojikiStopping().toString())
            .putExtra("network", hasUnderlyingNetwork(ctx).toString())
            .putExtra("dns_answer_age_s", KojikiDnsWatchdog.dnsAnswerAgeS().toString())
            .putExtra("dns_unanswered", KojikiDnsWatchdog.dnsUnanswered().toString())
            .putExtra("dns_stall", KojikiDnsWatchdog.dnsStalled().toString())
            .putExtra("resolver", persistentState.connectedDnsName)
            .putExtra("resolver_fallback", KojikiDnsWatchdog.onFallback().toString())
            .putExtra("wg", try { KojikiNetDiag.wgSummary() } catch (_: Exception) { "" })
        ctx.sendBroadcast(out)
    }

    // any non-VPN network that carries internet — what the tunnel would ride on
    @Suppress("DEPRECATION")
    private fun hasUnderlyingNetwork(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@any false
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
    }
}
