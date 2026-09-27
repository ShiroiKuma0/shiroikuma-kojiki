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
import android.content.SharedPreferences
import android.widget.LinearLayout
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.activity.AppListActivity
import com.celzero.bravedns.ui.activity.AppListActivity.FirewallFilter
import com.celzero.bravedns.ui.activity.AppListActivity.TopLevelFilter

/**
 * Fork (白い熊 考直): the apps view's quick-filter bar — the pill row under the search field.
 *
 * Upstream's row is a fixed, single-select `ChipGroup` holding exactly the eight firewall filters.
 * Everything else a user might filter by — installed / system / non-app, a category, one of the
 * fork's app groups, "has a note" — was reachable only through the filter sheet, two taps and a
 * scroll away. Here the row carries **any** of them, in **the user's own order**, with the ones they
 * never use hidden.
 *
 * ### Keys
 * A pill is one opaque string, so the order and the hidden set are just lists of them:
 * | key | family | selection |
 * | --- | --- | --- |
 * | `fw:<id>` | firewall filter | one at a time (re-tapping clears back to All) |
 * | `top:<id>` | installed / system / non-app | one at a time (re-tapping clears back to All) |
 * | `cat:<name>` | app category | many |
 * | `grp:<name>` | fork app group | many |
 * | `note` | has a note | toggle |
 *
 * Families matter because the bar is heterogeneous: tapping a firewall pill must clear the other
 * firewall pills but leave a group pill alone. [apply] is where that lives — one place, so the bar
 * and the filter sheet can never disagree about what a tap means.
 *
 * ### Order and hiding
 * [PREFS] keeps the visible order and the hidden set as CSV. A key absent from both is **new** —
 * a group created later, a category the device grew — and is appended in [visibleKeys] rather than
 * being invisible until the user goes looking for it. The default order is exactly upstream's eight
 * firewall pills, so the row looks untouched until it is deliberately changed.
 */
object KojikiQuickFilters {

    const val PREFS = "kojiki_quick_filters"
    private const val KEY_ORDER = "order"
    private const val KEY_HIDDEN = "hidden"
    // A control character, not a comma: a category or group name may contain anything the user
    // typed, and splitting on a character they could type would silently corrupt the list.
    private const val SEP = "\u0001"

    const val KEY_NOTE = "note"

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readList(context: Context, key: String): List<String> =
        sp(context).getString(key, null)?.split(SEP)?.filter { it.isNotBlank() } ?: emptyList()

    private fun writeList(context: Context, key: String, v: List<String>) {
        sp(context).edit().putString(key, v.joinToString(SEP)).apply()
    }

    /** Upstream's row, unchanged — what the bar shows until the user reorders or hides anything. */
    fun defaultOrder(): List<String> = FirewallFilter.entries.map { "fw:${it.id}" }

    fun order(context: Context): List<String> =
        readList(context, KEY_ORDER).ifEmpty { defaultOrder() }

    fun hidden(context: Context): Set<String> = readList(context, KEY_HIDDEN).toSet()

    fun setOrder(context: Context, keys: List<String>) = writeList(context, KEY_ORDER, keys)

    fun hide(context: Context, key: String) {
        writeList(context, KEY_HIDDEN, (hidden(context) + key).toList())
    }

    fun unhide(context: Context, key: String) {
        writeList(context, KEY_HIDDEN, (hidden(context) - key).toList())
    }

    /** Forget every customization — order and hidden set both go back to upstream's row. */
    fun reset(context: Context) {
        sp(context).edit().remove(KEY_ORDER).remove(KEY_HIDDEN).apply()
    }

    /**
     * Every key that could be a pill on this device, in family order: the firewall filters, the
     * top-level ones, "has a note", the fork's groups, then the categories the caller resolved.
     */
    fun allKeys(context: Context, categories: List<String>): List<String> {
        val keys = mutableListOf<String>()
        FirewallFilter.entries.forEach { keys.add("fw:${it.id}") }
        listOf(TopLevelFilter.INSTALLED, TopLevelFilter.SYSTEM, TopLevelFilter.NON_APP)
            .forEach { keys.add("top:${it.id}") }
        keys.add(KEY_NOTE)
        KojikiAppGroups.groups(context).forEach { keys.add("grp:$it") }
        categories.forEach { keys.add("cat:$it") }
        return keys
    }

    /**
     * The bar's contents: the stored order, minus anything hidden, plus any key that exists now but
     * was not around when the order was last written (a group made yesterday), appended at the end.
     */
    fun visibleKeys(context: Context, categories: List<String>): List<String> {
        val hidden = hidden(context)
        val stored = order(context)
        val known = stored.toSet()
        val fresh = allKeys(context, categories).filter { it !in known }
        return (stored + fresh).filter { it !in hidden && isLive(context, it, categories) }
    }

    /** Whether [key] still names something that exists — a group can be deleted, a category can go. */
    private fun isLive(context: Context, key: String, categories: List<String>): Boolean =
        when {
            key.startsWith("grp:") -> KojikiAppGroups.groups(context).contains(key.removePrefix("grp:"))
            key.startsWith("cat:") -> categories.isEmpty() || categories.contains(key.removePrefix("cat:"))
            else -> true
        }

    /** The pill's text. Firewall labels are rebuilt from the same strings upstream's row used. */
    fun labelOf(context: Context, key: String): String = when {
        key == KEY_NOTE -> context.getString(R.string.kojiki_filter_has_note)
        key.startsWith("grp:") -> key.removePrefix("grp:")
        key.startsWith("cat:") -> key.removePrefix("cat:")
        key.startsWith("top:") -> when (key.removePrefix("top:").toIntOrNull()) {
            TopLevelFilter.INSTALLED.id -> context.getString(R.string.fapps_filter_parent_installed)
            TopLevelFilter.SYSTEM.id -> context.getString(R.string.fapps_filter_parent_system)
            TopLevelFilter.NON_APP.id -> context.getString(R.string.kojiki_filter_non_app)
            else -> context.getString(R.string.lbl_all)
        }
        key.startsWith("fw:") -> when (key.removePrefix("fw:").toIntOrNull()) {
            FirewallFilter.ALL.id -> context.getString(R.string.lbl_all)
            FirewallFilter.ALLOWED.id -> context.getString(R.string.lbl_allowed)
            FirewallFilter.BLOCKED.id -> context.getString(R.string.lbl_blocked)
            FirewallFilter.BLOCKED_WIFI.id -> context.getString(
                R.string.two_argument_colon, context.getString(R.string.lbl_blocked),
                context.getString(R.string.firewall_rule_block_unmetered))
            FirewallFilter.BLOCKED_MOBILE_DATA.id -> context.getString(
                R.string.two_argument_colon, context.getString(R.string.lbl_blocked),
                context.getString(R.string.firewall_rule_block_metered))
            FirewallFilter.BYPASS.id ->
                context.getString(R.string.fapps_firewall_filter_bypass_universal)
            FirewallFilter.EXCLUDED.id ->
                context.getString(R.string.fapps_firewall_filter_excluded)
            FirewallFilter.LOCKDOWN.id ->
                context.getString(R.string.fapps_firewall_filter_isolate)
            else -> key
        }
        else -> key
    }

    /** Whether [key]'s filter is currently active, so the pill can draw itself checked. */
    fun isChecked(filters: AppListActivity.Filters?, key: String): Boolean {
        if (filters == null) return false
        return when {
            key == KEY_NOTE -> filters.notesOnly
            key.startsWith("grp:") -> filters.groupFilters.contains(key.removePrefix("grp:"))
            key.startsWith("cat:") -> filters.categoryFilters.contains(key.removePrefix("cat:"))
            key.startsWith("top:") -> filters.topLevelFilter.id == key.removePrefix("top:").toIntOrNull()
            key.startsWith("fw:") -> filters.firewallFilter.id == key.removePrefix("fw:").toIntOrNull()
            else -> false
        }
    }

    /**
     * Apply [key] to [filters], honouring its family: the two single-select families clear back to
     * "All" when their active pill is tapped again (upstream's row could not do that — it required a
     * selection), while the multi-select ones simply toggle.
     */
    fun apply(context: Context, filters: AppListActivity.Filters, key: String) {
        when {
            key == KEY_NOTE -> filters.notesOnly = !filters.notesOnly

            key.startsWith("grp:") -> {
                val name = key.removePrefix("grp:")
                val sel = filters.groupFilters.toMutableSet()
                if (!sel.remove(name)) sel.add(name)
                filters.setGroups(context, sel)
            }

            key.startsWith("cat:") -> {
                val name = key.removePrefix("cat:")
                if (!filters.categoryFilters.remove(name)) filters.categoryFilters.add(name)
            }

            key.startsWith("top:") -> {
                val id = key.removePrefix("top:").toIntOrNull() ?: return
                val next =
                    if (filters.topLevelFilter.id == id) TopLevelFilter.ALL
                    else TopLevelFilter.entries.firstOrNull { it.id == id } ?: TopLevelFilter.ALL
                filters.topLevelFilter = next
                // Non-app rows all sit in one category, so a category sub-filter could only ever
                // narrow them to nothing — drop it, exactly as the filter sheet does.
                if (next == TopLevelFilter.NON_APP) filters.categoryFilters.clear()
            }

            key.startsWith("fw:") -> {
                val id = key.removePrefix("fw:").toIntOrNull() ?: return
                filters.firewallFilter =
                    if (filters.firewallFilter.id == id) FirewallFilter.ALL
                    else FirewallFilter.filter(id)
            }
        }
    }

    /**
     * The hold-and-release menu for one pill: clear it if it is on, hide it, or open the manager.
     * Reordering is not in here — a pill is dragged by holding it and moving, which is the direct
     * way to say where it goes (see `KojikiQuickFilterAdapter.PillTouch`).
     */
    fun pillMenu(
        context: Context,
        key: String,
        checked: Boolean,
        onClear: () -> Unit,
        onHidden: () -> Unit,
        onManage: () -> Unit
    ) {
        KojikiDialog.show(
            context,
            labelOf(context, key),
            listOf(KojikiDialog.Action(context.getString(R.string.lbl_cancel)))
        ) { body, dialog ->
            if (checked) {
                body.addView(KojikiDialog.row(context, context.getString(R.string.kojiki_qf_clear)) {
                    onClear(); dialog.dismiss()
                })
            }
            body.addView(KojikiDialog.row(context, context.getString(R.string.kojiki_qf_hide)) {
                hide(context, key); onHidden(); dialog.dismiss()
            })
            body.addView(KojikiDialog.row(context, context.getString(R.string.kojiki_qf_manage)) {
                onManage(); dialog.dismiss()
            })
        }
    }

    /**
     * The manager: every possible pill with a checkbox, so hiding and restoring are one screen. The
     * order is left to the bar itself — dragging a pill is the direct way to say where it goes, and
     * a second ordering UI here would only be a worse copy of it.
     */
    fun manageDialog(
        context: Context,
        categories: List<String>,
        onChanged: () -> Unit
    ) {
        val keys = allKeys(context, categories)
        val hidden = hidden(context).toMutableSet()
        KojikiDialog.show(
            context,
            context.getString(R.string.kojiki_qf_manage),
            listOf(
                KojikiDialog.Action(context.getString(R.string.kojiki_qf_reset), leading = true) {
                    reset(context); onChanged()
                },
                KojikiDialog.Action(context.getString(R.string.lbl_cancel)),
                KojikiDialog.Action(context.getString(R.string.lbl_save)) {
                    writeList(context, KEY_HIDDEN, hidden.toList())
                    onChanged()
                })
        ) { body, _ ->
            body.addView(
                KojikiDialog.helper(context, context.getString(R.string.kojiki_qf_manage_hint)))
            for (k in keys) {
                val cb = KojikiDialog.checkbox(context, labelOf(context, k), k !in hidden)
                cb.setOnCheckedChangeListener { _, on -> if (on) hidden.remove(k) else hidden.add(k) }
                body.addView(
                    cb,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }
    }
}
