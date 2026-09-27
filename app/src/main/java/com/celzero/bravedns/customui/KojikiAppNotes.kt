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
import android.content.res.ColorStateList
import android.text.InputFilter
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.UiThread
import androidx.appcompat.widget.TooltipCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.util.UIUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fork (白い熊 考直): free-text per-app notes for the apps view — "why is this app excluded?", "do not
 * block, breaks X". Modelled on 白い熊 応用管理's `AppNotesManager`, same operation: a row carries a
 * glyph affordance (a "+" when there is no note, a filled note glyph when there is one), tapping it
 * opens a pre-filled multi-line dialog, and **saving a blank note deletes it**.
 *
 * ### Storage: upstream's own column (folded 2026-09-27)
 * The note lives in **`AppInfo.notes`**, the column upstream added at v0.5.7, written through
 * [FirewallManager.updateAppNotes] so the row cache and the database stay in step. Before that the
 * fork kept its own package-keyed prefs file, which meant two separate stores for one idea; the old
 * file is migrated on first use by [migrateLegacyStore] and then keeps only a much smaller job.
 *
 * That job is the **park**: `AppInfo.notes` can only hold a note for an app that has a row, so a
 * note for a package that is not installed — an import from another phone, a rule written ahead of
 * time — waits in [PREFS] keyed by package name until the app appears, exactly as
 * [com.celzero.bravedns.service.KojikiPendingFw] parks firewall rules. [applyParked] is called from
 * `RefreshDatabase.insertApp` and lands the note on the row being inserted.
 *
 * Reading a note for a row already in hand is therefore free — it is just [AppInfo.notes], no
 * lookup ([noteOf]); only the by-package paths (import, export, the synthetic-row marker) pay for a
 * cache lookup, and those are already suspend.
 *
 * Limitation (as in 応用管理): a package installed under several Android users shares one note.
 */
object KojikiAppNotes {

    /**
     * Park for notes whose app is not installed yet (and, until [migrateLegacyStore] runs once, the
     * fork's former notes store). Must match [KojikiExport.PREFS_APP_NOTES].
     */
    const val PREFS = "kojiki_app_notes"

    /** Set once [migrateLegacyStore] has folded the old store into `AppInfo.notes`. */
    private const val KEY_MIGRATED = "__kojiki_notes_migrated"

    /**
     * Hard cap on a note, matching `AppDatabase.APP_NOTES_MAX_LENGTH`. Upstream enforces it with a
     * SQLite trigger that **ABORTs** the write, so anything longer has to be clamped before it
     * reaches the database rather than discovered as a failed transaction.
     */
    const val MAX_LENGTH = 500

    private val scope = CoroutineScope(Dispatchers.IO)

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun clamp(text: CharSequence?): String =
        text?.toString()?.trim().orEmpty().take(MAX_LENGTH)

    /** The note carried by a row already in hand — no lookup, no I/O. */
    fun noteOf(appInfo: AppInfo): String? = appInfo.notes.ifBlank { null }

    /** The parked note for [pkg] (an app that has no row yet), or null. */
    fun parkedNote(context: Context, pkg: String): String? =
        sp(context).getString(pkg, null)?.ifBlank { null }

    /** The note for [pkg]: the installed row's column, else whatever is parked for it. */
    suspend fun getNote(context: Context, pkg: String): String? =
        FirewallManager.getAppInfoByPackage(pkg)?.notes?.ifBlank { null }
            ?: parkedNote(context, pkg)

    /**
     * Persist (or, on blank text, delete) the note for [pkg]. Writes the installed row's column;
     * with no row yet the note is parked until the app is installed. Text longer than [MAX_LENGTH]
     * is clamped — see the note there.
     */
    suspend fun setNote(context: Context, pkg: String, text: CharSequence?) {
        val trimmed = clamp(text)
        val app = FirewallManager.getAppInfoByPackage(pkg)
        if (app != null) {
            FirewallManager.updateAppNotes(app.uid, pkg, trimmed)
            // a row exists, so nothing should still be parked for this package
            if (sp(context).contains(pkg)) sp(context).edit().remove(pkg).apply()
        } else {
            val ed = sp(context).edit()
            if (trimmed.isEmpty()) ed.remove(pkg) else ed.putString(pkg, trimmed)
            ed.apply()
        }
    }

    /**
     * Land a parked note on the row about to be inserted for a freshly installed app, and drop the
     * parked copy. Called from `RefreshDatabase.insertApp` beside
     * [com.celzero.bravedns.service.KojikiPendingFw.applyTo]; kept separate from it because that
     * one's return value reports a restored *rule* to the new-app notification.
     */
    fun applyParked(context: Context, entry: AppInfo): Boolean {
        val parked = parkedNote(context, entry.packageName) ?: return false
        entry.notes = parked.take(MAX_LENGTH)
        sp(context).edit().remove(entry.packageName).apply()
        return true
    }

    /**
     * One-time fold of the fork's former prefs store into `AppInfo.notes`. Every entry whose package
     * has a row is written to the column; entries with no row stay exactly where they are and become
     * park entries, which is the same file's new job. Idempotent, and a no-op on a fresh install.
     */
    suspend fun migrateLegacyStore(context: Context) {
        val sp = sp(context)
        if (sp.getBoolean(KEY_MIGRATED, false)) return
        val entries = sp.all.filterKeys { it != KEY_MIGRATED }
        if (entries.isEmpty()) { sp.edit().putBoolean(KEY_MIGRATED, true).apply(); return }

        // Only run against a populated cache. An unresolved package is read as "not installed, keep
        // it parked" — which is right once the apps are loaded and catastrophically wrong before
        // they are: every note would be parked, the run would mark itself done, and since a parked
        // note is only ever landed by a fresh install, every existing note would silently vanish
        // from its row. So if the cache is empty, do nothing and leave the flag alone; the next
        // visit to the app list runs it again.
        if (FirewallManager.getAllApps().isEmpty()) return

        var failed = false
        for ((pkg, v) in entries) {
            val note = (v as? String)?.trim().orEmpty()
            if (note.isEmpty()) { sp.edit().remove(pkg).apply(); continue }
            val app = FirewallManager.getAppInfoByPackage(pkg) ?: continue // not installed: parked
            val ok = runCatching {
                FirewallManager.updateAppNotes(app.uid, pkg, note.take(MAX_LENGTH))
            }.isSuccess
            if (ok) sp.edit().remove(pkg).apply() else failed = true
        }
        // A failed write leaves the note where it was, so retry on the next run rather than
        // declaring the fold complete over the top of it.
        if (!failed) sp.edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    /** Every package that carries a parked note — the ones still waiting for their app. */
    fun parkedPackages(context: Context): Set<String> =
        sp(context).all.filterKeys { it != KEY_MIGRATED }
            .filterValues { it is String && it.isNotBlank() }.keys

    /**
     * View / edit the note for [appInfo]. The field opens pre-filled and immediately editable; Save
     * persists it, and **a blank field deletes the note** (the glyph reverts to "+"). [onSaved] runs
     * on the UI thread after the write lands, so the calling row can re-render its glyph.
     */
    @UiThread
    fun showNoteDialog(
        context: Context,
        appInfo: AppInfo,
        onSaved: (() -> Unit)? = null
    ) {
        // Just "Note" as the field hint — 応用管理's wording; no chatty placeholder sentence.
        val input =
            KojikiDialog.input(
                context, noteOf(appInfo), context.getString(R.string.kojiki_note),
                multiLine = true)
        // Stop typing at the column's limit rather than truncating on save — the same cap upstream's
        // own notes dialog applies, and the one its SQLite trigger enforces by aborting the write.
        input.filters = arrayOf(InputFilter.LengthFilter(MAX_LENGTH))
        KojikiDialog.show(
            context,
            appInfo.appName.ifBlank { context.getString(R.string.kojiki_note) },
            listOf(
                KojikiDialog.Action(context.getString(R.string.lbl_cancel)),
                KojikiDialog.Action(context.getString(R.string.lbl_save)) {
                    val text = clamp(input.text)
                    scope.launch {
                        setNote(context, appInfo.packageName, text)
                        // keep the row object the adapter is holding in step with the write, so the
                        // re-render below reads the new note rather than the pre-save one
                        appInfo.notes = text
                        withContext(Dispatchers.Main) { onSaved?.invoke() }
                    }
                })
        ) { body, _ ->
            body.addView(
                input,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            body.addView(
                KojikiDialog.helper(context, context.getString(R.string.kojiki_note_blank_deletes)))
        }
    }

    // A note that EXISTS annotates the row; it must not compete with the app name or the group pills
    // for attention, so its border, glyph and text are all drawn well below full opacity — that is
    // what makes it read as a margin note rather than as a second title. The empty "add a note"
    // state borrows the group "+" pill's alpha and width instead, so the two add controls are
    // identical and stack into one column.
    private const val ALPHA_BORDER = 0.40f
    private const val ALPHA_CONTENT = 0.60f

    /**
     * Render the row's note affordance for [appInfo] and report whether a note exists.
     *
     * It is one pill either way, so the two states read as the same control: with a note it holds
     * the glyph plus the note's text (one line, ellipsized) and the caller lets it start right after
     * the app label; with none it holds just the "+" glyph and the caller pins it to the row's right
     * edge, directly above the group "+" pill. The full note is the long-press tooltip — that is how
     * a note too long for the line stays readable.
     */
    fun bindRow(
        context: Context,
        pill: View,
        glyph: ImageView,
        noteTv: TextView,
        appInfo: AppInfo
    ): Boolean {
        // The note rides on the row itself now (upstream's AppInfo.notes), so binding costs nothing
        // — no store lookup per row, and the paging source re-emits when the column changes.
        val note = noteOf(appInfo)
        val has = note != null
        val d = context.resources.displayMetrics.density
        val accent =
            if (CustomUi.customThemeActive) CustomUiConfig(context).accentColor
            else UIUtils.fetchColor(context, R.attr.accentGood)
        val border =
            KojikiDialog.withAlpha(
                accent, if (has) ALPHA_BORDER else KojikiAppGroups.ADD_PILL_ALPHA)
        val content =
            KojikiDialog.withAlpha(
                accent, if (has) ALPHA_CONTENT else KojikiAppGroups.ADD_PILL_ALPHA)

        pill.background = GradientDrawable().apply {
            cornerRadius = 8 * d
            setColor(
                if (CustomUi.customThemeActive) CustomUiConfig(context).backgroundColor
                else UIUtils.fetchColor(context, R.attr.background))
            setStroke(maxOf(1, (1 * d).toInt()), border)
        }
        glyph.setImageResource(if (has) R.drawable.ic_kojiki_note else R.drawable.ic_kojiki_note_add)
        glyph.imageTintList = ColorStateList.valueOf(content)
        TooltipCompat.setTooltipText(
            pill, if (has) note else context.getString(R.string.kojiki_note_add))

        // The empty state carries no text at all — the plus lives inside the glyph itself, drawn
        // large enough to read as an add control, so a separate "+" character next to it was just
        // noise. With a note, this slot is the note's own text.
        if (has) {
            noteTv.text = note
            noteTv.setTextColor(content)
            // The pill wraps its content and a weighted spacer holds it against the right edge, so
            // the note grows leftward into the spacer — never into the app name, which is
            // weightless. This cap is what stops a very long note from running past the spacer and
            // being clipped at the row's edge. Sized off the display rather than the row: the row is
            // not measured yet at bind time, and it is very nearly the display's width anyway.
            noteTv.maxWidth =
                (context.resources.displayMetrics.widthPixels * NOTE_MAX_WIDTH_FRACTION).toInt()
            noteTv.visibility = View.VISIBLE
        } else {
            noteTv.text = ""
            noteTv.visibility = View.GONE
        }
        return has
    }

    /** How much of the display width the note's first line may claim before the app name stops
     *  yielding. Leaves the label roughly the other half of the row. */
    private const val NOTE_MAX_WIDTH_FRACTION = 0.60f
}
