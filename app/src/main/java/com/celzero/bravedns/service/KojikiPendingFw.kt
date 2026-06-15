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
package com.celzero.bravedns.service

import android.content.Context
import com.celzero.bravedns.database.AppInfo

/**
 * Fork (白い熊 考直): per-app firewall rules imported (by package name) for apps that are NOT installed
 * yet. The export keys per-app rules on the package name — uid is install-specific and useless across
 * devices/reinstalls — so a rule for an app missing at import time is parked here and applied the moment
 * that package is installed (from [com.celzero.bravedns.database.RefreshDatabase.insertApp], which builds
 * the new app's AppInfo). Applied-once then removed, so later manual changes aren't clobbered.
 *
 * Lives in `main` so both the `full` importer ([com.celzero.bravedns.customui.KojikiExport]) and the
 * `main` RefreshDatabase can reach it.
 */
object KojikiPendingFw {

    private const val PREFS = "kojiki_pending_fw"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Park a rule for [pkg] until it is installed. Encoded "fw,conn,screenOff,bg,proxyExcl". */
    fun put(
        ctx: Context,
        pkg: String,
        firewallStatus: Int,
        connectionStatus: Int,
        screenOffAllowed: Boolean,
        backgroundAllowed: Boolean,
        isProxyExcluded: Boolean
    ) {
        if (pkg.isBlank()) return
        prefs(ctx).edit()
            .putString(pkg, "$firewallStatus,$connectionStatus,$screenOffAllowed,$backgroundAllowed,$isProxyExcluded")
            .apply()
    }

    /**
     * If a parked rule exists for [entry]'s package, copy it onto [entry] (so it persists with the row
     * the caller is about to insert) and drop the parked entry. No-op otherwise.
     */
    fun applyTo(ctx: Context, entry: AppInfo) {
        val sp = prefs(ctx)
        val v = sp.getString(entry.packageName, null) ?: return
        val p = v.split(",")
        if (p.size >= 2) {
            p[0].toIntOrNull()?.let { entry.firewallStatus = it }
            p[1].toIntOrNull()?.let { entry.connectionStatus = it }
        }
        if (p.size >= 5) {
            entry.screenOffAllowed = p[2].toBoolean()
            entry.backgroundAllowed = p[3].toBoolean()
            entry.isProxyExcluded = p[4].toBoolean()
        }
        sp.edit().remove(entry.packageName).apply()
    }
}
