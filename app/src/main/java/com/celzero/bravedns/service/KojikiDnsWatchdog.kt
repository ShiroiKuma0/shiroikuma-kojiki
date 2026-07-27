/*
 * Fork (白い熊 考直): DNS watchdog.
 *
 * Diagnosed 2026-07-17: firestack's DoH transport wedges chronically — fresh connections to the
 * upstream (observed with Quad9) die with EOF ~3s after dial and the engine's retrier gives up
 * ("redo? false"), surfacing as http-status 502 / "unexpected EOF" on app queries. DNS then goes
 * dark in bursts, ALG mappings expire (realips([])), allowed apps' flows die, and the system's own
 * probes (uid 1000) fail, so Android network validation flaps device-wide (even VPN-excluded apps
 * feel it). The engine's ICMP netstack endpoints also wedge permanently ("endpoint is in invalid
 * state") — only a full tunnel re-create clears either.
 *
 * This watchdog is fed every non-cached DNS transaction from NetLogTracker.processDnsLog. On a
 * streak of upstream failures it:
 *   1. forces a FULL Go-tunnel recycle (GoVpnAdapter.restartTunnel via the fork hook in
 *      BraveVPNService.makeOrUpdateVpnAdapter — a plain link update keeps the wedged Go state);
 *   2. if the wedge re-triggers shortly after a restart (and DNS *was* healthy recently, so this
 *      isn't just a dead network), fails the DoH endpoint over to the fallback (Google) and
 *      recycles again.
 * Each action raises a notification. Cooldowns prevent restart loops.
 */
package com.celzero.bravedns.service

import Logger
import Logger.LOG_TAG_VPN
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DoHEndpointRepository
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.util.UIUtils.getAccentColor
import com.celzero.bravedns.util.Utilities.isAtleastO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object KojikiDnsWatchdog : KoinComponent {

    private const val TAG = "KojikiDnsWatchdog"

    // trigger: rate-based over a sliding window, NOT a consecutive streak — the wedge is bursty
    // and partial (failures interleave with the odd query that squeaks through, as the 2026-07-17
    // captures show), so a streak counter that resets on every success never fires. Trigger when
    // the window holds >= FAIL_THRESHOLD upstream failures, regardless of successes.
    private const val WINDOW_MS = 3 * 60_000L
    private const val FAIL_THRESHOLD = 8
    private const val WINDOW_CAP = 512 // hard bound on remembered events

    // at most one action per cooldown window, so a dead network can't cause a restart storm
    private const val ACTION_COOLDOWN_MS = 5 * 60_000L

    // Startup is NOT a wedge. Every fresh tunnel — app update, reboot, or the watchdog's own
    // recycle — begins with a burst of DNS failures: the VpnService was torn down, every app on
    // the device retries at once, and the engine is still dialling its first DoH connection. That
    // burst clears FAIL_THRESHOLD easily and used to fire a "DNS wedged" notification seconds
    // after opening the app (reported 2026-07-27). So the watchdog stays disarmed until the
    // tunnel has settled for WARMUP_MS *and* has resolved at least one name upstream — a wedge is
    // by definition "DNS worked, then it stopped", so with no success yet there is nothing to
    // diagnose as wedged (and nothing a recycle would cure).
    private const val WARMUP_MS = 2 * 60_000L

    // a re-trigger this soon after a restart means the restart didn't cure it -> fail over
    private const val FAILOVER_WINDOW_MS = 15 * 60_000L

    // ... but only fail over if DNS worked recently on this network (otherwise the "wedge" is
    // likely just no connectivity, and switching the user's DNS choice would be wrong)
    private const val RECENT_SUCCESS_MS = 30 * 60_000L

    // Google: robust-class infrastructure, different org from the Cloudflare primary. On the
    // STOCK engine the fallback must be idle-tolerant (>3-min pool) — Quad9/Mullvad kill idle
    // conns at <=90s and would fail over INTO the same wedge (2026-07-17 measurements).
    private const val FALLBACK_DOH_URL = "https://dns.google/dns-query"

    private const val NOTIF_ID = 10088

    private val appConfig: AppConfig by inject()
    private val dohRepo: DoHEndpointRepository by inject()
    private val persistentState: PersistentState by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val failTimes = ArrayDeque<Long>()
    private val okTimes = ArrayDeque<Long>()
    private var lastSuccessAt = 0L
    private var lastActionAt = 0L
    private var lastRestartAt = 0L

    // when the current tunnel came up, and whether it has resolved anything upstream since — the
    // two halves of the warm-up gate (see WARMUP_MS)
    private var armedAt = 0L
    private var resolvedSinceArm = false

    /**
     * Called when a tunnel is created or recycled ([BraveVPNService.makeOrUpdateVpnAdapter]) — it
     * restarts the warm-up, so the startup failure burst that follows every tunnel bring-up is not
     * mistaken for a wedge.
     */
    fun onTunnelUp() {
        synchronized(this) {
            armedAt = SystemClock.elapsedRealtime()
            resolvedSinceArm = false
            failTimes.clear()
            okTimes.clear()
        }
    }

    // called from NetLogTracker.processDnsLog for every DNS response; must stay cheap
    fun onDnsTransaction(t: Transaction) {
        // failure diagnostics via android.util.Log directly: the app's Logger is gated by the
        // in-app log level (observed fully silent in logcat), which would hide the telemetry
        if (t.status != Transaction.Status.COMPLETE && t.status != Transaction.Status.START) {
            android.util.Log.e(
                TAG,
                "diag: FAIL status=${t.status} cached=${t.isCached} q=${t.qName} " +
                    "server=${t.serverName} msg=${t.msg.take(80)}"
            )
        }
        // NOTE: do NOT skip on isCached — the engine's caching wrapper (CachePreferred) stamps
        // cached=true on every summary that flows through it, INCLUDING upstream failures for
        // never-before-seen names (observed live 2026-07-17 16:35). Only cached SUCCESSES are
        // meaningless for upstream health; failures count regardless of the flag.
        val now = SystemClock.elapsedRealtime()
        // fallback arming: if the tunnel hook never fired (a path we don't know about), arm off the
        // first transaction this process sees, so the watchdog can never be silent forever
        synchronized(this) { if (armedAt == 0L) armedAt = now }
        when (t.status) {
            Transaction.Status.COMPLETE ->
                if (!t.isCached) synchronized(this) {
                    okTimes.addLast(now)
                    lastSuccessAt = now
                    resolvedSinceArm = true
                    prune(now)
                }
            Transaction.Status.SEND_FAIL,
            Transaction.Status.TRANSPORT_ERROR,
            Transaction.Status.NO_RESPONSE,
            Transaction.Status.BAD_RESPONSE,
            Transaction.Status.INTERNAL_ERROR -> onUpstreamFailure(now)
            else -> {} // START / BAD_QUERY / CLIENT_ERROR: not upstream-health signals
        }
    }

    private fun prune(now: Long) {
        while (failTimes.isNotEmpty() &&
            (now - failTimes.first() > WINDOW_MS || failTimes.size > WINDOW_CAP)) {
            failTimes.removeFirst()
        }
        while (okTimes.isNotEmpty() &&
            (now - okTimes.first() > WINDOW_MS || okTimes.size > WINDOW_CAP)) {
            okTimes.removeFirst()
        }
    }

    private fun onUpstreamFailure(now: Long) {
        val failover: Boolean
        synchronized(this) {
            // still warming up, or DNS has not worked once since this tunnel came up: startup
            // noise, not a wedge. Drop the event outright so it can't count later either.
            if (now - armedAt < WARMUP_MS || !resolvedSinceArm) return
            failTimes.addLast(now)
            prune(now)
            val fails = failTimes.size
            val oks = okTimes.size
            // breadcrumb once the window starts filling; Logger.e because the app's Logger gates
            // by goLoggerLevel (default ERROR) and WARN would be dropped from logcat
            if (fails >= FAIL_THRESHOLD / 2) {
                Logger.e(
                    LOG_TAG_VPN,
                    "$TAG: window fails=$fails oks=$oks (trigger: fails>=$FAIL_THRESHOLD)"
                )
                android.util.Log.e(TAG, "window fails=$fails oks=$oks (trigger: fails>=$FAIL_THRESHOLD)")
            }
            // no success-ratio gate: the wedge is bursty — 10-30s bursts break apps even when the
            // 3-min window holds more successes than failures. A recycle is cheap and
            // cooldown-limited; only the failover needs (and has) stronger guards.
            if (fails < FAIL_THRESHOLD) return
            if (now - lastActionAt < ACTION_COOLDOWN_MS) return
            failTimes.clear()
            okTimes.clear()
            lastActionAt = now
            failover =
                lastRestartAt != 0L &&
                    now - lastRestartAt < FAILOVER_WINDOW_MS &&
                    now - lastSuccessAt < RECENT_SUCCESS_MS
            if (!failover) lastRestartAt = now
        }
        if (!VpnController.hasTunnel()) return
        if (failover) doFailover() else doRestart()
    }

    private fun doRestart() {
        Logger.e(LOG_TAG_VPN, "$TAG: DoH failure streak; forcing full tunnel recycle")
        android.util.Log.e(TAG, "TRIGGER: forcing full tunnel recycle")
        VpnController.kojikiForceEngineRestart(
            "kojikiDnsWatchdog, at: ${SystemClock.elapsedRealtime()}"
        )
        notify(
            "DNS wedged — tunnel restarted",
            "Repeated DNS failures detected; the tunnel engine was restarted to clear them. " +
                "If the wedge returns shortly, DNS will fail over to Google DoH."
        )
    }

    private fun doFailover() {
        scope.launch {
            try {
                val doh =
                    dohRepo.getAllDefaultDoHEndpoints().firstOrNull {
                        it.dohURL == FALLBACK_DOH_URL
                    }
                if (doh == null || persistentState.connectedDnsName == doh.dohName) {
                    // already on the fallback (or it's missing): recycle again instead
                    Logger.e(LOG_TAG_VPN, "$TAG: wedge persists on fallback; recycling again")
                    android.util.Log.e(TAG, "TRIGGER: wedge persists on fallback; recycling again")
                    synchronized(this@KojikiDnsWatchdog) {
                        lastRestartAt = SystemClock.elapsedRealtime()
                    }
                    VpnController.kojikiForceEngineRestart(
                        "kojikiDnsWatchdog-refailover, at: ${SystemClock.elapsedRealtime()}"
                    )
                    notify(
                        "DNS wedged again — tunnel restarted",
                        "DNS failures persist on the fallback resolver; the tunnel engine was " +
                            "restarted again."
                    )
                    return@launch
                }
                val prev = persistentState.connectedDnsName
                android.util.Log.e(TAG, "TRIGGER: failing over to fallback DoH")
                Logger.e(LOG_TAG_VPN, "$TAG: wedge returned after restart; failing over " +
                        "'$prev' -> '${doh.dohName}'")
                // same select sequence as KojikiExport's DNS import (proven to stick): select in
                // the DB first, then best-effort live switch
                dohRepo.removeConnectionStatus()
                doh.isSelected = true
                dohRepo.update(doh)
                runCatching { appConfig.handleDoHChanges(doh) }
                synchronized(this@KojikiDnsWatchdog) {
                    lastRestartAt = SystemClock.elapsedRealtime()
                }
                // recycle too: the failover must also clear wedged ICMP netstack endpoints
                VpnController.kojikiForceEngineRestart(
                    "kojikiDnsWatchdog-failover, at: ${SystemClock.elapsedRealtime()}"
                )
                notify(
                    "DNS failed over to ${doh.dohName}",
                    "The tunnel restart did not cure the DNS wedge, so the resolver was switched " +
                        "from '$prev' to '${doh.dohName}' and the tunnel was restarted."
                )
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "$TAG: failover failed: ${e.message}", e)
            }
        }
    }

    private fun notify(title: String, text: String) {
        try {
            val ctx: Context = VpnController.kojikiContext() ?: return
            val nm =
                ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return
            if (isAtleastO()) {
                val channel =
                    NotificationChannel(
                        FirewallManager.NOTIF_CHANNEL_ID_FIREWALL_ALERTS,
                        ctx.getString(R.string.notif_channel_firewall_alerts),
                        NotificationManager.IMPORTANCE_HIGH
                    )
                nm.createNotificationChannel(channel)
            }
            val builder =
                NotificationCompat.Builder(ctx, FirewallManager.NOTIF_CHANNEL_ID_FIREWALL_ALERTS)
                    .setSmallIcon(R.drawable.ic_notification_icon)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
            builder.color = ContextCompat.getColor(ctx, getAccentColor(persistentState.theme))
            nm.notify(NOTIF_ID, builder.build())
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG: notify failed: ${e.message}", e)
        }
    }
}
