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
 *      recycles again — for a while only: the chosen resolver is tried again after 15 min
 *      (doubling on a relapse), see "the failover is temporary" below.
 * Each action raises a notification. Cooldowns prevent restart loops.
 */
package com.celzero.bravedns.service

import com.celzero.bravedns.customui.KojikiDiag
import com.celzero.bravedns.customui.KojikiThreadDump
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_VPN
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    // names that drew an HTTP error status in the window, and when (see onDnsTransaction)
    private val statusFailNames = HashMap<String, Long>()
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
    // ---- read by the guard intents (KojikiGuardReceiver, 2026-11-07) ----
    @Volatile private var lastAnsweredAt = 0L
    /** Bumped on every tunnel bring-up — a RECYCLE is done when this moves. */
    @Volatile var tunnelUps = 0
        private set

    /** Seconds since the last DNS answer of any kind, -1 when none since the process started. */
    fun dnsAnswerAgeS(): Long =
        lastAnsweredAt.let { if (it == 0L) -1L else (SystemClock.elapsedRealtime() - it) / 1000 }

    fun dnsUnanswered(): Int = synchronized(this) { askedSinceAnswer }

    fun dnsStalled(): Boolean = synchronized(this) { stallReported }

    /** On the temporary fallback resolver, with a return to the chosen one pending. */
    fun onFallback(): Boolean = runCatching { prefs().getString(K_URL, null) != null }.getOrDefault(false)

    fun onTunnelUp() {
        tunnelUps++
        synchronized(this) {
            armedAt = SystemClock.elapsedRealtime()
            resolvedSinceArm = false
            failTimes.clear()
            okTimes.clear()
            statusFailNames.clear()
            firstUnansweredAt = 0L
            askedSinceAnswer = 0
            stallReported = false
        }
        // a failover plan from before a process restart: re-arm its timer
        if (returnJob?.isActive != true) runCatching { scheduleReturn() }
    }

    // ---- stall: questions asked, nothing answered (2026-11-07) ----
    //
    // The failure watch above only ever sees answers — a failed one is still an answer. On
    // 2026-11-06/07 DNS was dead all night with not a single failure logged: the questions were
    // reaching kojiki (onQuery) and no answer came back at all (onResponse), so the watchdog stayed
    // silent. This watches the gap itself: STALL_MIN_ASKED questions over STALL_MS with no answer in
    // between is a stall — dump the threads (what is holding it) and recycle the engine.
    private const val STALL_MS = 30_000L
    private const val STALL_MIN_ASKED = 3
    private var firstUnansweredAt = 0L
    private var askedSinceAnswer = 0
    private var stallReported = false

    /** Every question the engine hands kojiki (BraveVPNService.onQuery); must stay cheap. */
    fun onAsked() {
        val now = SystemClock.elapsedRealtime()
        val stalledFor: Long
        val asked: Int
        synchronized(this) {
            if (firstUnansweredAt == 0L) firstUnansweredAt = now
            askedSinceAnswer++
            // a fresh tunnel answers slowly at first; only a settled one can be stalled
            if (stallReported || armedAt == 0L || now - armedAt < STALL_MS) return
            stalledFor = now - firstUnansweredAt
            asked = askedSinceAnswer
            if (stalledFor < STALL_MS || asked < STALL_MIN_ASKED) return
            stallReported = true
        }
        onStall(stalledFor, asked)
    }

    /** Every answer, failed or not (BraveVPNService.onResponse). */
    fun onAnswered() {
        lastAnsweredAt = SystemClock.elapsedRealtime()
        val resumed: Boolean
        synchronized(this) {
            resumed = stallReported
            firstUnansweredAt = 0L
            askedSinceAnswer = 0
            stallReported = false
        }
        if (resumed) alarm("stall over: answers are coming back")
    }

    private fun onStall(stalledFor: Long, asked: Int) {
        alarm("STALL: $asked questions over ${stalledFor / 1000} s, not one answered")
        KojikiThreadDump.auto("dns stall: $asked questions over ${stalledFor / 1000} s, no answer")
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (now - lastActionAt < ACTION_COOLDOWN_MS) return
            lastActionAt = now
            lastRestartAt = now
        }
        if (!VpnController.hasTunnel()) return
        doRestart(
            "DNS questions went unanswered for ${stalledFor / 1000} s; the tunnel engine was " +
                "restarted to clear it."
        )
    }

    // called from NetLogTracker.processDnsLog for every DNS response; must stay cheap
    fun onDnsTransaction(t: Transaction) {
        // failure diagnostics via android.util.Log directly: the app's Logger is gated by the
        // in-app log level (observed fully silent in logcat), which would hide the telemetry
        // Off unless Diagnostics → "DNS watchdog" is on (KojikiDiag): then also kept in its trace.
        if (KojikiDiag.isOn(KojikiDiag.Group.DNS) &&
            t.status != Transaction.Status.COMPLETE && t.status != Transaction.Status.START
        ) {
            val line = "FAIL status=${t.status} cached=${t.isCached} q=${t.qName} " +
                "server=${t.serverName} msg=${t.msg.take(80)}"
            android.util.Log.e(TAG, "diag: $line")
            KojikiDiag.log(KojikiDiag.Group.DNS, line)
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
            // An HTTP error status counts once per name in the window. Quad9 answers 502 to every
            // `*.dbl.nordspam.com` spam-check lookup a mail app sends, and the app retries each
            // name several times: eight such answers for two names recycled the engine for
            // nothing (2026-11-07 10:21, 2026-11-08 11:50 and 17:03, felt as a wedge). Not
            // skipped outright: the July corpse wedge also surfaced as 502s, but across many names.
            Transaction.Status.TRANSPORT_ERROR ->
                if (!t.msg.contains("http-status:") || firstStatusFailFor(t.qName, now)) {
                    onUpstreamFailure(now)
                }
            Transaction.Status.SEND_FAIL,
            Transaction.Status.NO_RESPONSE,
            Transaction.Status.BAD_RESPONSE,
            Transaction.Status.INTERNAL_ERROR -> onUpstreamFailure(now)
            else -> {} // START / BAD_QUERY / CLIENT_ERROR: not upstream-health signals
        }
    }

    private fun firstStatusFailFor(name: String, now: Long): Boolean = synchronized(this) {
        statusFailNames.entries.removeAll { now - it.value > WINDOW_MS }
        if (statusFailNames.size >= WINDOW_CAP) return@synchronized true
        statusFailNames.put(name, now) == null
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
                alarm("window fails=$fails oks=$oks (trigger: fails>=$FAIL_THRESHOLD)")
            }
            // no success-ratio gate: the wedge is bursty — 10-30s bursts break apps even when the
            // 3-min window holds more successes than failures. A recycle is cheap and
            // cooldown-limited; only the failover needs (and has) stronger guards.
            if (fails < FAIL_THRESHOLD) return
            if (now - lastActionAt < ACTION_COOLDOWN_MS) return
            failTimes.clear()
            okTimes.clear()
            statusFailNames.clear()
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

    private fun doRestart(
        why: String = "Repeated DNS failures detected; the tunnel engine was restarted to clear " +
            "them. If the wedge returns shortly, DNS will fail over to Google DoH."
    ) {
        Logger.e(LOG_TAG_VPN, "$TAG: DoH failure streak; forcing full tunnel recycle")
        alarm("TRIGGER: forcing full tunnel recycle")
        VpnController.kojikiForceEngineRestart(
            "kojikiDnsWatchdog, at: ${SystemClock.elapsedRealtime()}"
        )
        notify("DNS wedged — tunnel restarted", why)
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
                    alarm("TRIGGER: wedge persists on fallback; recycling again")
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
                val back = rememberResolver()
                alarm("TRIGGER: failing over to fallback DoH")
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
                        "from '$prev' to '${doh.dohName}' and the tunnel was restarted." +
                        if (back != null) " Temporary: '$prev' is tried again in ${back / 60_000} min."
                        else " '$prev' is not a DoH resolver, so switch back by hand."
                )
                if (back != null) scheduleReturn()
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "$TAG: failover failed: ${e.message}", e)
            }
        }
    }

    // ---- the failover is temporary (2026-11-07) ----
    //
    // A failover used to be permanent: it rewrote the DNS setting and nothing ever changed it back,
    // so one bad half hour left the phone on Google for days, unnoticed (found 2026-11-07, when the
    // resolver 白い熊 had chosen — Quad9 DoH — was long gone). Now the chosen resolver is remembered
    // and tried again after RETURN_FIRST_MS; if the wedge sends it back to Google within
    // RETURN_RELAPSE_MS of a return, the next wait doubles, up to RETURN_MAX_MS. A DNS setting
    // changed by hand in the meantime wins: the plan is dropped. The plan lives in prefs, so a
    // process restart (an update) picks it up again in onTunnelUp.
    private const val RETURN_FIRST_MS = 15 * 60_000L
    private const val RETURN_MAX_MS = 8 * 60 * 60_000L
    private const val RETURN_RELAPSE_MS = 30 * 60_000L

    private const val PREFS = "kojiki_dns_failover"
    private const val K_URL = "url"           // the chosen resolver's DoH url; absent = no plan
    private const val K_NAME = "name"
    private const val K_DUE = "due"           // wall-clock ms when it is tried again
    private const val K_WAIT = "wait"         // the wait that produced K_DUE
    private const val K_RETURNED = "returned" // wall-clock ms of the last return

    private val appContext: Context by inject()
    private var returnJob: Job? = null

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Called just before switching to the fallback. Returns the wait before the chosen resolver is
    // tried again, or null when there is nothing to return to (the setting is not DoH).
    private suspend fun rememberResolver(): Long? {
        if (appConfig.getDnsType() != AppConfig.DnsType.DOH) return null
        val chosen = dohRepo.getConnectedDoH() ?: return null
        if (chosen.dohURL == FALLBACK_DOH_URL) return null
        val p = prefs()
        val now = System.currentTimeMillis()
        val returned = p.getLong(K_RETURNED, 0L)
        val relapse = returned != 0L && now - returned < RETURN_RELAPSE_MS
        val wait = if (relapse) (p.getLong(K_WAIT, RETURN_FIRST_MS) * 2).coerceAtMost(RETURN_MAX_MS)
                   else RETURN_FIRST_MS
        p.edit()
            .putString(K_URL, chosen.dohURL)
            .putString(K_NAME, chosen.dohName)
            .putLong(K_DUE, now + wait)
            .putLong(K_WAIT, wait)
            .apply()
        alarm("plan: back to '${chosen.dohName}' in ${wait / 60_000} min" +
            if (relapse) " (it failed again soon after the last return)" else "")
        return wait
    }

    private fun scheduleReturn() {
        val p = prefs()
        if (p.getString(K_URL, null) == null) return
        val left = (p.getLong(K_DUE, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
        synchronized(this) {
            returnJob?.cancel()
            returnJob = scope.launch {
                delay(left)
                returnToChosen()
            }
        }
    }

    private suspend fun returnToChosen() {
        try {
            val p = prefs()
            val url = p.getString(K_URL, null) ?: return
            val name = p.getString(K_NAME, url)
            val fallback = dohRepo.getAllDefaultDoHEndpoints().firstOrNull { it.dohURL == FALLBACK_DOH_URL }
            val onFallback = appConfig.getDnsType() == AppConfig.DnsType.DOH &&
                fallback != null && persistentState.connectedDnsName == fallback.dohName
            if (!onFallback) {
                // the setting was changed by hand since the failover: that choice wins
                p.edit().remove(K_URL).remove(K_NAME).remove(K_DUE).apply()
                alarm("plan dropped: the DNS setting was changed since the failover")
                return
            }
            val chosen = dohRepo.getAllDefaultDoHEndpoints().firstOrNull { it.dohURL == url }
            if (chosen == null) {
                p.edit().remove(K_URL).remove(K_NAME).remove(K_DUE).apply()
                alarm("plan dropped: '$name' is no longer in the DoH list")
                notify("DNS still on ${fallback?.dohName}",
                    "'$name' could not be found to switch back to; choose the resolver by hand.")
                return
            }
            alarm("RETURN: back from fallback to '${chosen.dohName}'")
            Logger.e(LOG_TAG_VPN, "$TAG: failover over; returning to '${chosen.dohName}'")
            dohRepo.removeConnectionStatus()
            chosen.isSelected = true
            dohRepo.update(chosen)
            runCatching { appConfig.handleDoHChanges(chosen) }
            p.edit().remove(K_URL).remove(K_NAME).remove(K_DUE)
                .putLong(K_RETURNED, System.currentTimeMillis()).apply()
            synchronized(this) { lastRestartAt = SystemClock.elapsedRealtime() }
            if (VpnController.hasTunnel()) {
                VpnController.kojikiForceEngineRestart(
                    "kojikiDnsWatchdog-return, at: ${SystemClock.elapsedRealtime()}"
                )
            }
            notify(
                "DNS back on ${chosen.dohName}",
                "The failover to ${fallback?.dohName} was temporary; '${chosen.dohName}' is in use " +
                    "again. If it wedges again soon, the next failover lasts longer."
            )
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG: return failed: ${e.message}", e)
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

    // a watchdog action: always to logcat (rare, and the one thing worth seeing there), and to the
    // DNS diagnostic trace while that is on
    private fun alarm(line: String) {
        android.util.Log.e(TAG, line)
        KojikiDiag.log(KojikiDiag.Group.DNS, line)
    }
}
