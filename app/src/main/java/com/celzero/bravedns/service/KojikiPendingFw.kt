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
import com.celzero.bravedns.R
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
     *
     * Returns true when a rule was applied, so the caller's new-app notification can report the rule
     * that was actually restored instead of upstream's unconditional "blocked" (upstream only ever
     * blocks a new app, so its text never had to ask).
     */
    fun applyTo(ctx: Context, entry: AppInfo): Boolean {
        val sp = prefs(ctx)
        val v = sp.getString(entry.packageName, null) ?: return false
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
        return true
    }

    // ---- "saved rule restored" notification text -------------------------------------------------
    // Fork-only English kept in Kotlin, not in upstream's translated strings.xml (one less rebase
    // conflict); the rule words themselves come from upstream's own firewall_status_* strings, the
    // same ones the app list prints under each row.

    /** Whether [app] carries the plain "blocked on both" rule — the one upstream's ALLOW / KEEP
     *  BLOCKING notification actions were written for. */
    fun isPlainBlock(app: AppInfo): Boolean =
        app.firewallStatus == FirewallManager.FirewallStatus.NONE.id &&
            app.connectionStatus == FirewallManager.ConnectionStatus.BOTH.id

    /** Whether [app] carries the plain "allowed" rule (the mirror image of [isPlainBlock]). */
    fun isPlainAllow(app: AppInfo): Boolean =
        app.firewallStatus == FirewallManager.FirewallStatus.NONE.id &&
            app.connectionStatus == FirewallManager.ConnectionStatus.ALLOW.id

    /** The rule on [app] in words: "allowed", "blocked on metered (mobile) networks", "bypasses
     *  universal firewall rules", … plus "; excluded from proxies" when that flag is set. */
    fun describeRule(ctx: Context, app: AppInfo): String {
        val main =
            when (FirewallManager.FirewallStatus.getStatus(app.firewallStatus)) {
                FirewallManager.FirewallStatus.NONE ->
                    when (FirewallManager.ConnectionStatus.getStatus(app.connectionStatus)) {
                        FirewallManager.ConnectionStatus.ALLOW ->
                            ctx.getString(R.string.firewall_status_allow)
                        FirewallManager.ConnectionStatus.METERED ->
                            ctx.getString(R.string.firewall_status_block_metered)
                        FirewallManager.ConnectionStatus.UNMETERED ->
                            ctx.getString(R.string.firewall_status_block_unmetered)
                        FirewallManager.ConnectionStatus.BOTH ->
                            ctx.getString(R.string.firewall_status_blocked)
                    }
                FirewallManager.FirewallStatus.EXCLUDE ->
                    ctx.getString(R.string.firewall_status_excluded)
                FirewallManager.FirewallStatus.ISOLATE ->
                    ctx.getString(R.string.firewall_status_isolate)
                FirewallManager.FirewallStatus.BYPASS_UNIVERSAL ->
                    ctx.getString(R.string.firewall_status_whitelisted)
                FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL ->
                    ctx.getString(R.string.firewall_status_bypass_dns_firewall)
            }
        return if (app.isProxyExcluded) "$main; excluded from proxies" else main
    }

    /** Notification title for a restored rule (replaces upstream's "Action Required" — none is). */
    fun restoredTitle(): String = "Saved rule restored"

    /** Notification body for a restored rule: "<app> restored the saved rule for recently installed
     *  app, <name>: <rule>. Tap to view or modify." */
    fun restoredContent(ctx: Context, appName: String, app: AppInfo): String =
        "${ctx.getString(R.string.app_name)} restored the saved rule for recently installed app, " +
            "$appName: ${describeRule(ctx, app)}. Tap to view or modify."

    /** Action label that dismisses a restored "allowed" rule as-is (mirrors upstream's "Keep blocking"). */
    fun keepAllowingLabel(): String = "Keep allowing"

    /** Body for the >5-apps batch notification when some of the batch had saved rules. [defaultWord]
     *  is what the rest got — "blocked" or "allowed" — per the "block newly installed apps" setting. */
    fun restoredBulkContent(total: Int, restored: Int, defaultWord: String): String {
        val rest = total - restored
        val restText = if (rest > 0) ", $rest $defaultWord" else ""
        return "$total new apps installed: $restored with saved rules restored$restText. " +
            "Tap to view or modify."
    }
}
