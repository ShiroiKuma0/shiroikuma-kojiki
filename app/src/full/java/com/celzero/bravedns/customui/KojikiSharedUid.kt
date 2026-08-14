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
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.LinearLayout
import android.widget.TextView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.service.FirewallManager

/**
 * Fork (白い熊 考直): shared uids, made visible.
 *
 * A firewall rule cannot name an app. Android hands the network stack a **uid**: `VpnService`'s
 * allow/disallow lists are uid ranges, the kernel's socket owner is a uid, and firestack's flow
 * callback is answered with a uid — so packages that share one (`android.uid.system`, uid 1000, is
 * the big one: on a Huawei device that is a dozen `com.huawei.*` services plus `com.huawei.systemserver`)
 * are a single principal that cannot be ruled apart. The app's own tables agree: `AppInfo` rules key
 * on uid, and `CustomDomain`/`CustomIp` key on `(uid, domain)` / `(uid, ip, port, protocol)`.
 *
 * Upstream already refuses to apply such a rule silently — it asks first, listing the other apps. What
 * it never does is say so **before** you commit to the decision: the app list shows those packages as
 * ordinary separate rows (identical byte counts and all, since accounting is per-uid too), so the row
 * implies a per-app decision the model cannot deliver. Hence:
 *
 * - [marker] — the row's id line carries "×N" when N packages share its uid;
 * - [confirm] — the pre-apply question, restated as *why* (the mechanism, not just the count), in the
 *   fork's own bordered dialog rather than a borderless Material alert;
 * - [spill] — the bulk toolbar's blind spot: a rule aimed at the filtered set also lands on every
 *   package that shares a uid with it, however far outside the filter it sits.
 *
 * The one axis that *does* discriminate below the uid is the destination — hence the dialog's hint to
 * rule domains/IPs instead when only one app under the uid should be let through.
 */
object KojikiSharedUid {

    /** Packages under [uid]. 1 = this app is alone there; >1 = every rule on it is a joint rule. */
    suspend fun count(uid: Int): Int = FirewallManager.getPackageNamesByUid(uid).size

    /** The app names under [uid], sorted; what [confirm] lists. */
    suspend fun names(uid: Int): List<String> =
        FirewallManager.getAppNamesByUid(uid).distinct().sortedBy { it.lowercase() }

    /**
     * The row marker — " ×11", accented and bold, appended to the id line. Empty below two packages,
     * so an ordinary app's row is untouched.
     */
    fun marker(context: Context, count: Int): CharSequence {
        if (count <= 1) return ""
        val text = " " + context.getString(R.string.kojiki_shared_uid_marker, count)
        val sb = SpannableStringBuilder(text)
        sb.setSpan(
            ForegroundColorSpan(KojikiDialog.accentOf(context)),
            0,
            sb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    /**
     * Ask before a rule reaches the whole uid. [names] is the sibling list (the caller has already
     * established it holds more than one entry), [proceedLabel] the affirmative button's text — the
     * rule being applied, which differs per call site.
     */
    fun confirm(
        context: Context,
        appName: String,
        uid: Int,
        names: List<String>,
        proceedLabel: CharSequence,
        onProceed: () -> Unit
    ) {
        KojikiDialog.show(
            context,
            context.getString(R.string.kojiki_shared_uid_title, uid.toString(), names.size),
            listOf(
                KojikiDialog.Action(context.getString(R.string.lbl_cancel), leading = true),
                KojikiDialog.Action(proceedLabel) { onProceed() })) { box, _ ->
            box.addView(
                body(
                    context,
                    context.getString(
                        R.string.kojiki_shared_uid_body, appName, uid.toString())))
            for (n in names) box.addView(bullet(context, n))
            box.addView(KojikiDialog.helper(context, context.getString(R.string.kojiki_shared_uid_hint)))
        }
    }

    /**
     * The apps a bulk rule reaches **past** its selection: everything sharing a uid with a selected
     * app but not itself selected. Empty when the selection is uid-closed, which is the common case.
     */
    suspend fun spill(selected: List<AppInfo>): List<String> {
        if (selected.isEmpty()) return emptyList()
        val uids = selected.mapTo(HashSet()) { it.uid }
        val picked = selected.mapTo(HashSet()) { it.packageName }
        return FirewallManager.getAppInfoSnapshot()
            .values
            .filter { uids.contains(it.uid) && !picked.contains(it.packageName) }
            .map { it.appName }
            .distinct()
            .sortedBy { it.lowercase() }
    }

    /** The bulk dialog's extra paragraph, or "" when the selection reaches nothing beyond itself. */
    fun bulkWarning(context: Context, extras: List<String>): String {
        if (extras.isEmpty()) return ""
        val shown = extras.take(MAX_NAMED)
        val list =
            if (extras.size > shown.size)
                context.getString(
                    R.string.kojiki_shared_uid_bulk_more,
                    shown.joinToString(", "),
                    extras.size - shown.size)
            else shown.joinToString(", ")
        return context.getString(R.string.kojiki_shared_uid_bulk, extras.size, list)
    }

    private const val MAX_NAMED = 6

    private fun body(context: Context, text: CharSequence): TextView {
        val d = context.resources.displayMetrics.density
        return TextView(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(KojikiDialog.textOf(context))
            setPadding(0, 0, 0, (10 * d).toInt())
        }
    }

    private fun bullet(context: Context, name: String): TextView {
        val d = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = name
            textSize = 15f
            setTextColor(KojikiDialog.accentOf(context))
            setPadding((8 * d).toInt(), (5 * d).toInt(), 0, (5 * d).toInt())
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }
}
