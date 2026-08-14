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
import androidx.annotation.StringRes
import androidx.annotation.UiThread
import com.celzero.bravedns.R
import com.celzero.bravedns.util.UIUtils

/**
 * Fork (白い熊 考直): the apps view's sort order — which key the list is ordered by, and in which
 * direction.
 *
 * Upstream orders the app list by `lower(appName)` and offers no choice. The keys added here are the
 * ones a firewall is actually read by: the app name, the package id, the **uid** (the number every
 * adb command, connection log and firewall rule speaks in), and how much data the app has moved.
 *
 * The choice is persisted in its own small SharedPreferences file, so it survives leaving the apps
 * view and restarting the app — a sort you have to re-pick on every visit is worse than none. The
 * ordering itself is done in SQL ([com.celzero.bravedns.database.AppInfoDAO.getSortedApps]): a paged
 * list cannot be re-sorted in Kotlin, because each page is fetched on its own.
 */
object KojikiAppSort {

    const val PREFS = "kojiki_app_sort"

    private const val K_BY = "sort_by"
    private const val K_DESC = "sort_desc"

    /** The sort keys. [id] is what the DAO's `sortKey` parameter binds to — never renumber them. */
    enum class By(val id: Int, @StringRes val labelRes: Int) {
        NAME(0, R.string.kojiki_sort_by_name),
        PACKAGE(1, R.string.kojiki_sort_by_package),
        UID(2, R.string.kojiki_sort_by_uid),
        DATA(3, R.string.kojiki_sort_by_data);

        companion object {
            fun of(id: Int): By = entries.firstOrNull { it.id == id } ?: NAME
        }
    }

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The persisted sort key — the app name, as upstream, until it is changed. */
    fun sortBy(context: Context): By = By.of(sp(context).getInt(K_BY, By.NAME.id))

    /** Whether the persisted order is descending. */
    fun descending(context: Context): Boolean = sp(context).getBoolean(K_DESC, false)

    fun save(context: Context, by: By, desc: Boolean) {
        sp(context).edit().putInt(K_BY, by.id).putBoolean(K_DESC, desc).apply()
    }

    /** The arrow that marks the active key's direction. */
    fun arrow(context: Context, desc: Boolean): String =
        context.getString(if (desc) R.string.kojiki_sort_desc_arrow else R.string.kojiki_sort_asc_arrow)

    /** "App name ↑" — the current order, for a toast or a tooltip. */
    fun describe(context: Context, by: By, desc: Boolean): String =
        context.getString(R.string.kojiki_sort_row, context.getString(by.labelRes), arrow(context, desc))

    /**
     * Pick the sort order. The active key is marked with its direction arrow and drawn in the accent
     * colour; **tapping the active key reverses** the direction, tapping any other key selects it
     * ascending. [onPicked] runs on the UI thread with the new order — persisting it is the caller's
     * job, since the caller also owns the list it has to re-query.
     */
    @UiThread
    fun showSortDialog(
        context: Context,
        current: By,
        desc: Boolean,
        onPicked: (By, Boolean) -> Unit
    ) {
        val accent =
            if (CustomUi.customThemeActive) CustomUiConfig(context).accentColor
            else UIUtils.fetchColor(context, R.attr.accentGood)
        KojikiDialog.show(
            context,
            context.getString(R.string.kojiki_sort_title),
            listOf(KojikiDialog.Action(context.getString(R.string.lbl_cancel)))) { box, dialog ->
                for (by in By.entries) {
                    val active = by == current
                    val label =
                        if (active) {
                            context.getString(
                                R.string.kojiki_sort_row,
                                context.getString(by.labelRes),
                                arrow(context, desc))
                        } else {
                            context.getString(by.labelRes)
                        }
                    val row =
                        KojikiDialog.row(context, label) {
                            dialog.dismiss()
                            // the active key toggles direction; any other key starts ascending
                            onPicked(by, if (active) !desc else false)
                        }
                    if (active) row.setTextColor(accent)
                    box.addView(row)
                }
                box.addView(KojikiDialog.helper(context, context.getString(R.string.kojiki_sort_hint)))
            }
    }
}
