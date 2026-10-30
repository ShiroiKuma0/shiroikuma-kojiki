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
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.celzero.bravedns.R
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_APP_DB
import com.celzero.bravedns.util.Utilities.isAtleastT

/**
 * Fork (白い熊 考直): apps that are installed but dormant — **hidden**, **suspended** or
 * **disabled**, which is what 白い熊 応用管理 does to an app it "freezes".
 *
 * ### Why this exists
 * A hidden app (`pm hide`, `DevicePolicyManager.setApplicationHidden`) is left out of
 * `PackageManager.getInstalledPackages(flags)` unless `MATCH_UNINSTALLED_PACKAGES` is passed. Upstream's
 * refresh did not pass it, so every freeze looked like an uninstall: with "Remember uninstalled apps"
 * off (the default) the row was deleted together with its IP, domain and proxy rules, and the unfreeze
 * then announced the app as newly installed. [installedPackages] is the corrected query the refresh
 * now uses, and [appInfo] the matching single-package lookup.
 *
 * `MATCH_UNINSTALLED_PACKAGES` also returns packages uninstalled with `-k` (data kept). Those carry no
 * `FLAG_INSTALLED`, and are filtered out, so a real uninstall still removes the row.
 *
 * ### The snapshot
 * The apps view needs each row's state at bind time, and the "Hidden / frozen" filter needs the whole
 * set. Both read [snapshot], which [refresh] rebuilds from two package queries; the app list refreshes
 * it on every resume and the database refresh does so as a side effect of its own query.
 */
object KojikiFrozenApps {

    enum class State(val labelRes: Int) {
        HIDDEN(R.string.kojiki_frozen_hidden),
        SUSPENDED(R.string.kojiki_frozen_suspended),
        DISABLED(R.string.kojiki_frozen_disabled)
    }

    @Volatile private var snapshot: Map<String, State> = emptyMap()

    fun stateOf(packageName: String): State? = snapshot[packageName]

    fun packages(): Set<String> = snapshot.keys

    /** Every installed package, hidden ones included; also refreshes the [snapshot]. */
    fun installedPackages(pm: PackageManager): List<PackageInfo> {
        val all = query(pm, PackageManager.GET_META_DATA or PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .filter { (it.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_INSTALLED != 0 }
        val visible = query(pm, 0).map { it.packageName }.toHashSet()
        rebuild(all, visible)
        return all
    }

    /** Rebuild the [snapshot]; true when it changed. */
    fun refresh(context: Context): Boolean {
        val before = snapshot
        installedPackages(context.packageManager)
        return before != snapshot
    }

    /** [com.celzero.bravedns.util.Utilities.getApplicationInfo], but a hidden app resolves too. */
    fun appInfo(context: Context, packageName: String): ApplicationInfo? {
        val flags = PackageManager.GET_META_DATA or PackageManager.MATCH_UNINSTALLED_PACKAGES
        return try {
            val ai =
                if (isAtleastT()) {
                    context.packageManager.getApplicationInfo(
                        packageName, PackageManager.ApplicationInfoFlags.of(flags.toLong()))
                } else {
                    context.packageManager.getApplicationInfo(packageName, flags)
                }
            if (ai.flags and ApplicationInfo.FLAG_INSTALLED != 0) ai else null
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (e: Exception) {
            Logger.w(LOG_TAG_APP_DB, "frozen: app info for $packageName failed: ${e.message}")
            null
        }
    }

    /** The row marker — " · hidden", accented and bold, appended to the id line; empty when awake. */
    fun marker(context: Context, packageName: String): CharSequence {
        val state = stateOf(packageName) ?: return ""
        val sb = SpannableStringBuilder(" · " + context.getString(state.labelRes))
        sb.setSpan(
            ForegroundColorSpan(KojikiDialog.accentOf(context)),
            0,
            sb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun rebuild(all: List<PackageInfo>, visible: Set<String>) {
        val next = HashMap<String, State>()
        for (p in all) {
            val ai = p.applicationInfo ?: continue
            // one state per row, the most dormant first: a hidden app is usually suspended and
            // disabled as well, and "hidden" is the one that explains why it vanished
            val state =
                when {
                    p.packageName !in visible -> State.HIDDEN
                    ai.flags and ApplicationInfo.FLAG_SUSPENDED != 0 -> State.SUSPENDED
                    !ai.enabled -> State.DISABLED
                    else -> null
                }
            if (state != null) next[p.packageName] = state
        }
        snapshot = next
    }

    @Suppress("DEPRECATION")
    private fun query(pm: PackageManager, flags: Int): List<PackageInfo> =
        if (isAtleastT()) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getInstalledPackages(flags)
        }
}
