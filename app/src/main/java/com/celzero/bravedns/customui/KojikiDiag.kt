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
package com.celzero.bravedns.customui

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Fork (白い熊 考直): Settings → 白い熊 考直 UI → Diagnostics. Every fork diagnostic sits behind its
 * own switch, all OFF by default — off means nothing is formatted, written or timed, so a diagnostic
 * costs nothing until it is wanted (the same contract as 白い熊 Jami's DiagPrefs).
 *
 * Each group writes a capped, timestamped trace to `<external files>/kojiki-diag/<file>`, readable
 * with adb (`/sdcard/Android/data/<app id>/files/kojiki-diag/`) — EMUI drops an app's own logcat
 * lines below ERROR, so logcat is no place for them — and from the page itself.
 */
object KojikiDiag {

    private const val PREFS = "kojiki_diag"
    private const val DIR = "kojiki-diag"
    private const val MAX_BYTES = 512 * 1024L

    /** A switchable group; [file] is its trace, null for a one-shot action. */
    enum class Group(val key: String, val file: String?) {
        LEAKS("leaks", "leaks.txt"),       // KojikiLeakWatch — destroyed screens still in memory
        HEAP_DUMP("heapdump", null),       // one-shot: the next leak found also writes heap.hprof
        VPN("vpn", "vpn.txt"),             // KojikiNetDiag — engine starts/stops + orphaned alg ips
        DNS("dns", "dns.txt"),             // KojikiDnsWatchdog — failed queries, recycles, failovers
        WG("wg", "wg.txt"),                // KojikiNetDiag — WireGuard handshake age / status / errors
    }

    const val HPROF = "heap.hprof"

    private val on = BooleanArray(Group.entries.size)
    @Volatile private var dir: File? = null
    private val io = Executors.newSingleThreadExecutor()
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT) // io thread only
    private val listeners = CopyOnWriteArrayList<(Group, Boolean) -> Unit>()

    /** Load the switches. Call once, early in Application.onCreate. */
    fun init(context: Context) {
        val c = context.applicationContext
        dir = c.getExternalFilesDir(null)?.let { File(it, DIR) }
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for (g in Group.entries) on[g.ordinal] = p.getBoolean(g.key, false)
    }

    fun isOn(g: Group): Boolean = on[g.ordinal]

    fun set(context: Context, g: Group, value: Boolean) {
        if (on[g.ordinal] == value) return
        on[g.ordinal] = value
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(g.key, value).apply()
        if (g.file != null) log(g, if (value) "— switched on —" else "— switched off —", force = true)
        listeners.forEach { runCatching { it(g, value) } }
    }

    /** Components that start/stop something (a sampler) when their switch flips. */
    fun addListener(l: (Group, Boolean) -> Unit) {
        listeners.add(l)
    }

    fun directory(): File? = dir

    fun file(g: Group): File? = g.file?.let { f -> dir?.let { File(it, f) } }

    /** Append [line] to [g]'s trace, stamped now. A no-op while the group is off. */
    fun log(g: Group, line: String, force: Boolean = false) {
        if (!force && !isOn(g)) return
        val f = file(g) ?: return
        val now = System.currentTimeMillis()
        io.execute {
            try {
                f.parentFile?.mkdirs()
                if (f.length() > MAX_BYTES) trimHalf(f)
                f.appendText(stamp.format(Date(now)) + "  " + line + "\n")
            } catch (_: Exception) {
                // a diagnostic must never take the app down
            }
        }
    }

    /** Run [block] on the trace thread, after every line already queued. */
    fun onIo(block: () -> Unit) {
        io.execute { runCatching(block) }
    }

    /** The last [maxLines] lines of [g]'s trace, or "" when there is none. */
    fun tail(g: Group, maxLines: Int = 400): String {
        val f = file(g) ?: return ""
        return try {
            if (!f.exists()) "" else f.readLines().takeLast(maxLines).joinToString("\n")
        } catch (_: Exception) {
            ""
        }
    }

    fun clear(g: Group) {
        val f = file(g) ?: return
        io.execute { runCatching { f.delete() } }
    }

    // keep the newer half, cut at a line start, so the cap never grows the file unboundedly
    private fun trimHalf(f: File) {
        val text = f.readText()
        val from = text.indexOf('\n', text.length / 2).let { if (it < 0) text.length else it + 1 }
        f.writeText("… older lines dropped (cap ${MAX_BYTES / 1024} KiB) …\n" + text.substring(from))
    }
}
