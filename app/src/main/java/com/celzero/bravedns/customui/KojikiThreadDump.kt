/*
 * Fork (白い熊 考直): thread dumps into the Diagnostics traces.
 *
 * EMUI's logcat keeps only error lines and a release build cannot be dumped from adb, so when kojiki
 * stalls (DNS unanswered all night, a STOP that takes a minute — 2026-11-07) nothing shows which
 * piece of work is holding it. This writes every JVM thread's stack to diag/threads.txt: on demand,
 * and automatically on a stall while Diagnostics → "Thread dumps" is on. Go threads are not visible
 * here, but a Kotlin callback blocked on one shows up as the thread waiting inside it.
 */
package com.celzero.bravedns.customui

import android.os.SystemClock

object KojikiThreadDump {

    // automatic dumps at most this often, so a stall that keeps re-triggering cannot fill the trace
    private const val AUTO_MIN_GAP_MS = 60_000L
    private const val MAX_FRAMES = 40

    @Volatile private var lastAutoAt = 0L

    /** An automatic dump, for [reason]: only while the switch is on, and rate-limited. */
    fun auto(reason: String) {
        if (!KojikiDiag.isOn(KojikiDiag.Group.THREADS)) return
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (lastAutoAt != 0L && now - lastAutoAt < AUTO_MIN_GAP_MS) return
            lastAutoAt = now
        }
        write(reason, force = false)
    }

    /** A dump asked for by hand: written whether the switch is on or not. */
    fun now(reason: String) = write(reason, force = true)

    private fun write(reason: String, force: Boolean) {
        val text = try {
            render(reason)
        } catch (e: Exception) {
            "thread dump failed ($reason): ${e.message}"
        }
        KojikiDiag.log(KojikiDiag.Group.THREADS, text, force)
    }

    private fun render(reason: String): String {
        val all = Thread.getAllStackTraces()
        // busy threads first: RUNNABLE and BLOCKED are what a stall is made of
        val order = listOf(Thread.State.BLOCKED, Thread.State.RUNNABLE)
        val threads = all.entries.sortedWith(
            compareBy({ order.indexOf(it.key.state).let { i -> if (i < 0) order.size else i } },
                { it.key.name })
        )
        val sb = StringBuilder()
        sb.append("=== thread dump: ").append(reason).append(" — ").append(all.size).append(" threads ===\n")
        for ((t, frames) in threads) {
            sb.append('"').append(t.name).append("\" ").append(t.state)
            if (t.isDaemon) sb.append(" daemon")
            sb.append('\n')
            for (f in frames.take(MAX_FRAMES)) sb.append("    at ").append(f).append('\n')
            if (frames.size > MAX_FRAMES) sb.append("    … ").append(frames.size - MAX_FRAMES).append(" more\n")
        }
        sb.append("=== end of dump ===")
        return sb.toString()
    }
}
