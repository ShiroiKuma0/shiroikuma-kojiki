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
package com.celzero.bravedns.receiver

import Logger
import Logger.LOG_TAG_BACKUP_RESTORE
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.customui.KojikiExport
import com.celzero.bravedns.service.PersistentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fork (白い熊 考直): the sister-app **state-export automation contract** (保存復元) — the wire shape
 * every 白い熊 app exposes so one 自由作業盤 task can back them all up headlessly.
 *
 * - [ACTION_EXPORT_STATE]: run the category-ZIP export ([KojikiExport]) with no UI. Extras (all
 *   String): `token` (required — the app's one automation token, [PersistentState]), `path`
 *   (optional absolute directory; wins over the configured export directory), `items` (optional
 *   comma list of [KojikiExport.Cat] ids; absent/empty = everything), `progress_action` (optional —
 *   see below), plus the reply trio `reply_action` / `reply_package` / `reply_id`.
 * - [ACTION_LIST_CATEGORIES]: token-gated, instant category enumeration for the caller's picker.
 *   Our categories are flat (no sub-options), so each line is just `id<TAB>label`.
 *
 * **ONE ZIP per request, always** — every category is an entry inside the single archive, named
 * `shiroikuma-kojiki_<yyyy-MM-dd_HH-mm-ss>.zip` (identical to what the Export/Import panel writes,
 * and importable by it).
 *
 * Reply: a FRESH broadcast to `reply_package` with action `reply_action`, extras `reply_id` (echoed
 * verbatim) + `result` = `OK:<path>|<bytes>|<human size>|<n> categories` (EXPORT_STATE), `OK:` +
 * `id<TAB>label` lines (LIST_CATEGORIES), or `ERROR:<reason>`. Exactly one terminal reply, guarded
 * by an [AtomicBoolean]. NO binders (ResultReceiver/PendingIntent/Messenger) and NO reliance on the
 * ordered-broadcast result — EMUI severs both between third-party apps (verified on 白い熊's Mate XT,
 * 2026-07-23); the plain reply broadcast is the only channel that works.
 * [Intent.FLAG_INCLUDE_STOPPED_PACKAGES] so a backgrounded/stopped caller still hears us.
 *
 * Progress: while exporting, plain broadcasts to `reply_package` with action `progress_action` —
 * `reply_id`, `app` (display label), `text` (numbers-first, e.g. `区分 3/10 — Snoop tags`, never a
 * percentage) and structured `current`/`total` (long) + `unit` (String). Throttled to at most one
 * every 500 ms, with a final one always sent at completion.
 *
 * Security: exported with NO android:permission (the caller cannot hold one) — the master switch
 * plus the token are the gate. Both live in the 白い熊 考直 UI page under Export / Import.
 */
class StateExportReceiver : BroadcastReceiver(), KoinComponent {

    private val persistentState by inject<PersistentState>()

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val action = intent.action ?: return
        val token = intent.getStringExtra(EXTRA_TOKEN)
        val replyAction = intent.getStringExtra(EXTRA_REPLY_ACTION)?.trim().orEmpty()
        val replyPackage = intent.getStringExtra(EXTRA_REPLY_PACKAGE)?.trim().orEmpty()
        val replyId = intent.getStringExtra(EXTRA_REPLY_ID)?.trim().orEmpty()
        val progressAction = intent.getStringExtra(EXTRA_PROGRESS_ACTION)?.trim().orEmpty()
        val pathOverride = intent.getStringExtra(EXTRA_PATH)?.trim().orEmpty()
        val items = intent.getStringExtra(EXTRA_ITEMS)?.trim().orEmpty()

        val replied = AtomicBoolean(false)
        fun reply(result: String) {
            if (!replied.compareAndSet(false, true)) return
            // Log either way — the reply is invisible on this side, and this is what 白い熊 reads
            // back with `adb logcat` during acceptance testing.
            Logger.w(LOG_TAG_BACKUP_RESTORE, "$TAG: $action [$replyId] -> ${result.take(160)}")
            if (replyAction.isEmpty() || replyPackage.isEmpty()) return
            app.sendBroadcast(Intent(replyAction).apply {
                setPackage(replyPackage)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra(EXTRA_REPLY_ID, replyId)
                putExtra(EXTRA_RESULT, result)
            })
        }

        // Gate first — "disabled" and "bad token" are distinct on purpose (they debug differently).
        if (!persistentState.automationExportEnabled) {
            reply("ERROR:automation disabled")
            return
        }
        if (!persistentState.isAppRuleTokenValid(token)) {
            reply("ERROR:bad token")
            return
        }

        when (action) {
            ACTION_LIST_CATEGORIES -> {
                reply("OK:" + KojikiExport.Cat.entries.joinToString("\n") {
                    "${it.id}\t${app.getString(it.labelRes)}"
                })
            }

            ACTION_EXPORT_STATE -> {
                val cats: Set<KojikiExport.Cat> = if (items.isEmpty()) {
                    KojikiExport.Cat.all()
                } else {
                    val ids = items.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val resolved = ids.mapNotNull { KojikiExport.Cat.byId(it) }
                    if (resolved.size != ids.size) {
                        reply("ERROR:unknown category in items: $items")
                        return
                    }
                    resolved.toSet()
                }
                val appLabel = app.packageManager.getApplicationLabel(app.applicationInfo).toString()
                val fileName = KojikiExport.exportFileName()
                var lastProgressMs = 0L

                fun progress(done: Int, total: Int, catLabel: String) {
                    if (progressAction.isEmpty() || replyPackage.isEmpty()) return
                    val now = System.currentTimeMillis()
                    // At most one every 500 ms — but the final one always goes out.
                    if (done < total && now - lastProgressMs < PROGRESS_MIN_INTERVAL_MS) return
                    lastProgressMs = now
                    app.sendBroadcast(Intent(progressAction).apply {
                        setPackage(replyPackage)
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        putExtra(EXTRA_REPLY_ID, replyId)
                        putExtra(EXTRA_PROGRESS_APP, appLabel)
                        putExtra(EXTRA_PROGRESS_TEXT, "$PROGRESS_UNIT $done/$total — $catLabel")
                        putExtra(EXTRA_PROGRESS_CURRENT, done.toLong())
                        putExtra(EXTRA_PROGRESS_TOTAL, total.toLong())
                        putExtra(EXTRA_PROGRESS_UNIT, PROGRESS_UNIT)
                    })
                }

                // The export walks Room + writes ZIP entries — hold the broadcast open and work on IO.
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        // Directory precedence: `path` extra -> configured export directory -> error.
                        // Writing an arbitrary absolute path needs All-Files-Access; without it we may
                        // only fall back to the configured SAF directory (contract §1).
                        val useAbsolute = pathOverride.isNotEmpty() && hasAllFilesAccess()
                        val safDir = KojikiExport.exportDir(app)
                        if (pathOverride.isNotEmpty() && !useAbsolute && safDir == null) {
                            reply("ERROR:no-storage-access")
                            return@launch
                        }
                        val bytes: Long
                        val shownPath: String
                        if (useAbsolute) {
                            val dir = File(pathOverride)
                            dir.mkdirs()
                            if (!dir.isDirectory) error("not a directory: $pathOverride")
                            val file = File(dir, fileName)
                            file.outputStream().use { out ->
                                KojikiExport.export(app, cats, out, ::progress)
                            }
                            bytes = file.length()
                            shownPath = file.absolutePath
                        } else {
                            val dir = safDir
                                ?: error("no-directory") // no path extra and no configured directory
                            val doc = dir.createFile("application/zip", fileName)
                                ?: error("cannot create $fileName in the export directory")
                            app.contentResolver.openOutputStream(doc.uri)?.use { out ->
                                KojikiExport.export(app, cats, out, ::progress)
                            } ?: error("cannot open $fileName for writing")
                            bytes = doc.length()
                            shownPath = absolutePathOf(dir, doc) ?: "${dir.name}/${doc.name ?: fileName}"
                        }
                        reply("OK:$shownPath|$bytes|${humanSize(bytes)}|${cats.size} categories")
                    } catch (e: Exception) {
                        Logger.w(LOG_TAG_BACKUP_RESTORE, "$TAG: export failed: ${e.message}", e)
                        reply("ERROR:${e.message ?: e.javaClass.simpleName}")
                    } finally {
                        pending.finish()
                    }
                }
            }

            else -> reply("ERROR:unknown action: $action")
        }
    }

    /** All-Files-Access — required to write a caller-supplied absolute path on API 30+. */
    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /**
     * Best-effort real filesystem path for a file written through SAF, so the reply carries an
     * absolute path (the contract's preferred shape) rather than just a folder label. Only the
     * primary-storage tree can be resolved this way; anything else returns null.
     */
    private fun absolutePathOf(dir: DocumentFile, doc: DocumentFile): String? {
        val treeUri: Uri = dir.uri
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val docId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrNull() ?: return null
        if (!docId.startsWith("primary:")) return null // sd-card/usb volumes: no stable mount path
        val rel = docId.removePrefix("primary:").trim('/')
        val base = Environment.getExternalStorageDirectory().absolutePath
        val name = doc.name ?: return null
        return if (rel.isEmpty()) "$base/$name" else "$base/$rel/$name"
    }

    companion object {
        // Namespaced on the INSTALLED package id (shiroikuma.kojiki), per the contract — not on the
        // code namespace (com.celzero.bravedns) the fork's other intents use. Derived from
        // BuildConfig so these can never drift from the manifest's `${applicationId}` filters.
        val ACTION_EXPORT_STATE = "${BuildConfig.APPLICATION_ID}.action.EXPORT_STATE"
        val ACTION_LIST_CATEGORIES = "${BuildConfig.APPLICATION_ID}.action.LIST_CATEGORIES"

        private const val TAG = "StateExportReceiver"
        private const val PROGRESS_MIN_INTERVAL_MS = 500L
        private const val PROGRESS_UNIT = "区分" // categories — what this app counts
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

        // Contract extras — deliberately bare names, shared verbatim by every sister app.
        const val EXTRA_TOKEN = "token"
        const val EXTRA_PATH = "path"
        const val EXTRA_ITEMS = "items"
        const val EXTRA_PROGRESS_ACTION = "progress_action"
        const val EXTRA_REPLY_ACTION = "reply_action"
        const val EXTRA_REPLY_PACKAGE = "reply_package"
        const val EXTRA_REPLY_ID = "reply_id"
        const val EXTRA_RESULT = "result"
        const val EXTRA_PROGRESS_APP = "app"
        const val EXTRA_PROGRESS_TEXT = "text"
        const val EXTRA_PROGRESS_CURRENT = "current"
        const val EXTRA_PROGRESS_TOTAL = "total"
        const val EXTRA_PROGRESS_UNIT = "unit"

        fun humanSize(bytes: Long): String = when {
            bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
            bytes >= 1L shl 10 -> "%.1f KB".format(bytes / (1L shl 10).toDouble())
            else -> "$bytes B"
        }
    }
}
