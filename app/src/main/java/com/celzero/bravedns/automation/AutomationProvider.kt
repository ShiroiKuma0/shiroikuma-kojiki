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

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.preference.PreferenceManager
import com.celzero.bravedns.customui.KojikiExport
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_BACKUP_RESTORE
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fork (白い熊 考直): the **data door** of sister-app automation contract v2 (§2a) — export this
 * app's own state, and put it back, for a caller we can actually identify.
 *
 * ## Why a provider and not another action on [com.celzero.bravedns.receiver.StateExportReceiver]
 *
 * **A broadcast cannot tell you who sent it.** v1's answer to that was the shared secret. Take the
 * secret away — and it had to go, because a pasted 48-character token cannot survive the wipe this
 * whole feature exists to recover from — and a broadcast receiver has no idea who is asking, while
 * the caller supplies the destination the export is written into. "No idea who is asking" would
 * therefore mean any app on the phone can harvest every sister app's data. A provider gets the
 * caller's identity from the framework; see [AutomationCallers] for what is checked and why a
 * `shiroikuma.*` prefix would have been strictly WEAKER than the token it replaced.
 *
 * **And a list needs a synchronous answer.** 応用管理 draws a row per installed app before any
 * export exists; a broadcast round trip per app to fill a list is the wrong shape entirely.
 *
 * ## What does NOT happen here
 *
 * The payload. [call] validates, starts a foreground service and returns — tens of megabytes over
 * minutes inside a binder call would block the caller, report no progress, refuse cancellation and
 * die silently if this process were killed. The bytes go through a file descriptor the caller
 * opened, and the terminal answer comes back on the reply broadcast the family already proved on
 * EMUI.
 *
 * ## Why a descriptor and not a path
 *
 * Because a backup is not a stable directory while it is being assembled. 応用管理 writes into a
 * temporary path and renames on commit; it encrypts and checksums **per file it knows about**. A
 * file this app dropped into that directory itself would be renamed out from under it, would sit in
 * plaintext inside an otherwise encrypted backup, and would be unverified rather than
 * verified-and-failing. A descriptor is also a capability that **expires when it is closed**.
 *
 * Consequence worth having: the data door needs no `MANAGE_EXTERNAL_STORAGE`. That permission is
 * still declared, but now ONLY for §1's caller-supplied absolute `path` extra.
 *
 * ## `import` lives ONLY here
 *
 * It never gets a broadcast action. An import overwrites this app's firewall rules, WireGuard
 * bindings and DNS endpoints, and the §1 receiver is `exported="true"` with no permission — an
 * import there would let any app on the phone wipe this one.
 */
class AutomationProvider : ContentProvider() {

    // NOTHING on this path touches dependency injection — not the gate, not describe.
    //
    // A ContentProvider's onCreate() runs BEFORE Application.onCreate(), so a call() can land while
    // Koin is still initialising. That is not an edge case: it is the CLEAN-PHONE case, where this
    // provider call is what starts the process in the first place. Resolving PersistentState
    // through Koin there would answer a refusal and 応用管理 would skip this app from the restore.
    // So the gate is read straight off SharedPreferences (one implementation, in PersistentState's
    // companion — the instance method delegates to the same function), and describe() asks only the
    // PackageManager and an enum.
    override fun onCreate(): Boolean = true

    /**
     * Every method answers a [Bundle] carrying [KEY_RESULT] — `OK…` or `ERROR:…`, the same
     * vocabulary the §1 broadcast contract uses, so a caller has one grammar to parse rather than
     * two.
     *
     * **A refusal is returned, never thrown.** An exception across a binder reaches the caller as a
     * `RuntimeException` carrying our stack trace, which tells 白い熊 nothing and tells a
     * misbehaving caller rather more than it should.
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context?.applicationContext ?: return fail("ERROR:not ready")
        return try {
            // WHO, before WHAT. A caller we cannot identify gets the same answer whatever it asked.
            when (val verdict = AutomationCallers.verify(ctx, callingPackage)) {
                is AutomationCallers.Verdict.Refused -> return fail(verdict.why)
                AutomationCallers.Verdict.Allowed -> Unit
            }
            // Then this app's own switches. A token is IGNORED unless this app asks for one.
            // Read without DI, for the reason in the class comment above.
            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            PersistentState.refuseAutomation(prefs, extras?.getString(KEY_TOKEN))
                ?.let { return fail(it) }

            when (method) {
                METHOD_DESCRIBE -> ok(describe(ctx))
                METHOD_EXPORT -> start(ctx, extras, importing = false)
                METHOD_IMPORT -> start(ctx, extras, importing = true)
                METHOD_CANCEL -> {
                    AutomationJobs.cancel(extras?.getString(KEY_JOB_ID))
                    ok("OK:cancelled")
                }
                else -> fail("ERROR:unknown method: $method")
            }
        } catch (t: Throwable) {
            // Includes the cold-start race where the process is still binding and Koin is not up.
            Logger.w(LOG_TAG_BACKUP_RESTORE, "$TAG: $method failed: ${t.message}", t as? Exception)
            fail("ERROR:${t.message ?: t.javaClass.simpleName}")
        }
    }

    /**
     * What this app would export, answered without exporting anything.
     *
     * Returned from the call rather than written into the archive, deliberately: 応用管理 must draw
     * a row **before an export exists**, and at restore must judge compatibility **before**
     * streaming megabytes into an app that would reject them — which it cannot do if the header is
     * buried inside an encrypted archive.
     */
    private fun describe(ctx: Context): String {
        val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val cats = KojikiExport.Cat.defaults().sortedBy { it.ordinal }
        val header = JSONObject()
            .put("app_id", ctx.packageName)
            .put("version_code", @Suppress("DEPRECATION") pkg.versionCode)
            .put("version_name", pkg.versionName.orEmpty())
            .put("format", FORMAT)
            .put("min_format_readable", MIN_FORMAT_READABLE)
            // This app's import writes prefs and Room rows and needs no first run: a provider call()
            // starts the process, which is all the initialisation it wants. Rules for apps that are
            // not installed yet park in KojikiPendingFw and apply on install, which is exactly the
            // clean-phone case.
            .put("requires_launch_first", false)
            .put("contains", JSONArray(cats.map { ctx.getString(it.labelRes) }))
        return "OK:$header"
    }

    /**
     * Hand the descriptor to a foreground service and get out of the way.
     *
     * The descriptor is **duplicated** before it leaves this method. The one in [extras] belongs to
     * the binder transaction and is closed the moment `call()` returns; a service reading it
     * afterwards would find it shut. That is a bug you only see under load, so it is not left to
     * the service to remember.
     */
    private fun start(ctx: Context, extras: Bundle?, importing: Boolean): Bundle {
        @Suppress("DEPRECATION")
        val fd = extras?.getParcelable<ParcelFileDescriptor>(KEY_FD)
            ?: return fail("ERROR:no descriptor")
        val dup = runCatching { fd.dup() }.getOrNull() ?: return fail("ERROR:descriptor unusable")
        val jobId = AutomationJobs.begin()
        AutomationDataService.start(ctx, jobId, dup, importing, extras)
        return ok("OK:$jobId")
    }

    private fun ok(result: String) = Bundle().apply { putString(KEY_RESULT, result) }
    private fun fail(why: String) = Bundle().apply { putString(KEY_RESULT, why) }

    // A provider that is only ever call()ed still has to answer these. Refusing loudly beats
    // returning an empty cursor, which reads downstream as "there is no data" rather than "wrong
    // door".
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? =
        throw UnsupportedOperationException("automation is call() only")

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("automation is call() only")

    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int =
        throw UnsupportedOperationException("automation is call() only")

    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int =
        throw UnsupportedOperationException("automation is call() only")

    companion object {
        private const val TAG = "AutomationProvider"

        const val METHOD_DESCRIBE = "describe"
        const val METHOD_EXPORT = "export"
        const val METHOD_IMPORT = "import"
        const val METHOD_CANCEL = "cancel"

        const val KEY_RESULT = "result"
        const val KEY_FD = "fd"
        const val KEY_TOKEN = "token"
        const val KEY_JOB_ID = "job_id"
        const val KEY_ITEMS = "items"
        const val KEY_REPLY_ACTION = "reply_action"
        const val KEY_REPLY_PACKAGE = "reply_package"
        const val KEY_PROGRESS_ACTION = "progress_action"

        /**
         * This app's archive format — [KojikiExport.VERSION], so the two can never drift. Bumped
         * when an older build could no longer read what we write.
         */
        const val FORMAT = KojikiExport.VERSION

        /**
         * The oldest archive this build can still read.
         *
         * Version skew has a direction: old data into a newer app is normally fine, because an app
         * migrates its own storage; newer data into an older app is not. This field is what lets a
         * caller refuse the second case at discovery time, before anything is streamed.
         *
         * 1 rather than 2: [KojikiExport.import] reads a v1 archive's categories and simply
         * recovers nothing from the one category whose shape changed (blocklists became a portable
         * selection + stamp in v2), rather than failing. Everything else imports unchanged.
         */
        const val MIN_FORMAT_READABLE = 1
    }
}
