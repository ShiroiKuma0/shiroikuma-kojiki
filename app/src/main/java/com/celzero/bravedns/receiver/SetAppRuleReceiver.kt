/*
 * Copyright 2020 RethinkDNS and its authors
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

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_FIREWALL
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Fork (白い熊 考直): external automation hook to set a per-app firewall rule.
 *
 * Lets Tasker / adb flip an app's RethinkDNS rule (e.g. Termux between EXCLUDE — direct LAN — and
 * BYPASS_UNIVERSAL — rides the outbound relay) WITHOUT pausing the VPN, so other apps stay filtered.
 *
 * Action: [ACTION_SET_APP_RULE]. All extras are read AS STRINGS (Tasker and `am --es` send strings):
 *   - pkg    (required): package name, e.g. "com.termux"
 *   - rule   (optional): EXCLUDE | BYPASS_UNIVERSAL | NONE | ISOLATE | BYPASS_DNS_FIREWALL
 *   - conn   (optional): BOTH | UNMETERED | METERED | ALLOW (default: preserve the app's current value)
 *   - toggle (optional): "true" — if true and no `rule`, flip current EXCLUDE <-> BYPASS_UNIVERSAL
 *   - token  (required): shared secret; must match the stored token (Settings > Misc)
 *
 * Always target this receiver EXPLICITLY (Android 8+ won't deliver an implicit custom-action
 * broadcast to a manifest receiver):
 *   adb shell am broadcast \
 *     -n shiroikuma.kojiki/com.celzero.bravedns.receiver.SetAppRuleReceiver \
 *     -a com.celzero.bravedns.intent.action.SET_APP_RULE \
 *     --es pkg com.termux --es rule EXCLUDE --es token <TOKEN>
 *
 * Security trade-off: the receiver is exported with NO android:permission. A custom/signature
 * <permission> would block Tasker (it can't be granted to / declared by Tasker), so we instead
 * guard with a `token` shared secret stored in app prefs. Any app that knows the token AND the
 * explicit component can therefore trigger it — acceptable for a personal device; rotate the token
 * (Settings > Misc) to revoke.
 *
 * Cold start: the [FirewallManager] in-memory cache is loaded asynchronously from the DB. We call
 * [FirewallManager.load] before resolving the package, so this works whether or not the VPN is
 * already running — provided RethinkDNS has been launched at least once (so the app DB is populated).
 * Note: for the rule to actually change routing, the VPN must be active; the rule is still persisted
 * either way and applies the next time the VPN starts.
 */
class SetAppRuleReceiver : BroadcastReceiver(), KoinComponent {

    private val persistentState by inject<PersistentState>()

    companion object {
        const val ACTION_SET_APP_RULE = "com.celzero.bravedns.intent.action.SET_APP_RULE"
        private const val TAG = "SetAppRuleReceiver"

        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_RULE = "rule"
        private const val EXTRA_CONN = "conn"
        private const val EXTRA_TOGGLE = "toggle"
        private const val EXTRA_TOKEN = "token"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_APP_RULE) {
            Logger.w(LOG_TAG_FIREWALL, "$TAG: ignoring action=${intent.action}")
            return
        }
        // goAsync() keeps the receiver alive while the suspend work runs (well within the ~10s budget).
        val appCtx = context.applicationContext
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                handle(appCtx, intent)
            } catch (e: Exception) {
                Logger.e(LOG_TAG_FIREWALL, "$TAG: error: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handle(context: Context, intent: Intent) {
        // 1) token guard (shared secret in prefs). getOrCreate so the very first call never matches
        //    a blank stored token; the user must view the real token in Settings > Misc first.
        val provided = intent.getStringExtra(EXTRA_TOKEN)
        if (!persistentState.isAppRuleTokenValid(provided)) { // constant-time
            Logger.w(LOG_TAG_FIREWALL, "$TAG: token missing/mismatch; rejecting")
            return
        }

        // 2) resolve package -> uid; ensure the FirewallManager cache is loaded (cold start)
        val pkg = intent.getStringExtra(EXTRA_PKG)
        if (pkg.isNullOrBlank()) {
            Logger.w(LOG_TAG_FIREWALL, "$TAG: missing 'pkg'")
            toast(context, "SET_APP_RULE: missing 'pkg'")
            return
        }
        FirewallManager.load() // idempotent; populates appInfos from DB if not yet loaded
        val appInfo = FirewallManager.getAppInfoByPackage(pkg)
        if (appInfo == null) {
            Logger.w(LOG_TAG_FIREWALL, "$TAG: app not found: $pkg (launch RethinkDNS once?)")
            toast(context, "SET_APP_RULE: app not found: $pkg")
            return
        }
        val uid = appInfo.uid
        val currentStatus = FirewallManager.FirewallStatus.getStatus(appInfo.firewallStatus)

        // 3) determine target firewall status: explicit `rule` wins; else `toggle` flips EXCLUDE<->BYPASS
        val ruleStr = intent.getStringExtra(EXTRA_RULE)?.trim()?.uppercase()
        val toggle =
            intent.getStringExtra(EXTRA_TOGGLE)?.trim()?.equals("true", ignoreCase = true) == true
        val target: FirewallManager.FirewallStatus = when {
            !ruleStr.isNullOrEmpty() ->
                parseRule(ruleStr) ?: run {
                    Logger.w(LOG_TAG_FIREWALL, "$TAG: bad rule '$ruleStr'")
                    toast(context, "SET_APP_RULE: bad rule '$ruleStr'")
                    return
                }
            toggle ->
                if (currentStatus == FirewallManager.FirewallStatus.EXCLUDE) {
                    FirewallManager.FirewallStatus.BYPASS_UNIVERSAL
                } else {
                    FirewallManager.FirewallStatus.EXCLUDE
                }
            else -> {
                Logger.w(LOG_TAG_FIREWALL, "$TAG: neither 'rule' nor 'toggle:true' provided")
                toast(context, "SET_APP_RULE: provide 'rule' or 'toggle:true'")
                return
            }
        }

        // 4) connection status: explicit `conn` wins; else preserve the app's current value
        val connStr = intent.getStringExtra(EXTRA_CONN)?.trim()?.uppercase()
        val conn: FirewallManager.ConnectionStatus =
            if (!connStr.isNullOrEmpty()) {
                parseConn(connStr) ?: run {
                    Logger.w(LOG_TAG_FIREWALL, "$TAG: bad conn '$connStr'")
                    toast(context, "SET_APP_RULE: bad conn '$connStr'")
                    return
                }
            } else {
                FirewallManager.ConnectionStatus.getStatus(appInfo.connectionStatus)
            }

        // 5) apply via the same path the UI uses; BraveVPNService observes the app-list and
        //    auto-restarts the tunnel when the EXCLUDE set changes (so routing flips).
        Logger.i(
            LOG_TAG_FIREWALL,
            "$TAG: ${appInfo.appName}($uid) ${currentStatus.name} -> ${target.name}, conn=${conn.name}"
        )
        FirewallManager.updateFirewallStatus(uid, target, conn)

        // 6) toast the resulting mode
        toast(context, modeMessage(appInfo.appName.ifBlank { pkg }, target))
        Logger.i(LOG_TAG_FIREWALL, "$TAG: applied ${target.name} to ${appInfo.appName}($uid)")
    }

    private fun parseRule(s: String): FirewallManager.FirewallStatus? =
        when (s) {
            "EXCLUDE" -> FirewallManager.FirewallStatus.EXCLUDE
            "BYPASS_UNIVERSAL" -> FirewallManager.FirewallStatus.BYPASS_UNIVERSAL
            "NONE" -> FirewallManager.FirewallStatus.NONE
            "ISOLATE" -> FirewallManager.FirewallStatus.ISOLATE
            "BYPASS_DNS_FIREWALL" -> FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL
            else -> null
        }

    private fun parseConn(s: String): FirewallManager.ConnectionStatus? =
        when (s) {
            "BOTH" -> FirewallManager.ConnectionStatus.BOTH
            "UNMETERED" -> FirewallManager.ConnectionStatus.UNMETERED
            "METERED" -> FirewallManager.ConnectionStatus.METERED
            "ALLOW" -> FirewallManager.ConnectionStatus.ALLOW
            else -> null
        }

    private fun modeMessage(label: String, status: FirewallManager.FirewallStatus): String =
        when (status) {
            FirewallManager.FirewallStatus.EXCLUDE -> "$label → EXCLUDE (LAN in)"
            FirewallManager.FirewallStatus.BYPASS_UNIVERSAL -> "$label → BYPASS_UNIVERSAL (relay)"
            else -> "$label → ${status.name}"
        }

    private suspend fun toast(context: Context, msg: String) {
        withContext(Dispatchers.Main) { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    }
}
