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
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.UiThread
import androidx.appcompat.widget.TooltipCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.util.UIUtils

/**
 * Fork (白い熊 考直): free-text per-app notes for the apps view — "why is this app excluded?", "do not
 * block, breaks X". Modelled on 白い熊 応用管理's `AppNotesManager`, same operation: a row carries a
 * glyph affordance (a "+" when there is no note, a filled note glyph when there is one), tapping it
 * opens a pre-filled multi-line dialog, and **saving a blank note deletes it**.
 *
 * Storage is a dedicated SharedPreferences file keyed by **package name** — never uid, which changes
 * on every reinstall (the same rule the Export/Import per-app firewall rules follow). The dedicated
 * file also makes the Export/Import category a two-line change: [KojikiExport] carries it with the
 * generic prefs exporter.
 *
 * Limitation (as in 応用管理): a package installed under several Android users shares one note.
 */
object KojikiAppNotes {

    /** Must match [KojikiExport.PREFS_APP_NOTES]. */
    const val PREFS = "kojiki_app_notes"

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The stored note for [pkg], or null when there is none. */
    fun getNote(context: Context, pkg: String): String? = sp(context).getString(pkg, null)

    /** True when [pkg] carries a non-blank note. */
    fun hasNote(context: Context, pkg: String): Boolean = !getNote(context, pkg).isNullOrBlank()

    /** Persist (or, on blank text, delete) the note for [pkg]. */
    fun setNote(context: Context, pkg: String, text: CharSequence?) {
        val trimmed = text?.toString()?.trim().orEmpty()
        val ed = sp(context).edit()
        if (trimmed.isEmpty()) ed.remove(pkg) else ed.putString(pkg, trimmed)
        ed.apply()
    }

    /** Every package that carries a note — used by the apps-view "has a note" filter. */
    fun notedPackages(context: Context): Set<String> =
        sp(context).all.filterValues { it is String && it.isNotBlank() }.keys

    /**
     * View / edit the note for [pkg]. The field opens pre-filled and immediately editable; Save
     * persists it, and **a blank field deletes the note** (the glyph reverts to "+"). [onSaved] runs
     * on the UI thread after a save, so the calling row can re-render its glyph.
     */
    @UiThread
    fun showNoteDialog(
        context: Context,
        pkg: String,
        appLabel: String?,
        onSaved: (() -> Unit)? = null
    ) {
        // Just "Note" as the field hint — 応用管理's wording; no chatty placeholder sentence.
        val input =
            KojikiDialog.input(
                context, getNote(context, pkg), context.getString(R.string.kojiki_note),
                multiLine = true)
        KojikiDialog.show(
            context,
            appLabel ?: context.getString(R.string.kojiki_note),
            listOf(
                KojikiDialog.Action(context.getString(R.string.lbl_cancel)),
                KojikiDialog.Action(context.getString(R.string.lbl_save)) {
                    setNote(context, pkg, input.text)
                    onSaved?.invoke()
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
     * Render the row's note affordance for [pkg] and report whether a note exists.
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
        pkg: String
    ): Boolean {
        val note = getNote(context, pkg)
        val has = !note.isNullOrBlank()
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
