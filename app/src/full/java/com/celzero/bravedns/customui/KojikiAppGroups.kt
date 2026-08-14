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
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.UiThread
import com.celzero.bravedns.R
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities

/**
 * Fork (白い熊 考直): named app groups (profiles) for the apps view — 白い熊 応用管理's profiles, in
 * kojiki's terms. A group is just an ordered name plus a set of member packages; the apps view shows
 * membership as pills on each row, the filter sheet filters by group, and — because the app list's
 * bulk-rule toolbar acts on whatever the current filter selects — filtering to a group turns the
 * whole toolbar into "apply this rule to every app in the group".
 *
 * Members are keyed by **package name**, never uid: a uid is install-specific, so a uid-keyed group
 * would silently point at the wrong apps after a reinstall or an export/import round trip (the same
 * rule the per-app firewall import follows).
 *
 * Storage is a dedicated SharedPreferences file so [KojikiExport] can carry it with the generic prefs
 * exporter:
 *   - `groups`     → the ordered group names, "\n"-joined (order is the display order).
 *   - `g:<name>`   → that group's member package names (a string set).
 */
object KojikiAppGroups {

    /** Must match [KojikiExport.PREFS_APP_GROUPS]. */
    const val PREFS = "kojiki_app_groups"

    private const val KEY_ORDER = "groups"
    private const val MEMBER_PREFIX = "g:"
    private const val SEP = "\n"

    // Read on every row bind, so keep a snapshot instead of re-parsing the prefs each time. Dropped
    // wholesale on any write (and by KojikiExport after an import swaps the file underneath us).
    @Volatile private var cachedOrder: List<String>? = null
    @Volatile private var cachedByPkg: Map<String, List<String>>? = null

    fun invalidateCache() {
        cachedOrder = null
        cachedByPkg = null
    }

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Every group, in display order. */
    fun groups(context: Context): List<String> {
        cachedOrder?.let { return it }
        val raw = sp(context).getString(KEY_ORDER, "").orEmpty()
        val list = raw.split(SEP).map { it.trim() }.filter { it.isNotEmpty() }
        cachedOrder = list
        return list
    }

    /** The member packages of [group]. */
    fun members(context: Context, group: String): Set<String> =
        sp(context).getStringSet(MEMBER_PREFIX + group, emptySet()) ?: emptySet()

    /** The groups [pkg] belongs to, in display order. */
    fun groupsOf(context: Context, pkg: String): List<String> = membership(context)[pkg].orEmpty()

    /** Every package in any of [names]. An empty [names] yields an empty set — callers decide what
     *  "no groups selected" means (the apps view treats it as "no group filter"). */
    fun packagesIn(context: Context, names: Collection<String>): Set<String> {
        if (names.isEmpty()) return emptySet()
        val out = HashSet<String>()
        for (n in names) out.addAll(members(context, n))
        return out
    }

    /** package name → its groups, in display order. Cached; [invalidateCache] drops it. */
    private fun membership(context: Context): Map<String, List<String>> {
        cachedByPkg?.let { return it }
        val map = HashMap<String, MutableList<String>>()
        for (g in groups(context)) {
            for (pkg in members(context, g)) {
                map.getOrPut(pkg) { mutableListOf() }.add(g)
            }
        }
        cachedByPkg = map
        return map
    }

    // ---- mutation -------------------------------------------------------------------------------

    /** Create [name] if it is non-blank and not already taken (case-insensitively). Returns whether
     *  a group was actually created. */
    fun create(context: Context, name: String): Boolean {
        val n = normalize(name)
        if (n.isEmpty()) return false
        val existing = groups(context)
        if (existing.any { it.equals(n, ignoreCase = true) }) return false
        writeOrder(context, existing + n)
        return true
    }

    /** Rename [from] to [to], carrying its members. No-op when [to] is blank or already taken. */
    fun rename(context: Context, from: String, to: String): Boolean {
        val n = normalize(to)
        if (n.isEmpty() || n == from) return false
        val existing = groups(context)
        if (!existing.contains(from)) return false
        if (existing.any { it.equals(n, ignoreCase = true) }) return false
        val mem = members(context, from)
        sp(context).edit()
            .remove(MEMBER_PREFIX + from)
            .putStringSet(MEMBER_PREFIX + n, mem)
            .apply()
        writeOrder(context, existing.map { if (it == from) n else it })
        return true
    }

    /** Delete [name] and its membership. */
    fun delete(context: Context, name: String) {
        sp(context).edit().remove(MEMBER_PREFIX + name).apply()
        writeOrder(context, groups(context).filterNot { it == name })
    }

    /** Replace [pkg]'s membership with exactly [selected] (a group not in [selected] loses it). */
    fun setMembership(context: Context, pkg: String, selected: Set<String>) {
        val ed = sp(context).edit()
        for (g in groups(context)) {
            val mem = HashSet(members(context, g))
            val want = selected.contains(g)
            if (want == mem.contains(pkg)) continue
            if (want) mem.add(pkg) else mem.remove(pkg)
            ed.putStringSet(MEMBER_PREFIX + g, mem)
        }
        ed.apply()
        invalidateCache()
    }

    /** Drop [pkg] from [group]. */
    fun removeFrom(context: Context, group: String, pkg: String) {
        val mem = HashSet(members(context, group))
        if (!mem.remove(pkg)) return
        sp(context).edit().putStringSet(MEMBER_PREFIX + group, mem).apply()
        invalidateCache()
    }

    private fun writeOrder(context: Context, names: List<String>) {
        sp(context).edit().putString(KEY_ORDER, names.joinToString(SEP)).apply()
        invalidateCache()
    }

    /** A group name is a single trimmed line — the store joins names with "\n". */
    private fun normalize(name: String): String = name.replace(SEP, " ").trim()

    // ---- dialogs --------------------------------------------------------------------------------

    /**
     * Add / remove [pkg] across the existing groups — a checklist of every group with the current
     * membership pre-ticked, plus a "New group…" action. With no groups defined yet it goes straight
     * to the create prompt, so the very first "+" tap is one step, not two.
     */
    @UiThread
    fun showMembershipDialog(
        context: Context,
        pkg: String,
        appLabel: String?,
        onChanged: () -> Unit
    ) {
        val all = groups(context)
        if (all.isEmpty()) {
            showCreateDialog(context) { created ->
                setMembership(context, pkg, setOf(created))
                onChanged()
            }
            return
        }
        val mine = groupsOf(context, pkg).toHashSet()
        val checked = BooleanArray(all.size) { mine.contains(all[it]) }
        KojikiDialog.show(
            context,
            appLabel ?: context.getString(R.string.kojiki_group_add_title),
            listOf(
                KojikiDialog.Action(context.getString(R.string.kojiki_group_new), leading = true) {
                    // Carry the ticks already made into the new group's creation, so opening
                    // "New group…" never silently discards them.
                    showCreateDialog(context) { created ->
                        val keep = all.filterIndexed { i, _ -> checked[i] }.toMutableSet()
                        keep.add(created)
                        setMembership(context, pkg, keep)
                        onChanged()
                    }
                },
                KojikiDialog.Action(context.getString(R.string.lbl_cancel)),
                KojikiDialog.Action(context.getString(R.string.lbl_save)) {
                    setMembership(context, pkg, all.filterIndexed { i, _ -> checked[i] }.toSet())
                    onChanged()
                })
        ) { body, _ ->
            all.forEachIndexed { i, name ->
                body.addView(
                    KojikiDialog.checkbox(context, name, checked[i]).apply {
                        setOnCheckedChangeListener { _, isChecked -> checked[i] = isChecked }
                    })
            }
        }
    }

    /** Prompt for a new group name; [onCreated] gets the created name (never called on a duplicate
     *  or blank name — a duplicate simply reports itself and closes). */
    @UiThread
    fun showCreateDialog(context: Context, onCreated: (String) -> Unit) {
        val input =
            KojikiDialog.input(
                context, "", context.getString(R.string.kojiki_group_name_hint))
        KojikiDialog.show(
            context,
            context.getString(R.string.kojiki_group_new),
            listOf(
                KojikiDialog.Action(context.getString(R.string.lbl_cancel)),
                KojikiDialog.Action(context.getString(R.string.lbl_add)) {
                    val n = normalize(input.text.toString())
                    if (n.isEmpty()) return@Action
                    if (create(context, n)) {
                        onCreated(n)
                    } else {
                        Utilities.showToastUiCentered(
                            context,
                            context.getString(R.string.kojiki_group_exists, n),
                            android.widget.Toast.LENGTH_SHORT)
                    }
                })
        ) { body, _ ->
            body.addView(
                input,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** Manage the group list itself: pick a group to rename or delete, or create a new one. */
    @UiThread
    fun showManageDialog(context: Context, onChanged: () -> Unit) {
        val all = groups(context)
        if (all.isEmpty()) {
            showCreateDialog(context) { onChanged() }
            return
        }
        KojikiDialog.show(
            context,
            context.getString(R.string.kojiki_group_manage),
            listOf(
                KojikiDialog.Action(context.getString(R.string.kojiki_group_new), leading = true) {
                    showCreateDialog(context) { onChanged() }
                },
                KojikiDialog.Action(context.getString(R.string.lbl_cancel)))
        ) { body, dialog ->
            for (g in all) {
                val label =
                    context.getString(R.string.kojiki_group_manage_row, g, members(context, g).size)
                body.addView(
                    KojikiDialog.row(context, label) {
                        dialog.dismiss()
                        showEditDialog(context, g, onChanged)
                    })
            }
        }
    }

    /** Rename or delete one group. */
    @UiThread
    private fun showEditDialog(context: Context, group: String, onChanged: () -> Unit) {
        val input = KojikiDialog.input(context, group, context.getString(R.string.kojiki_group_name_hint))
        KojikiDialog.show(
            context,
            group,
            listOf(
                KojikiDialog.Action(context.getString(R.string.lbl_delete), leading = true) {
                    delete(context, group)
                    onChanged()
                },
                KojikiDialog.Action(context.getString(R.string.lbl_cancel)),
                KojikiDialog.Action(context.getString(R.string.lbl_save)) {
                    rename(context, group, input.text.toString())
                    onChanged()
                })
        ) { body, _ ->
            body.addView(
                input,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    // ---- row pills ------------------------------------------------------------------------------

    /**
     * A membership pill for an apps-view row: a rounded, accent-bordered chip — **outlined, never
     * filled**: accent text on the background colour, with an accent border. A solid accent fill
     * reads as a highlighted/selected state and shouts over the row it annotates.
     *
     * [add] marks the trailing "+" affordance: it takes the shared [ADD_PILL_WIDTH_DP] so it is
     * exactly as wide as the note "+" pill on the line above, and is drawn at [ADD_PILL_ALPHA] so
     * both add controls sit back into the row instead of competing with the memberships they create.
     */
    fun pill(context: Context, label: String, add: Boolean = false): TextView {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val custom = CustomUi.customThemeActive
        val accent =
            if (custom) CustomUiConfig(context).accentColor
            else UIUtils.fetchColor(context, R.attr.accentGood)
        val bg =
            if (custom) CustomUiConfig(context).backgroundColor
            else UIUtils.fetchColor(context, R.attr.background)
        val ink = if (add) KojikiDialog.withAlpha(accent, ADD_PILL_ALPHA) else accent
        return TextView(context).apply {
            text = label
            textSize = if (add) 13f else 11f
            includeFontPadding = false
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(2), dp(8), dp(2))
            minWidth = dp(26)
            setTextColor(ink)
            background =
                CustomUi.snoopPillBackground(context, bg, PILL_RADIUS_DP, PILL_BORDER_DP, ink)
            layoutParams =
                LinearLayout.LayoutParams(
                    if (add) dp(ADD_PILL_WIDTH_DP) else ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = dp(4) }
        }
    }

    /** Width shared by the two "add" affordances — the note pill on the label line and the group "+"
     *  pill on the line below — so they read as one column rather than two ragged controls. */
    const val ADD_PILL_WIDTH_DP = 40

    /** Opacity of both "add" affordances: present, but faded back into the row. */
    const val ADD_PILL_ALPHA = 0.55f

    private const val PILL_RADIUS_DP = 8
    private const val PILL_BORDER_DP = 1
}
