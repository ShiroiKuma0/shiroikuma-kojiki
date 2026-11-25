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
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.celzero.bravedns.R

/**
 * Fork (白い熊 考直): what the three app-level firewall buttons actually do.
 *
 * Long-pressing Bypass DNS & Firewall / Bypass Universal / Exclude on the app screen used to raise
 * the platform tooltip — a white flash of one sentence, unthemed and gone before it is read. This
 * replaces it with a near-full-screen scrollable dialog in the fork's own black/yellow box: a
 * paragraph per button (the pressed one first) and a rule-by-rule table of what each one waives.
 *
 * The table is not folklore — it is the decision order in `TunFirewallManager.firewall()`, where
 * both bypass branches return before the universal-toggle tail, and `TunDnsManager.onQuery()`,
 * which consults only `bypassDnsFirewall`. Two consequences people get wrong and the table states
 * plainly: **both** bypasses waive "app not in use" and "device locked", and choosing either one
 * stores the row's own WiFi/mobile-data toggles as *allowed* (the app screen, the bulk toolbar and
 * the SET_APP_RULE intent all do) — those toggles are tested earlier, so a bypass stored next to a
 * block would still be blocked, which is exactly why no path stores one.
 *
 * The text is in the fork's own `strings_kojiki.xml` (keys `kojiki_fwh_*`, Japanese beside it) —
 * not in upstream's translated `strings.xml`, which would be one more rebase conflict. Button
 * names come from upstream's resources so they track the rebrand.
 */
object KojikiFirewallHelp {

    /** The three buttons this explains; [labelRes] is what the button itself says. */
    enum class Rule(val labelRes: Int) {
        BYPASS_DNS_FIREWALL(R.string.ada_app_bypass_dns_firewall),
        BYPASS_UNIVERSAL(R.string.ada_app_bypass_univ),
        EXCLUDE(R.string.ada_app_exclude)
    }

    // A reading dialog, not a prompt: it claims most of the screen and scrolls.
    private const val CONTENT_FRACTION = 0.78f
    private const val COL_DP = 64

    private const val YES = "✓"
    private const val NO = "✗"
    private const val NA = "–"

    private class Row(val label: String, val bdf: String, val bu: String, val ex: String)

    private class Section(val title: String, val rows: List<Row>)

    fun show(context: Context, focus: Rule) {
        KojikiDialog.show(
            context,
            // neutral: the pressed button already names itself in the first heading below
            title(context),
            listOf(KojikiDialog.Action(context.getString(R.string.dns_info_positive))),
            CONTENT_FRACTION) { box, _ ->
            // the pressed button leads; the other two follow, since the whole point is the contrast
            val order = listOf(focus) + Rule.entries.filter { it != focus }
            for (r in order) {
                box.addView(heading(context, context.getString(r.labelRes), r == focus))
                box.addView(para(context, blurb(context, r)))
            }

            box.addView(divider(context, 0.4f, 14))
            box.addView(heading(context, context.getString(R.string.kojiki_fwh_rule_by_rule), false))
            box.addView(para(context, context.getString(R.string.kojiki_fwh_legend)))
            box.addView(table(context))

            box.addView(heading(context, context.getString(R.string.kojiki_fwh_notes_title), false))
            for ((i, n) in NOTES.withIndex()) box.addView(note(context, i + 1, context.getString(n)))
            box.addView(divider(context, 0.4f, 14))
            box.addView(para(context, context.getString(R.string.kojiki_fwh_tail)))
        }
    }

    // ---- content -----------------------------------------------------------------------------

    private fun blurb(context: Context, r: Rule): String {
        val app = context.getString(R.string.app_name)
        return when (r) {
            Rule.BYPASS_DNS_FIREWALL -> context.getString(R.string.kojiki_fwh_blurb_bdf, app)
            Rule.BYPASS_UNIVERSAL -> context.getString(R.string.kojiki_fwh_blurb_bu)
            Rule.EXCLUDE -> context.getString(R.string.kojiki_fwh_blurb_ex, app)
        }
    }

    /**
     * The dialog's own title, larger and underlined. Spanned rather than restyled: the title view
     * belongs to [KojikiDialog] and every fork dialog shares its 18sp, so the emphasis has to ride
     * on the text.
     */
    private fun title(context: Context): CharSequence =
        SpannableString(context.getString(R.string.kojiki_fwh_title)).apply {
            setSpan(RelativeSizeSpan(1.35f), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(UnderlineSpan(), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    private val NOTES =
        listOf(
            R.string.kojiki_fwh_note_1, R.string.kojiki_fwh_note_2, R.string.kojiki_fwh_note_3,
            R.string.kojiki_fwh_note_4, R.string.kojiki_fwh_note_5)

    private fun sections(context: Context): List<Section> {
        val app = context.getString(R.string.app_name)
        return listOf(
            Section(
                context.getString(R.string.kojiki_fwh_sec_dns),
                listOf(
                    Row(context.getString(R.string.kojiki_fwh_row_resolved, app), YES, YES, NO),
                    Row(context.getString(R.string.kojiki_fwh_row_blocklists), NO, YES, NA),
                    Row(context.getString(R.string.kojiki_fwh_row_blocked_at_connect), NO, YES, NA),
                    Row(context.getString(R.string.kojiki_fwh_row_leaks), NO, YES, NA),
                    Row(context.getString(R.string.kojiki_fwh_row_disallow_bypass), NO, NO, NA),
                    Row(context.getString(R.string.kojiki_fwh_row_dns_logging), YES, YES, NO))),
            Section(
                context.getString(R.string.kojiki_fwh_sec_universal),
                listOf(
                    Row(context.getString(R.string.kojiki_fwh_row_not_in_use), NO, NO, NA),
                    Row(context.getString(R.string.kojiki_fwh_row_locked), NO, NO, NA),
                    Row(context.getString(R.string.kojiki_fwh_row_metered), NO, NO, NA),
                    Row(context.getString(R.string.kojiki_fwh_row_lockdown), NO, NO, NA),
                    Row(context.getString(R.string.kojiki_fwh_row_http), NO, NO, NA),
                    Row(context.getString(R.string.kojiki_fwh_row_udp), NO, NO, NA),
                    Row(context.getString(R.string.kojiki_fwh_row_univ_domain), NO, NO, NA),
                    Row(context.getString(R.string.kojiki_fwh_row_univ_ip), NO, NO, NA))),
            Section(
                context.getString(R.string.kojiki_fwh_sec_app),
                listOf(
                    Row(context.getString(R.string.kojiki_fwh_row_app_wifi), NO, NO, NA),
                    Row(context.getString(R.string.kojiki_fwh_row_app_domain), YES, YES, NA),
                    Row(context.getString(R.string.kojiki_fwh_row_app_ip), YES, YES, NA))),
            Section(
                context.getString(R.string.kojiki_fwh_sec_tunnel),
                listOf(
                    Row(context.getString(R.string.kojiki_fwh_row_enters), YES, YES, NO),
                    Row(context.getString(R.string.kojiki_fwh_row_conn_log), YES, YES, NO),
                    Row(context.getString(R.string.kojiki_fwh_row_wg), YES, YES, NO),
                    Row(context.getString(R.string.kojiki_fwh_row_android_lockdown), YES, YES, NO))))
    }

    // ---- views -------------------------------------------------------------------------------

    private fun dp(context: Context, v: Int) =
        (v * context.resources.displayMetrics.density).toInt()

    private fun heading(context: Context, text: String, strong: Boolean) =
        TextView(context).apply {
            this.text = text
            textSize = if (strong) 17f else 16f
            setTextColor(KojikiDialog.accentOf(context))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(context, 14), 0, dp(context, 4))
        }

    private fun para(context: Context, text: CharSequence) =
        TextView(context).apply {
            this.text = text
            textSize = 16f
            setTextColor(KojikiDialog.textOf(context))
            setLineSpacing(dp(context, 3).toFloat(), 1f)
        }

    private fun note(context: Context, n: Int, text: String) =
        TextView(context).apply {
            this.text = "$n.  $text"
            textSize = 15f
            setTextColor(KojikiDialog.withAlpha(KojikiDialog.textOf(context), 0.75f))
            setLineSpacing(dp(context, 3).toFloat(), 1f)
            setPadding(0, dp(context, 6), 0, 0)
        }

    private fun divider(context: Context, alpha: Float, topDp: Int) =
        View(context).apply {
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1))
                    .apply { topMargin = dp(context, topDp) }
            setBackgroundColor(KojikiDialog.withAlpha(KojikiDialog.accentOf(context), alpha))
        }

    private fun table(context: Context): LinearLayout {
        val t =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(context, 10), 0, 0)
            }
        t.addView(headerRow(context))
        t.addView(divider(context, 0.4f, 4))
        for (s in sections(context)) {
            t.addView(sectionRow(context, s.title))
            for (r in s.rows) {
                t.addView(dataRow(context, r))
                t.addView(divider(context, 0.12f, 0))
            }
        }
        return t
    }

    private fun rowShell(context: Context) =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 2), 0, dp(context, 2))
        }

    private fun headerRow(context: Context): LinearLayout {
        val row = rowShell(context).apply { setPadding(0, dp(context, 4), 0, dp(context, 4)) }
        row.addView(labelCell(context, "", accent = true))
        val accent = KojikiDialog.accentOf(context)
        for (h in listOf(R.string.kojiki_fwh_col_bdf, R.string.kojiki_fwh_col_bu, R.string.kojiki_fwh_col_ex).map { context.getString(it) }) {
            row.addView(
                glyphCell(context, h, accent, 12f).apply { typeface = Typeface.DEFAULT_BOLD })
        }
        return row
    }

    private fun sectionRow(context: Context, title: String) =
        TextView(context).apply {
            text = title.uppercase()
            textSize = 13f
            letterSpacing = 0.08f
            setTextColor(KojikiDialog.accentOf(context))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(context, 10), 0, dp(context, 3))
        }

    private fun dataRow(context: Context, r: Row): LinearLayout {
        val row = rowShell(context)
        row.addView(labelCell(context, r.label, accent = false))
        for (c in listOf(r.bdf, r.bu, r.ex)) {
            row.addView(glyphCell(context, c, glyphColor(context, c), 18f))
        }
        return row
    }

    private fun glyphColor(context: Context, glyph: String): Int {
        val fg = KojikiDialog.textOf(context)
        return when (glyph) {
            YES -> KojikiDialog.accentOf(context)
            NO -> KojikiDialog.withAlpha(fg, 0.45f)
            else -> KojikiDialog.withAlpha(fg, 0.28f)
        }
    }

    private fun labelCell(context: Context, text: String, accent: Boolean) =
        TextView(context).apply {
            this.text = text
            textSize = 15f
            setTextColor(
                if (accent) KojikiDialog.accentOf(context) else KojikiDialog.textOf(context))
            setPadding(0, 0, dp(context, 6), 0)
            layoutParams =
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

    private fun glyphCell(context: Context, text: String, color: Int, size: Float) =
        TextView(context).apply {
            this.text = text
            textSize = size
            gravity = Gravity.CENTER
            setTextColor(color)
            layoutParams =
                LinearLayout.LayoutParams(
                    dp(context, COL_DP), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
}
