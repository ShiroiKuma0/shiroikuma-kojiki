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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.customui.KojikiExport
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_BACKUP_RESTORE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fork (白い熊 考直): where an [AutomationProvider] data export or import actually runs.
 *
 * ## Why a foreground service and not the provider call
 *
 * The call returns in milliseconds; this can run for a while. Two hard reasons it cannot be done
 * anywhere cheaper:
 *
 * - **A binder call holds the caller.** 応用管理 is drawing a list; a multi-second synchronous call
 *   would freeze its UI, report no progress and refuse cancellation.
 * - **A backgrounded app writing for minutes is frozen mid-stream on this phone**, which yields a
 *   truncated archive underneath a success reply — the worst possible failure, because it is
 *   indistinguishable from a good backup until the day it is restored.
 *
 * ## The descriptor
 *
 * Already duplicated by [AutomationProvider] before it got here, because the original belongs to
 * the binder transaction and is closed the moment `call()` returns. This service owns the copy and
 * closes it in a `finally` — leaking one would hold the caller's file open indefinitely, and the
 * caller cannot checksum or encrypt a file that is still open.
 */
class AutomationDataService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val importing = intent?.getBooleanExtra(EXTRA_IMPORTING, false) ?: false

        // FIRST, before any early return. Once a caller has invoked startForegroundService() the
        // platform REQUIRES this call whatever the service then decides, and kills the process with
        // ForegroundServiceDidNotStartInTimeException if it never comes. Returning early on a
        // missing intent, an unknown job or a drained handover entry without going foreground would
        // therefore mean a caller RETRYING WITH A STALE JOB ID CRASHES THIS APP instead of being
        // quietly ignored. It must also happen within 5 s of the service starting.
        //
        // Still guarded: a refused promotion must not cost us the job either. The work then runs
        // without the notification, and with less protection from being frozen.
        runCatching { startForeground(NOTIFICATION_ID, notification(importing)) }
            .onFailure { Logger.w(LOG_TAG_BACKUP_RESTORE, "$TAG: startForeground refused: ${it.message}") }

        // Now the early returns are safe. Both are silent no-ops by design: a cancel or a retry that
        // names a job which already finished is the normal race, not an error.
        val jobId = intent?.getStringExtra(EXTRA_JOB) ?: return stop(startId)
        val fd = HANDOVER.remove(jobId) ?: return stop(startId)

        scope.launch {
            try {
                runJob(applicationContext, jobId, fd, importing, intent.extras)
            } finally {
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(importing: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(
                if (importing) "自動化データを戻しています" else "自動化データを書き出しています"
            )
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun stop(startId: Int): Int {
        // We went foreground unconditionally above, so we must come back out of it here too.
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    /**
     * Bytes written so far, as a named class rather than an anonymous object — see the note on
     * [runJob]'s reply lambda for the AGP lint crash that shape causes.
     */
    private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
        var written = 0L
            private set

        override fun write(b: Int) { out.write(b); written++ }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len); written += len
        }

        override fun flush() = out.flush()
    }

    companion object {
        private const val TAG = "AutomationDataService"
        private const val CHANNEL = "kojiki_automation_data"
        private const val CHANNEL_NAME = "自動化データ"
        private const val NOTIFICATION_ID = 9714
        private const val EXTRA_JOB = "job"
        private const val EXTRA_IMPORTING = "importing"
        private const val PROGRESS_UNIT = "区分" // categories — what this app counts

        /**
         * The most an import may be. This app's own archives are hundreds of KB; the descriptor,
         * though, comes from the caller, so a bound is what stops a wrong or hostile one taking the
         * process out with an OutOfMemoryError instead of an answer.
         */
        private const val MAX_IMPORT_BYTES = 64 shl 20 // 64 MB

        /**
         * The descriptor's way across, because an Intent is the wrong vehicle for one.
         *
         * A [ParcelFileDescriptor] in an Intent extra is duplicated by the system on delivery and
         * the copy's lifetime stops being ours to reason about. Handing it through a map keyed by
         * the job id keeps exactly one open descriptor with exactly one owner — whoever runs the
         * job, which closes it in a `finally`.
         */
        private val HANDOVER = ConcurrentHashMap<String, ParcelFileDescriptor>()

        /**
         * A last-resort scope for the case below. Not the happy path and never the first choice.
         */
        private val FALLBACK = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun start(
            context: Context,
            jobId: String,
            fd: ParcelFileDescriptor,
            importing: Boolean,
            extras: Bundle?
        ) {
            HANDOVER[jobId] = fd
            val intent = Intent(context, AutomationDataService::class.java).apply {
                putExtra(EXTRA_JOB, jobId)
                putExtra(EXTRA_IMPORTING, importing)
                putExtra(AutomationProvider.KEY_ITEMS, extras?.getString(AutomationProvider.KEY_ITEMS))
                putExtra(
                    AutomationProvider.KEY_REPLY_ACTION,
                    extras?.getString(AutomationProvider.KEY_REPLY_ACTION)
                )
                putExtra(
                    AutomationProvider.KEY_REPLY_PACKAGE,
                    extras?.getString(AutomationProvider.KEY_REPLY_PACKAGE)
                )
                putExtra(
                    AutomationProvider.KEY_PROGRESS_ACTION,
                    extras?.getString(AutomationProvider.KEY_PROGRESS_ACTION)
                )
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                // A provider call() is ALWAYS a background start, and API 31+ can refuse one with
                // ForegroundServiceStartNotAllowedException unless the app is exempt. This app
                // usually is — its VpnService is bound by the system — but "the VPN happens to be
                // off" must not mean "this app cannot be backed up", so the job runs in-process
                // instead: no notification and no protection from being frozen.
                //
                // That is safe HERE specifically because this app's export is a dozen small JSON
                // entries, and because the reply is only sent AFTER the archive is closed: a
                // process frozen mid-write never reports success, so the caller times out and shows
                // a failed row rather than keeping a truncated archive it believes in.
                //
                // Whatever happens, the caller's descriptor must not leak — it holds their file
                // open, and they cannot checksum or encrypt a file that is still open.
                Logger.w(LOG_TAG_BACKUP_RESTORE, "$TAG: FGS refused (${t.message}); running inline")
                val handed = HANDOVER.remove(jobId) ?: return // the service won the race after all
                try {
                    FALLBACK.launch {
                        runJob(context.applicationContext, jobId, handed, importing, intent.extras)
                    }
                } catch (t2: Throwable) {
                    // Could not even schedule it: close the descriptor, drop the job, and tell the
                    // caller — never leave them holding an open file and waiting for a reply.
                    runCatching { handed.close() }
                    AutomationJobs.finish(jobId)
                    AutomationWire.reply(
                        context.applicationContext,
                        extras?.getString(AutomationProvider.KEY_REPLY_ACTION),
                        extras?.getString(AutomationProvider.KEY_REPLY_PACKAGE),
                        jobId, jobId,
                        "ERROR:cannot start: ${t2.javaClass.simpleName}"
                    )
                }
            }
        }

        /**
         * The job itself, shared by the service and the inline fallback so there is exactly one
         * implementation of "export or import, then reply once".
         */
        private suspend fun runJob(
            context: Context,
            jobId: String,
            fd: ParcelFileDescriptor,
            importing: Boolean,
            extras: Bundle?
        ) {
            val replyAction = extras?.getString(AutomationProvider.KEY_REPLY_ACTION)
            val replyPackage = extras?.getString(AutomationProvider.KEY_REPLY_PACKAGE)
            val progressAction = extras?.getString(AutomationProvider.KEY_PROGRESS_ACTION)
            val items = extras?.getString(AutomationProvider.KEY_ITEMS)

            val replied = AtomicBoolean(false)
            // A `val` lambda, not a local `fun`. A local function alongside an anonymous object that
            // captures a local `var` crashes AGP's lintVitalAnalyze with "FirDeclaration was not
            // found for class KtProperty, fir is null" — AFTER Kotlin has compiled cleanly, so it
            // surfaces minutes into the build and does not look like a source problem at all.
            // (Found across the family, 2026-09-04.) The counter is a named class for the same
            // reason. Neither shape is needed here, so neither is used.
            val reply: (String) -> Unit = { result ->
                // Exactly one terminal answer per job, whatever path got here — a synchronous
                // failure and an asynchronous success must never both fire. The same guard the
                // broadcast contract has carried since the first sister app.
                if (replied.compareAndSet(false, true)) {
                    AutomationJobs.finish(jobId)
                    Logger.w(LOG_TAG_BACKUP_RESTORE, "$TAG: [$jobId] -> ${result.take(160)}")
                    // The job id rides under BOTH `job_id` and `reply_id`: 応用管理 reads it either
                    // way, and one sender for both halves of the contract is what keeps the caller's
                    // liveness watchdog from drifting between them.
                    AutomationWire.reply(context, replyAction, replyPackage, jobId, jobId, result)
                }
            }

            try {
                fd.use { open ->
                    if (importing) runImport(context, open, reply)
                    else runExport(context, jobId, open, items, progressAction, replyPackage, reply)
                }
            } catch (c: KojikiExport.ExportCancelled) {
                reply("ERROR:cancelled")
            } catch (t: Throwable) {
                Logger.w(LOG_TAG_BACKUP_RESTORE, "$TAG: [$jobId] failed: ${t.message}", t as? Exception)
                reply("ERROR:${t.message ?: t.javaClass.simpleName}")
            } finally {
                // Belt and braces: a path that somehow returned without replying must not leave the
                // caller waiting for an answer that can never come.
                reply("ERROR:ended without a result")
            }
        }

        private suspend fun runExport(
            context: Context,
            jobId: String,
            fd: ParcelFileDescriptor,
            items: String?,
            progressAction: String?,
            replyPackage: String?,
            reply: (String) -> Unit
        ) {
            val cats = resolve(items)
                ?: run { reply("ERROR:unknown category in items: $items"); return }
            // Declaration order — exactly the order KojikiExport.export walks them in, which is what
            // makes `done` an index into this list and therefore lets us name the category id.
            val ordered = cats.sortedBy { it.ordinal }
            val appLabel =
                context.packageManager.getApplicationLabel(context.applicationInfo).toString()
            // §3 applies to the data door exactly as to §1 — an app that reports nothing for two
            // minutes is presumed dead and its slot is failed. Same sender as the receiver's.
            val sender = AutomationWire.Progress(
                context, progressAction, replyPackage, jobId, jobId, appLabel, PROGRESS_UNIT
            )
            var written = 0L
            ParcelFileDescriptor.AutoCloseOutputStream(fd).use { out ->
                // Counted as it goes rather than stat'ed afterwards: the caller owns the file and we
                // may not be able to see it at all — it can be an anonymous pipe or a descriptor
                // into a directory this app cannot list.
                val counting = CountingOutputStream(out)
                KojikiExport.export(
                    context = context,
                    cats = cats,
                    out = counting,
                    // WHICH row is running. The panel cannot work that out from `current`, which is
                    // whatever we happen to be counting at that moment.
                    onProgress = { done, total, catLabel ->
                        sender.send(
                            done, total, ordered.getOrNull(done - 1)?.id, catLabel, counting.written
                        )
                    },
                    isCancelled = { AutomationJobs.isCancelled(jobId) }
                )
                written = counting.written
            }
            if (AutomationJobs.isCancelled(jobId)) reply("ERROR:cancelled")
            else reply("OK:$written|${cats.size} categories")
        }

        /** Read the whole archive before touching anything (see the note inside). */
        private suspend fun runImport(
            context: Context,
            fd: ParcelFileDescriptor,
            reply: (String) -> Unit
        ) {
            // KojikiExport.import works on the whole archive, and that is the right shape here for
            // a reason beyond convenience: a partial read that failed halfway would import half an
            // archive, and a half-restored firewall is worse than one that refused. This app's
            // archives are settings, rules and a few fonts — hundreds of KB — so they are read into
            // memory rather than spooled. The cap is the guard that makes that safe: a descriptor
            // is supplied by the caller, so its size is not ours to assume.
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                val capped = ByteArray(MAX_IMPORT_BYTES + 1).let { buf ->
                    var n = 0
                    while (n < buf.size) {
                        val r = input.read(buf, n, buf.size - n)
                        if (r < 0) break
                        n += r
                    }
                    buf.copyOf(n)
                }
                capped
            }
            if (bytes.size > MAX_IMPORT_BYTES) {
                reply("ERROR:archive too large (over ${MAX_IMPORT_BYTES / (1 shl 20)} MB)")
                return
            }
            if (bytes.isEmpty()) { reply("ERROR:empty archive"); return }
            // Every category the archive actually carries, not every category we know about: asking
            // for one the archive lacks is how a restore ends up reporting success over nothing.
            val present = KojikiExport.categoriesIn(bytes)
            if (present.isEmpty()) { reply("ERROR:archive carries no categories"); return }
            val summary = KojikiExport.import(context, bytes, present)
            // FLUSH BEFORE REPLYING. 応用管理 force-stops this app with SIGKILL the instant it sees
            // this OK — deliberately, and correctly, because a process shut down normally writes its
            // cached SharedPreferences back out and would silently undo the import. The cost of that
            // guarantee is that anything still queued here dies unwritten, so everything must be on
            // disk before the answer goes out, not after.
            KojikiExport.flushPrefs(context)
            reply("OK:$summary")
        }

        /** Absent/blank `items` = this app's DEFAULT set, which is not the same as everything. */
        private fun resolve(items: String?): Set<KojikiExport.Cat>? {
            if (items.isNullOrBlank()) return KojikiExport.Cat.defaults()
            val wanted = items.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val found = wanted.mapNotNull { KojikiExport.Cat.byId(it) }
            return if (found.size == wanted.size) found.toSet() else null
        }
    }
}
