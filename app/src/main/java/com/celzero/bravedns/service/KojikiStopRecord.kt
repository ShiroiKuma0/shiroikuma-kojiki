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
package com.celzero.bravedns.service

import android.content.Context
import android.content.Intent

/**
 * Fork (白い熊 考直): the last time the VPN stopped, and whether 白い熊 asked for it.
 *
 * On 2026-11-09 at 17:08 the engine refused a link update after a network change and upstream's
 * restart path stopped the VPN ("noTunRestart2"). "VPN wanted" was cleared with it, so 自由作業盤's
 * guard read a deliberate stop and left it off for six hours. This keeps what a stop cannot say by
 * itself — who stopped it, why, and when — always (not behind a Diagnostics switch), so the guard's
 * STATUS answer and the notification can tell the two apart; and an unexpected stop is announced at
 * once with [ACTION_STOPPED] for anything listening.
 */
object KojikiStopRecord {

    /** Broadcast on every stop 白い熊 did not ask for; extras `reason`, `error`, `at_ms` (strings). */
    const val ACTION_STOPPED = "com.celzero.bravedns.intent.action.KOJIKI_VPN_STOPPED_UNEXPECTEDLY"

    private const val PREFS = "kojiki_stop"
    private const val K_AT = "at"
    private const val K_REASON = "reason"
    private const val K_ERROR = "error"
    private const val K_USER = "user"

    class Stop(val atMs: Long, val reason: String, val error: String, val user: Boolean)

    fun record(context: Context, reason: String, error: String?, user: Boolean) {
        val now = System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(K_AT, now)
            .putString(K_REASON, reason)
            .putString(K_ERROR, error.orEmpty())
            .putBoolean(K_USER, user)
            .apply()
        if (user) return
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_STOPPED)
                    .putExtra("reason", reason)
                    .putExtra("error", error.orEmpty())
                    .putExtra("at_ms", now.toString())
            )
        }
    }

    fun last(context: Context): Stop? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong(K_AT, 0L)
        if (at == 0L) return null
        return Stop(at, p.getString(K_REASON, "").orEmpty(), p.getString(K_ERROR, "").orEmpty(), p.getBoolean(K_USER, true))
    }
}
