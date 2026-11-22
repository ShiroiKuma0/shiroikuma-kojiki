/*
 * Fork (白い熊 考直): Diagnostics → Engine log, and the translation probe.
 *
 * EMUI's logcat drops everything the app writes below ERROR, and the engine (firestack, Go) says
 * what it is doing at debug level — so what the engine does was invisible on the phone. While the
 * "Engine log" switch is on, the engine's console level is raised to verbose and its DNS-related
 * lines are kept in diag/engine.txt. Off (the default) nothing is raised, filtered or written.
 *
 * The translation probe exists for one fault (found 2026-11-22): under translation mode "auto" the
 * engine answers the AAAA query of a host without an IPv6 address by synthesizing one (DNS64), and
 * the first such lookup per host never answers. 0.5.8+033 avoids the path (PTMODENONE on a network
 * with native IPv4); the probe walks into it on purpose, for two minutes, with the engine log
 * captured and the engine's own goroutine stacks sampled, so the trace shows where it hangs.
 */
package com.celzero.bravedns.service

import android.os.SystemClock
import com.celzero.bravedns.customui.KojikiDiag
import com.celzero.bravedns.customui.KojikiDiag.Group
import com.celzero.bravedns.net.go.GoVpnAdapter
import com.celzero.firestack.intra.Intra
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object KojikiEngineDiag : KoinComponent {

    private const val PROBE_MS = 120_000L
    private const val PROBE_SAMPLE_MS = 2_000L
    private const val MAX_LINE = 700
    private const val VERBOSE = 1

    // engine lines worth keeping: name resolution, translation, the resolver's connections
    private val KEEP = arrayOf(
        "dns64", "natpt", "alg:", "ipmapper", "dns:", "dnsx", "doh", "dot:", "transport",
        "resolver", "onQuery", "cache", "wall:", "commondial", "blocked dot", "v4 fallback"
    )

    // goroutines worth keeping from a probe sample: anything inside the translation path
    private val KEEP_STACK = arrayOf("/x64.", "ipmapper", "lookupon", "queryAny", "dnsx.(*dnsgateway).q")

    private val persistentState by inject<PersistentState>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var probeUntil = 0L
    private var probeJob: Job? = null

    fun init() {
        KojikiDiag.addListener { g, _ -> if (g == Group.ENGINE) applyLogLevel() }
    }

    /** The translation probe is running: translation mode is "auto" whatever the network. */
    fun probing(): Boolean = probeUntil != 0L && SystemClock.elapsedRealtime() < probeUntil

    /** Engine lines are being captured (the switch, or a running probe). */
    fun capturing(): Boolean = KojikiDiag.isOn(Group.ENGINE) || probing()

    /** The console level the engine should run at: verbose while capturing, else [wanted]. */
    fun consoleLevel(wanted: Int): Int = if (capturing()) minOf(wanted, VERBOSE) else wanted

    /** Every line the engine hands the app (GoReportingHandler.log); must stay cheap when off. */
    fun onEngineLine(msg: String) {
        if (!capturing()) return
        if (KEEP.none { msg.contains(it, ignoreCase = true) }) return
        KojikiDiag.log(Group.ENGINE, if (msg.length > MAX_LINE) msg.take(MAX_LINE) + " …" else msg, force = true)
    }

    /** The engine's own goroutine stacks, all of them; written whether the switch is on or not. */
    fun dumpGoroutinesNow(reason: String) {
        scope.launch {
            val text = goroutines()
            KojikiDiag.log(
                Group.ENGINE,
                "=== engine goroutines: $reason — ${text.length} chars ===\n$text",
                force = true
            )
        }
    }

    /** Two minutes of translation mode "auto", engine log captured, translation stacks sampled. */
    fun startProbe() {
        synchronized(this) {
            probeUntil = SystemClock.elapsedRealtime() + PROBE_MS
            probeJob?.cancel()
            probeJob = scope.launch {
                KojikiDiag.log(Group.ENGINE, "— translation probe: mode 'auto' for ${PROBE_MS / 1000} s —", force = true)
                applyLogLevel()
                VpnController.kojikiRefreshTunMode()
                var last = ""
                while (probing()) {
                    delay(PROBE_SAMPLE_MS)
                    val kept = goroutines().split("\n\n")
                        .filter { block -> KEEP_STACK.any { block.contains(it) } }
                    // the same stacks again say only "still there": note it in one line
                    val shape = kept.joinToString("|") { it.lineSequence().drop(1).take(6).joinToString(";") }
                    if (kept.isEmpty()) continue
                    if (shape == last) {
                        KojikiDiag.log(Group.ENGINE, "probe: ${kept.size} goroutine(s) still in the translation path, unchanged", force = true)
                    } else {
                        last = shape
                        KojikiDiag.log(
                            Group.ENGINE,
                            "probe: ${kept.size} goroutine(s) in the translation path\n" + kept.joinToString("\n\n"),
                            force = true
                        )
                    }
                }
                probeUntil = 0L
                KojikiDiag.log(Group.ENGINE, "— translation probe over —", force = true)
                applyLogLevel()
                VpnController.kojikiRefreshTunMode()
            }
        }
    }

    private fun goroutines(): String =
        try {
            Intra.printStack(2)?.toString(Charsets.UTF_8).orEmpty()
        } catch (e: Throwable) {
            "goroutine dump failed: ${e.message}"
        }

    private fun applyLogLevel() {
        runCatching {
            GoVpnAdapter.setLogLevel(
                persistentState.goLoggerLevel.toInt(),
                includeFileTrace = persistentState.includeFileTrace
            )
        }
    }
}
