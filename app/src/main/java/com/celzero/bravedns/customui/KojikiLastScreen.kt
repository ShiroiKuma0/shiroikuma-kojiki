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
package com.celzero.bravedns.customui

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

/**
 * Fork (白い熊 考直): remember the screen the user was last on and return to it when the app is
 * re-opened from the launcher.
 *
 * Why this is needed: the launcher entry is `AppLockActivity` (or `HomeScreenActivity` when the
 * app-lock alias is off), and both are `android:launchMode="singleTask"`. A launcher tap therefore
 * goes through `ActivityStarter#complyActivityFlags` → `performClearTop`, which finishes every
 * activity above the task root — so leaving from, say, the Apps list and coming back drops the user
 * on the home screen. Upstream doubles down on that with `android:finishOnTaskLaunch="true"` on
 * nearly every sub-activity.
 *
 * We deliberately do *not* fix this by relaxing the launch mode: keeping the task stack alive across
 * a launcher tap would also skip the biometric app-lock gate on re-entry. Instead the last
 * non-entry-point activity's intent is recorded (from [com.celzero.bravedns.ui.BaseActivity.onResume],
 * the app-wide chokepoint) and re-launched on top of the home screen once the home screen is created
 * from a launcher start — after the lock gate, not around it. Back from the restored screen lands on
 * the home screen as usual.
 */
object KojikiLastScreen {

    private const val TAG = "KojikiLastScreen"
    private const val PREFS_NAME = "kojiki_last_screen"
    private const val KEY_INTENT = "last_intent_uri"

    /** Set by `AppLockActivity` when the home-screen start came from a plain launcher tap. */
    const val EXTRA_RESTORE_LAST = "kojiki_restore_last_screen"

    /**
     * Entry points and transient/one-shot screens. These are never restored, and landing on one
     * clears the stored screen — sitting on the home screen *is* "nothing to restore".
     */
    private val SKIP =
        setOf(
            "com.celzero.bravedns.ui.HomeScreenActivity",
            "com.celzero.bravedns.ui.activity.AppLockActivity",
            "com.celzero.bravedns.ui.activity.WelcomeActivity",
            "com.celzero.bravedns.ui.activity.PauseActivity",
            "com.celzero.bravedns.ui.NotificationHandlerActivity",
            "com.celzero.bravedns.ui.TestDialogActivity",
            "com.celzero.bravedns.ui.activity.BubbleActivity",
            "com.celzero.bravedns.ui.activity.CheckoutActivity"
        )

    /** Last value written, so the common case (a resume on the screen we already recorded) is free. */
    @Volatile private var lastUri: String? = null

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Records [activity] as the screen to come back to. No-op (and a reset) for [SKIP] screens. */
    fun record(activity: Activity) {
        val cls = activity.javaClass.name
        if (SKIP.contains(cls)) {
            clear(activity)
            return
        }
        // rebuild a clean, explicit intent: component + extras only. Flags, action, categories and
        // data uris are dropped on purpose — a data uri's read permission does not survive the
        // round-trip, and stale flags would fight the fresh launch.
        val copy = Intent(activity, activity.javaClass)
        activity.intent?.extras?.let { copy.putExtras(it) }
        try {
            // toUri() keeps primitive/String extras and silently skips the rest, which is exactly
            // the subset that is safe to replay later
            val uri = copy.toUri(Intent.URI_INTENT_SCHEME)
            if (uri == lastUri) return
            prefs(activity).edit().putString(KEY_INTENT, uri).apply()
            lastUri = uri
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG err recording last screen: $cls", e)
        }
    }

    /**
     * Re-launches the recorded screen on top of [activity], if there is one. Consumed one-shot: the
     * restored screen records itself again as soon as it resumes, so a failed restore can never
     * loop.
     *
     * @return true when a screen was launched.
     */
    fun restoreIfAny(activity: Activity): Boolean {
        val p = prefs(activity)
        val uri = p.getString(KEY_INTENT, null) ?: return false
        p.edit().remove(KEY_INTENT).apply()
        // the restored screen must be free to record itself again on resume
        lastUri = null

        val intent =
            try {
                Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "$TAG err parsing last screen: $uri", e)
                return false
            }

        // only ever re-open our own activities, and never an entry point
        val cn = intent.component
        if (cn == null || cn.packageName != activity.packageName || SKIP.contains(cn.className)) {
            Logger.i(LOG_TAG_UI, "$TAG ignoring last screen: $cn")
            return false
        }

        intent.selector = null
        intent.setPackage(activity.packageName)
        intent.flags = Intent.FLAG_ACTIVITY_NO_ANIMATION
        return try {
            activity.startActivity(intent)
            Logger.i(LOG_TAG_UI, "$TAG restored last screen: ${cn.className}")
            true
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG err restoring last screen: ${cn.className}", e)
            false
        }
    }

    fun clear(ctx: Context) {
        if (lastUri == null && prefs(ctx).getString(KEY_INTENT, null) == null) return
        prefs(ctx).edit().remove(KEY_INTENT).apply()
        lastUri = null
    }
}
