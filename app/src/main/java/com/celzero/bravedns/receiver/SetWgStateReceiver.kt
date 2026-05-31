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

import Logger
import Logger.LOG_TAG_PROXY
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.celzero.bravedns.database.WgConfigFilesImmutable
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.WireguardManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Fork (白い熊 考直): external automation hook to enable/disable a WireGuard tunnel.
 *
 * Sibling of [SetAppRuleReceiver] — it turns a single WireGuard config on/off (it does NOT touch the
 * whole VPN, unlike the upstream VPN_START/VPN_STOP receiver). Useful for one-tap Tasker control of,
 * say, the outbound relay tunnel while everything else keeps running.
 *
 * Action: [ACTION_SET_WG_STATE]. All extras are read AS STRINGS (Tasker and `am --es` send strings):
 *   - wg     (required): WireGuard config to target — either its numeric id or its name (case-insensitive)
 *   - state  (optional): on | off | toggle  (default: toggle)
 *   - token  (required): shared secret; must match the token from Settings > Misc (same token that
 *                        guards SetAppRuleReceiver — one secret for all automations)
 *
 * Always target this receiver EXPLICITLY (Android 8+ won't deliver an implicit custom-action broadcast
 * to a manifest receiver):
 *   adb shell am broadcast \
 *     -n shiroikuma.kojiki/com.celzero.bravedns.receiver.SetWgStateReceiver \
 *     -a com.celzero.bravedns.intent.action.SET_WG_STATE \
 *     --es wg <name|id> --es state toggle --es token <TOKEN>
 *
 * Security trade-off: exported with NO android:permission for the same reason as [SetAppRuleReceiver]
 * (a custom/signature permission would block Tasker); guarded by the shared `token` instead.
 *
 * Cold start: [WireguardManager.load] is called first so the config mappings are available whether or
 * not the VPN is already running. As with the firewall rule, enabling/disabling is persisted either
 * way, but for it to affect live routing the VPN must be active.
 */
class SetWgStateReceiver : BroadcastReceiver(), KoinComponent {

    private val persistentState by inject<PersistentState>()

    companion object {
        const val ACTION_SET_WG_STATE = "com.celzero.bravedns.intent.action.SET_WG_STATE"
        private const val TAG = "SetWgStateReceiver"

        private const val EXTRA_WG = "wg"
        private const val EXTRA_STATE = "state"
        private const val EXTRA_TOKEN = "token"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_WG_STATE) {
            Logger.w(LOG_TAG_PROXY, "$TAG: ignoring action=${intent.action}")
            return
        }
        val appCtx = context.applicationContext
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                handle(appCtx, intent)
            } catch (e: Exception) {
                Logger.e(LOG_TAG_PROXY, "$TAG: error: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handle(context: Context, intent: Intent) {
        // 1) token guard (shared secret in prefs; same token as SetAppRuleReceiver)
        val provided = intent.getStringExtra(EXTRA_TOKEN)
        val expected = persistentState.getOrCreateAppRuleToken()
        if (provided.isNullOrEmpty() || provided != expected) {
            Logger.w(LOG_TAG_PROXY, "$TAG: token missing/mismatch; rejecting")
            return
        }

        // 2) ensure WG mappings are loaded (cold start); cheap no-op if already loaded
        WireguardManager.load(forceRefresh = false)

        // 3) resolve the target config by numeric id or by name
        val wgArg = intent.getStringExtra(EXTRA_WG)?.trim()
        if (wgArg.isNullOrEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG: missing 'wg' (id or name)")
            toast(context, "SET_WG_STATE: missing 'wg' (id or name)")
            return
        }
        val map = resolveConfig(wgArg)
        if (map == null) {
            Logger.w(LOG_TAG_PROXY, "$TAG: WG not found: $wgArg")
            toast(context, "SET_WG_STATE: WG not found: $wgArg")
            return
        }

        // 4) desired state: on | off | toggle (default toggle)
        val stateStr = intent.getStringExtra(EXTRA_STATE)?.trim()?.lowercase()
        val enable: Boolean = when (stateStr) {
            "on", "enable", "true", "1" -> true
            "off", "disable", "false", "0" -> false
            null, "", "toggle" -> !map.isActive
            else -> {
                Logger.w(LOG_TAG_PROXY, "$TAG: bad state '$stateStr'")
                toast(context, "SET_WG_STATE: bad state '$stateStr' (on|off|toggle)")
                return
            }
        }

        // 5) apply (no-op if already in the desired state)
        if (enable == map.isActive) {
            Logger.i(LOG_TAG_PROXY, "$TAG: ${map.name}(${map.id}) already ${onOff(enable)}")
            toast(context, "WireGuard ${map.name} already ${onOff(enable).uppercase()}")
            return
        }
        if (enable) {
            WireguardManager.enableConfig(map)
        } else {
            if (!WireguardManager.canDisableConfig(map)) {
                Logger.w(LOG_TAG_PROXY, "$TAG: cannot disable ${map.name}(${map.id}) (catch-all/hop)")
                toast(context, "WireGuard ${map.name}: can't disable (catch-all/hop)")
                return
            }
            WireguardManager.disableConfig(map)
        }
        Logger.i(LOG_TAG_PROXY, "$TAG: ${map.name}(${map.id}) -> ${onOff(enable)}")
        toast(context, "WireGuard ${map.name} → ${onOff(enable).uppercase()}")
    }

    /** Resolve a WG config: numeric id first, then case-insensitive name match. */
    private fun resolveConfig(arg: String): WgConfigFilesImmutable? {
        arg.toIntOrNull()?.let { id -> WireguardManager.getConfigFilesById(id)?.let { return it } }
        return WireguardManager.getAllMappings().firstOrNull { it.name.equals(arg, ignoreCase = true) }
    }

    private fun onOff(enable: Boolean) = if (enable) "on" else "off"

    private suspend fun toast(context: Context, msg: String) {
        withContext(Dispatchers.Main) { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    }
}
