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
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Fork (白い熊 考直): Settings → 白い熊 考直 UI → Diagnostics. Every fork diagnostic sits behind its
 * own switch, all OFF by default — off means nothing is formatted, written or timed, so a diagnostic
 * costs nothing until it is wanted (the same contract as 白い熊 Jami's DiagPrefs).
 *
 * Each group writes a capped, timestamped trace to `<external files>/diag/<file>`, the app's own data
 * directory, readable with adb (`/sdcard/Android/data/<app id>/files/diag/`) — EMUI drops an app's own
 * logcat lines below ERROR, so logcat is no place for them — and from the page itself, which also
 * summarizes, exports (to a directory picked with SAF) and purges what is stored. Until 2026-11-03 the
 * directory was `kojiki-diag/`; [init] moves anything left there in.
 */
object KojikiDiag {

    private const val PREFS = "kojiki_diag"
    private const val DIR = "diag"
    private const val OLD_DIR = "kojiki-diag"
    private const val KEY_EXPORT_TREE = "export_tree"
    private const val MAX_BYTES = 512 * 1024L

    /** A switchable group; [file] is its trace, null for a one-shot action. */
    enum class Group(val key: String, val file: String?) {
        LEAKS("leaks", "leaks.txt"),       // KojikiLeakWatch — destroyed screens still in memory
        HEAP_DUMP("heapdump", null),       // one-shot: the next leak found also writes heap.hprof
        VPN("vpn", "vpn.txt"),             // KojikiNetDiag — engine starts/stops + orphaned alg ips
        DNS("dns", "dns.txt"),             // KojikiDnsWatchdog — failed queries, recycles, failovers
        WG("wg", "wg.txt"),                // KojikiNetDiag — WireGuard handshake age / status / errors
        THREADS("threads", "threads.txt"), // KojikiThreadDump — every thread's stack on a stall
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
        val base = c.getExternalFilesDir(null)
        dir = base?.let { File(it, DIR) }
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for (g in Group.entries) on[g.ordinal] = p.getBoolean(g.key, false)
        // first on the trace thread, so no line is written before the old files are in
        if (base != null) io.execute { runCatching { migrate(File(base, OLD_DIR), File(base, DIR)) } }
    }

    // Idempotent: whatever is still in the old directory moves in; an empty old directory goes.
    // Writers need no reopening — every line opens its file afresh by path.
    private fun migrate(from: File, to: File) {
        val files = from.listFiles() ?: return
        to.mkdirs()
        for (f in files) {
            if (!f.isFile) continue
            runCatching { moveInto(f, File(to, f.name)) }
        }
        if (from.list().isNullOrEmpty()) from.delete()
    }

    // Rename, or copy and delete when the rename fails. A trace already present at the target keeps
    // its lines after the moved ones; a binary (heap.hprof) never merges — the moved one is kept aside.
    private fun moveInto(f: File, to: File) {
        if (!to.exists() && f.renameTo(to)) return
        val target = if (to.exists() && !to.name.endsWith(".txt")) File(to.parentFile, "old-" + to.name) else to
        if (target != to && !target.exists() && f.renameTo(target)) return
        val tmp = File(to.parentFile, target.name + ".moving")
        tmp.outputStream().use { out ->
            f.inputStream().use { it.copyTo(out) }
            if (target.exists()) target.inputStream().use { it.copyTo(out) }
        }
        check(tmp.renameTo(target)) { "rename $tmp" }
        check(f.delete()) { "delete $f" }
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

    // ---- what is stored, export, purge (2026-11-03, after 白い熊 地図's ChizuDiag) ----

    /** Every file in the diag directory, by name. */
    fun files(): List<File> =
        dir?.listFiles { f -> f.isFile && !f.name.endsWith(".moving") }?.sortedBy { it.name }.orEmpty()

    /** One stored file: its size and the span of its lines (an un-stamped file: its mtime). */
    class Stored(val file: File, val bytes: Long, val first: Long, val last: Long)

    class Summary(val stored: List<Stored>, val days: TreeMap<String, LongArray>) {
        val bytes: Long get() = stored.sumOf { it.bytes }
        val first: Long get() = stored.minOfOrNull { it.first } ?: 0L
        val last: Long get() = stored.maxOfOrNull { it.last } ?: 0L
    }

    /** Reads every stored file's stamps: the span per file and per day. Call off the main thread. */
    fun summarize(): Summary {
        val parse = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        val stored = ArrayList<Stored>()
        val days = TreeMap<String, LongArray>()
        fun day(t: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(t))
        fun note(t: Long) {
            val span = days.getOrPut(day(t)) { longArrayOf(t, t) }
            if (t < span[0]) span[0] = t
            if (t > span[1]) span[1] = t
        }
        for (f in files()) {
            var first = 0L
            var last = 0L
            if (f.name.endsWith(".txt")) {
                runCatching {
                    f.forEachLine { line ->
                        val t = stampOf(line, parse)
                        if (t > 0) {
                            if (first == 0L) first = t
                            last = t
                            note(t)
                        }
                    }
                }
            }
            if (first == 0L) {
                first = f.lastModified()
                last = first
                note(first)
            }
            stored.add(Stored(f, f.length(), first, last))
        }
        return Summary(stored, days)
    }

    private fun stampOf(line: String, parse: SimpleDateFormat): Long {
        if (line.length < 19 || line[4] != '-' || line[10] != ' ') return 0
        return runCatching { parse.parse(line.substring(0, 19))?.time ?: 0L }.getOrDefault(0L)
    }

    fun exportTree(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EXPORT_TREE, null)
            ?.let { Uri.parse(it) }

    fun setExportTree(context: Context, tree: Uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_EXPORT_TREE, tree.toString()).apply()
    }

    /**
     * Copies every stored file into a new `<yyyy-MM-dd_HH-mm-ss>_kojiki-diag` subdirectory of [tree];
     * returns its name and the count. Off the main thread; throws when nothing could be written.
     */
    fun export(context: Context, tree: Uri): Pair<String, Int> {
        val root = DocumentFile.fromTreeUri(context, tree)
        check(root != null && root.canWrite()) { "the chosen directory is not writable" }
        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date()) + "_kojiki-diag"
        val sub = root.createDirectory(name) ?: error("cannot create $name")
        val files = files()
        for (f in files) {
            // octet-stream, so the provider keeps the name's own extension
            val out = sub.createFile("application/octet-stream", f.name) ?: error("cannot create ${f.name}")
            context.contentResolver.openOutputStream(out.uri).use { os ->
                checkNotNull(os) { f.name }
                f.inputStream().use { it.copyTo(os) }
            }
        }
        return name to files.size
    }

    /** Deletes every stored file, on the trace thread after the lines already queued; [done] gets the count. */
    fun purge(done: (Int) -> Unit) {
        io.execute {
            val n = files().count { it.delete() }
            done(n)
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
