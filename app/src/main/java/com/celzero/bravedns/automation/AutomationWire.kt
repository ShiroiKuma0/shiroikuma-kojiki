/*
 * Copyright 2025 RethinkDNS and its authors
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
package com.celzero.bravedns.automation

import android.content.Context
import android.content.Intent

/**
 * Fork (白い熊 考直): the ONE place an automation reply or progress broadcast is built.
 *
 * Both halves of the sister-app contract report the same way — §1's
 * [com.celzero.bravedns.receiver.StateExportReceiver] (a ZIP written to a path) and §2a's
 * [AutomationDataService] (a ZIP written into a caller-supplied descriptor). They are deliberately
 * NOT two senders: 自由作業盤 gives up on an app that goes quiet for two minutes, so the throttle
 * and the heartbeat are watchdog-facing behaviour, and two copies of watchdog-facing behaviour
 * drift (自由作業盤, 2026-09-04, after the v2 rollout found exactly that).
 *
 * ## Why the correlation id is two parameters and not one
 *
 * §1 correlates on `reply_id`, which the caller supplies and the app echoes back verbatim. §2a
 * correlates on the `job_id` the provider minted, and 応用管理 reads it under **both** names. So
 * §2a passes the same value twice and §1 passes `jobId = null`, which keeps §1's wire byte-identical
 * to the shape that has been proven on EMUI since the first sister app. Nothing in §1 moves.
 */
object AutomationWire {

    const val EXTRA_REPLY_ID = "reply_id"
    const val EXTRA_JOB_ID = "job_id"
    const val EXTRA_RESULT = "result"

    /** At most one progress broadcast every 500 ms — the final one always goes out regardless. */
    const val PROGRESS_MIN_INTERVAL_MS = 500L

    /**
     * The terminal reply: a FRESH broadcast, never a binder.
     *
     * No `ResultReceiver`, no `PendingIntent`, no `Messenger`, and never a reliance on the
     * ordered-broadcast result — EMUI severs both between third-party apps (verified on 白い熊's
     * Mate XT, 2026-07-23). [Intent.FLAG_INCLUDE_STOPPED_PACKAGES] matters too: without it a
     * backgrounded or freshly installed caller never hears us, and on a clean phone the caller may
     * not have been launched at all.
     */
    fun reply(
        context: Context,
        action: String?,
        pkg: String?,
        replyId: String,
        jobId: String?,
        result: String
    ) {
        if (action.isNullOrEmpty() || pkg.isNullOrEmpty()) return
        context.sendBroadcast(
            Intent(action).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra(EXTRA_REPLY_ID, replyId)
                jobId?.let { putExtra(EXTRA_JOB_ID, it) }
                putExtra(EXTRA_RESULT, result)
            }
        )
    }

    /**
     * A throttled progress sender for one run. Real numbers, never a percentage (白い熊's explicit
     * requirement) — and always the category `item`, because that is how the caller's panel knows
     * which row is running: it cannot work that out from `current`, which is whatever the app
     * happens to be counting at that moment.
     */
    class Progress(
        private val context: Context,
        private val action: String?,
        private val pkg: String?,
        private val replyId: String,
        private val jobId: String?,
        private val appLabel: String,
        private val unit: String
    ) {
        private var lastMs = 0L

        val live: Boolean get() = !action.isNullOrEmpty() && !pkg.isNullOrEmpty()

        /**
         * [current] is the POSITION of the category being written (1 while the first is written, and
         * a final call with `current == total`), matching the label in the text beside it — the
         * correction the family made on 2026-07-28 after every app's highlight was drawn one row
         * too far down.
         */
        fun send(current: Int, total: Int, itemId: String?, label: String, bytes: Long? = null) {
            if (!live) return
            val now = System.currentTimeMillis()
            if (current < total && now - lastMs < PROGRESS_MIN_INTERVAL_MS) return
            lastMs = now
            context.sendBroadcast(
                Intent(action).apply {
                    setPackage(pkg)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    putExtra(EXTRA_REPLY_ID, replyId)
                    jobId?.let { putExtra(EXTRA_JOB_ID, it) }
                    putExtra("app", appLabel)
                    itemId?.let { putExtra("item", it) }
                    putExtra("text", "$unit $current/$total — $label")
                    putExtra("current", current.toLong())
                    putExtra("total", total.toLong())
                    putExtra("unit", unit)
                    bytes?.let { putExtra("bytes", it) }
                }
            )
        }
    }

    /** Categories, in the order [com.celzero.bravedns.customui.KojikiExport.export] walks them. */
    fun orderedIds(cats: Set<com.celzero.bravedns.customui.KojikiExport.Cat>): List<String> =
        cats.sortedBy { it.ordinal }.map { it.id }
}
