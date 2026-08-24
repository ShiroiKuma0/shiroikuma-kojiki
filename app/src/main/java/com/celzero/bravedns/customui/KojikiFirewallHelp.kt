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
 * plainly: **both** bypasses waive "app not in use" and "device locked", and **neither** bypass
 * lifts the row's own WiFi/mobile-data toggles, which are tested earlier.
 *
 * Text lives here rather than in `strings.xml` — it is fork-only, English-only, and keeping it out
 * of upstream's translated resource file is one less rebase conflict. Button names still come from
 * resources so they track the rebrand.
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
            title(),
            listOf(KojikiDialog.Action(context.getString(R.string.dns_info_positive))),
            CONTENT_FRACTION) { box, _ ->
            // the pressed button leads; the other two follow, since the whole point is the contrast
            val order = listOf(focus) + Rule.entries.filter { it != focus }
            for (r in order) {
                box.addView(heading(context, context.getString(r.labelRes), r == focus))
                box.addView(para(context, blurb(context, r)))
                if (r == Rule.EXCLUDE) box.addView(KojikiDialog.helper(context, EXCLUDE_CAUTION))
            }

            box.addView(divider(context, 0.4f, 14))
            box.addView(heading(context, RULE_BY_RULE, false))
            box.addView(para(context, LEGEND))
            box.addView(table(context))

            box.addView(heading(context, NOTES_TITLE, false))
            for ((i, n) in NOTES.withIndex()) box.addView(note(context, i + 1, n))
            box.addView(divider(context, 0.4f, 14))
            box.addView(para(context, TAIL))
        }
    }

    // ---- content -----------------------------------------------------------------------------

    private fun blurb(context: Context, r: Rule): String {
        val app = context.getString(R.string.app_name)
        return when (r) {
            Rule.BYPASS_DNS_FIREWALL ->
                "Stays inside the tunnel — it still resolves through $app and still follows any " +
                    "WireGuard or proxy binding — but the DNS blocklists and every universal " +
                    "firewall rule are waived for it. The rules you set on this app itself still " +
                    "apply."
            Rule.BYPASS_UNIVERSAL ->
                "Stays inside the tunnel and keeps full DNS filtering — a blocklisted domain is " +
                    "still blocked — but the universal firewall rules are waived. The narrower of " +
                    "the two bypasses: it lifts the global toggles off the app without opening the " +
                    "blocklists."
            Rule.EXCLUDE ->
                "Removes the app from the VPN at the Android level. Its traffic never reaches " +
                    "$app at all: the system resolver answers its DNS, so no blocklists, no " +
                    "logging, no rules, and no WireGuard or proxy routing."
        }
    }

    private const val EXCLUDE_CAUTION =
        "On some Huawei/EMUI builds an excluded app loses name resolution entirely — the system " +
            "misroutes its queries — so prefer one of the bypasses there."

    private const val TITLE = "Firewall rules"

    /**
     * The dialog's own title, larger and underlined. Spanned rather than restyled: the title view
     * belongs to [KojikiDialog] and every fork dialog shares its 18sp, so the emphasis has to ride
     * on the text.
     */
    private fun title(): CharSequence =
        SpannableString(TITLE).apply {
            setSpan(RelativeSizeSpan(1.35f), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(UnderlineSpan(), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    private const val RULE_BY_RULE = "Rule by rule"

    private const val LEGEND =
        "✓ the rule still applies   ·   ✗ waived for this app   ·   – not applicable, the app is " +
            "outside the tunnel"

    private const val NOTES_TITLE = "Notes"

    private val NOTES =
        listOf(
            "The difference between the two bypasses. A Bypass Universal app still has its " +
                "blocklisted domains blocked — unless a universal domain rule marks that domain " +
                "Trust, the one universal rule that survives for it.",
            "“Prevent DNS leaks” keeps trapping port 53 for a Bypass Universal app. Bypass DNS & " +
                "Firewall escapes it.",
            "Both bypasses waive these two. Neither “not in use” nor “device locked” can block an " +
                "app carrying either status.",
            "The app’s own WiFi and mobile-data toggles are tested before either bypass, so they " +
                "still block. A bypass does not override the two toggles on this row.",
            "Under Android’s “block connections without VPN”, an excluded app would be left with " +
                "no connectivity at all, so the exclude list is ignored and the app is tunnelled " +
                "normally.")

    private const val TAIL =
        "Two rules run ahead of every status and cannot be bypassed by any of them: “block newly " +
            "installed apps” (only while the app is still untracked) and “block unknown " +
            "connections” (only for traffic with no app behind it)."

    private fun sections(context: Context): List<Section> {
        val app = context.getString(R.string.app_name)
        return listOf(
            Section(
                "DNS",
                listOf(
                    Row("Resolved by $app", YES, YES, NO),
                    Row("DNS blocklists ¹", NO, YES, NA),
                    Row("Blocked DNS enforced at connect ¹", NO, YES, NA),
                    Row("Prevent DNS leaks ²", NO, YES, NA),
                    Row("Disallow DNS bypass", NO, NO, NA),
                    Row("DNS logging + snoop tags", YES, YES, NO))),
            Section(
                "Universal firewall toggles",
                listOf(
                    Row("Block when app is not in use ³", NO, NO, NA),
                    Row("Block when device is locked ³", NO, NO, NA),
                    Row("Block metered connections", NO, NO, NA),
                    Row("Universal lockdown", NO, NO, NA),
                    Row("Block insecure HTTP", NO, NO, NA),
                    Row("Block UDP other than DNS/NTP", NO, NO, NA),
                    Row("Universal domain rules ¹", NO, NO, NA),
                    Row("Universal IP rules", NO, NO, NA))),
            Section(
                "Rules set on this app",
                listOf(
                    Row("Block on WiFi / mobile data ⁴", YES, YES, NA),
                    Row("Domain rules (Trust / Block)", YES, YES, NA),
                    Row("IP rules (Trust / Block)", YES, YES, NA))),
            Section(
                "Tunnel",
                listOf(
                    Row("Traffic enters the tunnel", YES, YES, NO),
                    Row("Connection logging", YES, YES, NO),
                    Row("WireGuard / proxy routing", YES, YES, NO),
                    Row("Honoured under Android lockdown ⁵", YES, YES, NO))))
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
        for (h in listOf("Bypass\nDNS & FW", "Bypass\nUniversal", "Exclude")) {
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
