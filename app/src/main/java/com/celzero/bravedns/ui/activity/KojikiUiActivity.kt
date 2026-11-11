/*
 * Copyright 2020 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.activity

import android.content.ClipData
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Outline
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.R
import com.celzero.bravedns.customui.ColorPickerDialog
import com.celzero.bravedns.customui.CustomUi
import com.celzero.bravedns.customui.CustomUiConfig
import com.celzero.bravedns.customui.KojikiDiag
import com.celzero.bravedns.customui.KojikiThreadDump
import com.celzero.bravedns.customui.KojikiDialog
import com.celzero.bravedns.customui.SnoopTagUi
import com.celzero.bravedns.databinding.ActivityKojikiUiBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.SnoopTagStore
import com.celzero.bravedns.ui.bottomsheet.ExportImportBottomSheet
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.customui.KojikiAlertDialogBuilder
import org.koin.android.ext.android.inject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fork (白い熊 考直): the 白い熊 考直 UI page — configure the foundation colours (background,
 * accent/icons, text) and a global font (family / weight / size). Applied app-wide at runtime by
 * [CustomUi] when the "Custom" theme is active. Rows are built programmatically (sections + indents)
 * to mirror the sister apps' layout without per-row layout files.
 */
class KojikiUiActivity : AppCompatActivity(R.layout.activity_kojiki_ui) {

    private val b by viewBinding(ActivityKojikiUiBinding::bind)
    private val persistentState by inject<PersistentState>()
    private lateinit var cfg: CustomUiConfig

    private val fontImportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> onFontImported(uri) }

    private val diagExportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree -> onDiagExportDirChosen(tree) }

    // All-Files-Access state the automation switch row was last drawn with; the grant happens in
    // system settings, so re-check on resume and repaint if it changed.
    private var renderedAllFilesAccess = false

    private companion object {
        const val KEY_SCROLL_Y = "kojiki_ui_scroll_y"
        const val WARN_COLOR = 0xFFFF5252.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        setSupportActionBar(b.kojikiUiToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        cfg = CustomUiConfig(this)
        buildRows()
        // Every control commits via recreate(); without this the page snaps back to the top on each
        // change. Restore the prior scroll position after the rows are laid out.
        val savedScroll = savedInstanceState?.getInt(KEY_SCROLL_Y, 0) ?: 0
        if (savedScroll > 0) {
            b.kojikiUiScroll.post { b.kojikiUiScroll.scrollTo(0, savedScroll) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from the system's All-files-access screen: repaint so the warning clears.
        if (persistentState.automationExportEnabled && hasAllFilesAccess() != renderedAllFilesAccess) {
            buildRows()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SCROLL_Y, b.kojikiUiScroll.scrollY)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun Context.isDarkThemeOn(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun indent(view: View, level: Int) {
        // Fork (白い熊 考直): tight spacing, matching 白い熊 Jami's settings page (2026-11-02) — the
        // section title sits at 36 dp and each level is 18 dp deeper (was 16 dp + 60 dp per level).
        val start = dp(36) + level * dp(18)
        view.setPaddingRelative(start, view.paddingTop, view.paddingEnd, view.paddingBottom)
    }

    private fun buildRows() {
        val holder = b.kojikiUiHolder
        holder.removeAllViews()

        // The page's link to the app's own settings: first, and the one thick pill above the
        // sections (black-yellow, settled 2026-11-07). CLEAR_TOP returns to the Settings screen this
        // page was opened from instead of stacking a second one.
        addActionRow(
            R.string.kojiki_ui_app_settings, getString(R.string.kojiki_ui_app_settings_desc),
            indentLevel = 0, main = true, opens = true
        ) {
            startActivity(Intent(this, MiscSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }

        // --- Export / Import (moved here from Settings) — kxkb-style: heading + a name/description
        // row beneath it that opens the category bottom sheet ---
        addSectionHeader(R.string.settings_import_export_heading)
        addActionRow(
            R.string.settings_import_export_heading,
            getString(R.string.settings_import_export_desc),
            indentLevel = 1, main = true, opens = true
        ) { openExportImport() }
        // The 保存復元 automation contract lives HERE, under the existing export rows — a backup
        // feature belongs where backup lives, and every sister app puts it in the same place.
        addAutomationRows()

        // --- Colours ---
        addSectionHeader(R.string.kojiki_ui_section_colors)
        addColorRow(R.string.kojiki_ui_color_background, cfg.backgroundColor) { cfg.backgroundColor = it; recreate() }
        addColorRow(R.string.kojiki_ui_color_surface, cfg.surfaceColor) { cfg.surfaceColor = it; recreate() }
        addColorRow(R.string.kojiki_ui_color_accent, cfg.accentColor) { cfg.accentColor = it; recreate() }
        addColorRow(R.string.kojiki_ui_color_text, cfg.textColor) { cfg.textColor = it; recreate() }
        addColorRow(R.string.kojiki_ui_switch, cfg.switchColor) { cfg.switchColor = it; recreate() }
        addColorRow(R.string.kojiki_ui_note_color, cfg.noteColor) { cfg.noteColor = it; recreate() }

        // --- Global font (family / weight / italic / size; applies to all text not overridden below) ---
        addSectionHeader(R.string.kojiki_ui_section_font)
        addFontControls(1,
            { cfg.fontFamily }, { cfg.fontFamily = it },
            { cfg.fontWeight }, { cfg.fontWeight = it },
            { cfg.fontItalic }, { cfg.fontItalic = it },
            { cfg.fontSize }, { cfg.fontSize = it },
            withImport = true)
        addSampleRow()

        // --- Firewall list: per-item colour + full font (family / weight / italic) + size ---
        addSectionHeader(R.string.kojiki_ui_section_fw)
        addFontGroup(R.string.kojiki_ui_fw_name_user, CustomUiConfig.P_FW_NAME_USER)
        addFontGroup(R.string.kojiki_ui_fw_name_system, CustomUiConfig.P_FW_NAME_SYSTEM)
        // The package id that trails the app name. Unset, it tracks the app name (dimmed, a touch
        // smaller) — so the swatch previews the dimmed user-app colour rather than plain yellow.
        addFontGroup(R.string.kojiki_ui_fw_app_id, CustomUiConfig.P_FW_APP_ID,
            CustomUi.dimmed(cfg.styleColor(CustomUiConfig.P_FW_NAME_USER, CustomUiConfig.PALETTE_YELLOW)))
        addFontGroup(R.string.kojiki_ui_fw_status, CustomUiConfig.P_FW_STATUS)
        addFontGroup(R.string.kojiki_ui_fw_traffic, CustomUiConfig.P_FW_TRAFFIC)
        addSubLabel(R.string.kojiki_ui_fw_toggle)
        addColorRow(R.string.kojiki_ui_fw_toggle_allowed, cfg.fwAllowedColor, indentLevel = 2) { cfg.fwAllowedColor = it; recreate() }
        addColorRow(R.string.kojiki_ui_fw_toggle_denied, cfg.fwDeniedColor, indentLevel = 2) { cfg.fwDeniedColor = it; recreate() }
        addColorRow(R.string.kojiki_ui_fw_toggle_excluded, cfg.fwExcludedColor, indentLevel = 2) { cfg.fwExcludedColor = it; recreate() }
        addColorRow(R.string.kojiki_ui_fw_toggle_bypass_dns, cfg.fwBypassDnsColor, indentLevel = 2) { cfg.fwBypassDnsColor = it; recreate() }
        addColorRow(R.string.kojiki_ui_fw_toggle_bypass_univ, cfg.fwBypassUnivColor, indentLevel = 2) { cfg.fwBypassUnivColor = it; recreate() }

        // --- Icons (firewall app list) — with a live preview ---
        addIconSection(R.string.kojiki_ui_section_icon,
            { cfg.iconSize }, { cfg.iconSize = it },
            { cfg.iconRoundness }, { cfg.iconRoundness = it })

        // --- Snooping panel: icon (preview) + severity pill + status text (live preview) ---
        addIconSection(R.string.kojiki_ui_section_snoop_icon,
            { cfg.snoopIconSize }, { cfg.snoopIconSize = it },
            { cfg.snoopIconRoundness }, { cfg.snoopIconRoundness = it })
        addSnoopPillControls()

        // --- Snooping tags: default chip style + manage created tags ---
        addSectionHeader(R.string.kojiki_ui_section_snoop_tags)
        addColorRow(R.string.kojiki_ui_snoop_tag_text, cfg.snoopTagTextColor) { cfg.snoopTagTextColor = it; recreate() }
        addColorRow(R.string.kojiki_ui_snoop_tag_border, cfg.snoopTagBorderColor) { cfg.snoopTagBorderColor = it; recreate() }
        addSliderRow(R.string.kojiki_ui_snoop_tag_border_width, cfg.snoopTagBorderWidth, CustomUiConfig.MAX_BORDER_DP, ::dpLabel) {
            cfg.snoopTagBorderWidth = it; recreate()
        }
        addSubLabel(R.string.kojiki_ui_snoop_tag_list)
        val snoopTags = SnoopTagStore.tags(this)
        if (snoopTags.isEmpty()) {
            addNote(getString(R.string.kojiki_ui_snoop_tag_none), 2)
        } else {
            val byTag = SnoopTagStore.domainsByTag(this)
            snoopTags.forEach { addTagManageRow(it, byTag[it.name].orEmpty()) }
        }
        addActionRow(R.string.snoop_tag_new, null, indentLevel = 2, opens = true) {
            SnoopTagUi.showNew(this) { recreate() }
        }

        // --- Network (connection) log: app icon (preview) + row spacing + text size ---
        addIconSection(R.string.kojiki_ui_section_conn_log_icon,
            { cfg.connLogIconSize }, { cfg.connLogIconSize = it },
            { cfg.connLogIconRoundness }, { cfg.connLogIconRoundness = it })
        addSliderRow(R.string.kojiki_ui_log_row_padding, cfg.connLogRowPadding, CustomUiConfig.MAX_ROW_PADDING_DP, ::dpDefaultLabel, defaultable = true) {
            cfg.connLogRowPadding = it; recreate()
        }
        addSliderRow(R.string.kojiki_ui_log_line_spacing, cfg.connLogLineSpacing, CustomUiConfig.MAX_ROW_PADDING_DP, ::dpDefaultLabel, defaultable = true) {
            cfg.connLogLineSpacing = it; recreate()
        }
        addTextSizePreview(R.string.kojiki_ui_log_text_size,
            { cfg.connLogTextSize }, { cfg.connLogTextSize = it })
        addSliderRow(R.string.kojiki_ui_log_divider_width, cfg.connLogDividerWidth, CustomUiConfig.MAX_DIVIDER_DP, ::dpDefaultLabel, defaultable = true) {
            cfg.connLogDividerWidth = it; recreate()
        }
        addColorRow(R.string.kojiki_ui_log_divider_color, cfg.connLogDividerColor) { cfg.connLogDividerColor = it; recreate() }

        // --- DNS log: app icon (preview) + row spacing + text size ---
        addIconSection(R.string.kojiki_ui_section_dns_log_icon,
            { cfg.dnsLogIconSize }, { cfg.dnsLogIconSize = it },
            { cfg.dnsLogIconRoundness }, { cfg.dnsLogIconRoundness = it })
        addSliderRow(R.string.kojiki_ui_log_row_padding, cfg.dnsLogRowPadding, CustomUiConfig.MAX_ROW_PADDING_DP, ::dpDefaultLabel, defaultable = true) {
            cfg.dnsLogRowPadding = it; recreate()
        }
        addSliderRow(R.string.kojiki_ui_log_line_spacing, cfg.dnsLogLineSpacing, CustomUiConfig.MAX_ROW_PADDING_DP, ::dpDefaultLabel, defaultable = true) {
            cfg.dnsLogLineSpacing = it; recreate()
        }
        addTextSizePreview(R.string.kojiki_ui_log_text_size,
            { cfg.dnsLogTextSize }, { cfg.dnsLogTextSize = it })
        addSliderRow(R.string.kojiki_ui_log_divider_width, cfg.dnsLogDividerWidth, CustomUiConfig.MAX_DIVIDER_DP, ::dpDefaultLabel, defaultable = true) {
            cfg.dnsLogDividerWidth = it; recreate()
        }
        addColorRow(R.string.kojiki_ui_log_divider_color, cfg.dnsLogDividerColor) { cfg.dnsLogDividerColor = it; recreate() }

        // --- Log status indicator (blocked/allowed bar + text tag), shared by both log tabs ---
        addSectionHeader(R.string.kojiki_ui_section_log_status)
        addSliderRow(R.string.kojiki_ui_log_status_width, cfg.logStatusBarWidth, CustomUiConfig.MAX_BORDER_DP, ::dpLabel) {
            cfg.logStatusBarWidth = it; recreate()
        }
        addColorRow(R.string.kojiki_ui_log_status_blocked, cfg.logStatusBlockedColor) { cfg.logStatusBlockedColor = it; recreate() }
        addColorRow(R.string.kojiki_ui_log_status_maybe, cfg.logStatusMaybeColor) { cfg.logStatusMaybeColor = it; recreate() }
        addColorRow(R.string.kojiki_ui_log_status_allowed, cfg.logStatusAllowedColor) { cfg.logStatusAllowedColor = it; recreate() }
        addToggleRow(R.string.kojiki_ui_log_tag_show, cfg.logTagShow, 1) { cfg.logTagShow = it; recreate() }
        addSliderRow(R.string.kojiki_ui_log_tag_size, cfg.logTagSize, CustomUiConfig.MAX_FONT_SIZE_SP, ::spLabel) {
            cfg.logTagSize = it; recreate()
        }

        // --- Popup menus (snoop row actions / sort / filter): border + item text ---
        addSectionHeader(R.string.kojiki_ui_section_menu)
        addColorRow(R.string.kojiki_ui_menu_border, cfg.menuBorderColor) { cfg.menuBorderColor = it; recreate() }
        addSliderRow(R.string.kojiki_ui_menu_border_width, cfg.menuBorderWidth, CustomUiConfig.MAX_BORDER_DP, ::dpLabel) {
            cfg.menuBorderWidth = it; recreate()
        }
        addFontGroup(R.string.kojiki_ui_menu_item, CustomUiConfig.P_SNOOP_MENU)

        // --- Cards (border applied to every card, app-wide) ---
        addSectionHeader(R.string.kojiki_ui_section_cards)
        addColorRow(R.string.kojiki_ui_card_border, cfg.cardBorderColor) { cfg.cardBorderColor = it; recreate() }
        addSliderRow(R.string.kojiki_ui_card_border_width, cfg.cardBorderWidth, CustomUiConfig.MAX_BORDER_DP, ::dpLabel) {
            cfg.cardBorderWidth = it; recreate()
        }

        // --- List dividers (lines between firewall rows) ---
        addSectionHeader(R.string.kojiki_ui_section_dividers)
        addColorRow(R.string.kojiki_ui_divider_color, cfg.dividerColor) { cfg.dividerColor = it; recreate() }
        addSliderRow(R.string.kojiki_ui_divider_thickness, cfg.dividerThickness, CustomUiConfig.MAX_DIVIDER_DP, ::dpLabel) {
            cfg.dividerThickness = it; recreate()
        }

        addActionRow(R.string.kojiki_ui_reset, null) {
            // acts in place, so it asks first (no ›)
            KojikiDialog.show(
                this, getString(R.string.kojiki_ui_reset),
                listOf(
                    KojikiDialog.Action(getString(android.R.string.cancel)),
                    KojikiDialog.Action(getString(R.string.kojiki_ui_reset)) { cfg.resetToDefaults(); recreate() }
                )
            ) { box, _ -> box.addView(noteText(getString(R.string.kojiki_ui_reset_confirm))) }
        }

        addDiagnosticsSection()
    }

    // ---- Diagnostics (2026-11-02) ----
    // Every fork diagnostic behind its own switch, all OFF by default (KojikiDiag): off means nothing
    // is recorded, formatted or timed. Each trace switch is followed by a row that shows the trace.
    private fun addDiagnosticsSection() {
        addSectionHeader(R.string.kojiki_diag_section)
        addNote(getString(R.string.kojiki_diag_intro, packageName), 1)
        addSubLabel(R.string.kojiki_diag_sub_app)
        addDiagToggle(R.string.kojiki_diag_leaks, R.string.kojiki_diag_leaks_desc, KojikiDiag.Group.LEAKS)
        addToggleRow(
            R.string.kojiki_diag_heap, KojikiDiag.isOn(KojikiDiag.Group.HEAP_DUMP), 2,
            desc = getString(R.string.kojiki_diag_heap_desc),
            tag = KojikiDiag.HPROF, tagNote = getString(R.string.kojiki_diag_tag_heap)
        ) {
            KojikiDiag.set(this, KojikiDiag.Group.HEAP_DUMP, it)
            // a dump is taken only when a leak is found, so it needs the leak watch on
            if (it && !KojikiDiag.isOn(KojikiDiag.Group.LEAKS)) {
                KojikiDiag.set(this, KojikiDiag.Group.LEAKS, true)
                recreate()
            }
        }
        addDiagView(KojikiDiag.Group.LEAKS, R.string.kojiki_diag_leaks)
        addSubLabel(R.string.kojiki_diag_sub_net)
        addDiagToggle(R.string.kojiki_diag_vpn, R.string.kojiki_diag_vpn_desc, KojikiDiag.Group.VPN)
        addDiagView(KojikiDiag.Group.VPN, R.string.kojiki_diag_vpn)
        addDiagToggle(R.string.kojiki_diag_dns, R.string.kojiki_diag_dns_desc, KojikiDiag.Group.DNS)
        addDiagView(KojikiDiag.Group.DNS, R.string.kojiki_diag_dns)
        addDiagToggle(R.string.kojiki_diag_wg, R.string.kojiki_diag_wg_desc, KojikiDiag.Group.WG)
        addDiagView(KojikiDiag.Group.WG, R.string.kojiki_diag_wg)
        addDiagToggle(R.string.kojiki_diag_threads, R.string.kojiki_diag_threads_desc, KojikiDiag.Group.THREADS)
        addActionRow(R.string.kojiki_diag_threads_now, getString(R.string.kojiki_diag_threads_now_desc), indentLevel = 2) {
            KojikiThreadDump.now("on demand")
            Toast.makeText(this, R.string.kojiki_diag_threads_done, Toast.LENGTH_SHORT).show()
        }
        addDiagView(KojikiDiag.Group.THREADS, R.string.kojiki_diag_threads)
        addDiagStoredRows()
    }

    // "Stored: …" under the switches, then Export and Purge (2026-11-03, after 白い熊 地図). The
    // summary reads every trace's stamps, so it is filled in from a worker thread.
    private fun addDiagStoredRows() {
        val stored = addNote(getString(R.string.kojiki_diag_stored_reading), 2)
        Thread({
            val s = KojikiDiag.summarize()
            runOnUiThread {
                stored.text =
                    if (s.stored.isEmpty()) getString(R.string.kojiki_diag_stored_none)
                    else getString(R.string.kojiki_diag_stored, diagFiles(s.stored.size, s.bytes) + ", " + diagSpan(s.first, s.last))
            }
        }, "kojiki-diag-summary").start()
        val last = KojikiDiag.exportTree(this)
        addActionRow(
            R.string.kojiki_diag_export,
            getString(R.string.kojiki_diag_export_desc, last?.let { treeLabel(it) } ?: "/sdcard/tmp"),
            indentLevel = 2, opens = true
        ) {
            if (KojikiDiag.files().isEmpty()) {
                Toast.makeText(this, R.string.kojiki_diag_stored_none, Toast.LENGTH_SHORT).show()
                return@addActionRow
            }
            val tree = KojikiDiag.exportTree(this)
            val initial =
                if (tree != null) {
                    android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
                } else {
                    android.provider.DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents", "primary:tmp")
                }
            diagExportLauncher.launch(initial)
        }
        addActionRow(R.string.kojiki_diag_purge, getString(R.string.kojiki_diag_purge_desc), indentLevel = 2) {
            confirmDiagPurge()
        }
    }

    private fun onDiagExportDirChosen(tree: Uri?) {
        tree ?: return
        val c = applicationContext
        // keep the grant, so the next export's picker can open on this directory
        runCatching {
            contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        KojikiDiag.setExportTree(c, tree)
        val where = treeLabel(tree)
        Thread({
            val message =
                try {
                    val (sub, n) = KojikiDiag.export(c, tree)
                    c.getString(R.string.kojiki_diag_export_done, n, "$where/$sub")
                } catch (e: Exception) {
                    c.getString(R.string.kojiki_diag_export_failed, e.message.toString())
                }
            runOnUiThread {
                Toast.makeText(c, message, Toast.LENGTH_LONG).show()
                if (!isDestroyed) recreate()
            }
        }, "kojiki-diag-export").start()
    }

    // The warning lists every file with its size and span, then each day's span, then the total —
    // so what "forever" covers is in front of 白い熊 before anything goes.
    private fun confirmDiagPurge() {
        Thread({
            val s = KojikiDiag.summarize()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (s.stored.isEmpty()) {
                    Toast.makeText(this, R.string.kojiki_diag_stored_none, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                showDiagPurgeDialog(s)
            }
        }, "kojiki-diag-summary").start()
    }

    private fun showDiagPurgeDialog(s: KojikiDiag.Summary) {
        val msg = StringBuilder()
        for (f in s.stored) {
            msg.append(f.file.name).append("  ").append(diagSize(f.bytes)).append("\n    ")
                .append(diagSpan(f.first, f.last)).append("\n")
        }
        msg.append("\n")
        val day = SimpleDateFormat("EEE yyyy-MM-dd", Locale.US)
        val time = SimpleDateFormat("HH:mm", Locale.US)
        for (d in s.days.values) {
            msg.append(day.format(Date(d[0]))).append("  ").append(time.format(Date(d[0]))).append("–")
                .append(time.format(Date(d[1]))).append("\n")
        }
        msg.append("\n").append(diagFiles(s.stored.size, s.bytes)).append(", ").append(diagSpan(s.first, s.last))
            .append(".")
        KojikiDialog.show(
            this, getString(R.string.kojiki_diag_purge_title),
            listOf(
                KojikiDialog.Action(getString(android.R.string.cancel)),
                KojikiDialog.Action(getString(R.string.kojiki_diag_purge_button)) {
                    val c = applicationContext
                    KojikiDiag.purge { n ->
                        runOnUiThread {
                            Toast.makeText(c, c.getString(R.string.kojiki_diag_purge_done, n), Toast.LENGTH_SHORT).show()
                            if (!isDestroyed) recreate()
                        }
                    }
                }
            ),
            0.78f
        ) { box, _ ->
            box.addView(TextView(this).apply {
                text = msg.toString()
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextColor(KojikiDialog.textOf(this@KojikiUiActivity))
            })
        }
    }

    private fun diagSize(bytes: Long): String =
        if (bytes < 1024 * 1024) String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else String.format(Locale.US, "%.1f MB", bytes / 1048576.0)

    private fun diagFiles(count: Int, bytes: Long): String =
        String.format(Locale.US, "%d %s, %.1f MB", count, if (count == 1) "file" else "files", bytes / 1048576.0)

    private fun diagSpan(first: Long, last: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return f.format(Date(first)) + " – " + f.format(Date(last))
    }

    // "/sdcard/tmp" for a tree on the phone's own storage, else the tree's document id.
    private fun treeLabel(tree: Uri): String =
        try {
            val id = android.provider.DocumentsContract.getTreeDocumentId(tree)
            if (id.startsWith("primary:")) "/sdcard/" + id.removePrefix("primary:") else id
        } catch (_: Exception) {
            tree.toString()
        }

    private fun addDiagToggle(@StringRes labelRes: Int, @StringRes descRes: Int, g: KojikiDiag.Group) {
        addToggleRow(
            labelRes, KojikiDiag.isOn(g), 2, desc = getString(descRes),
            tag = g.file, tagNote = getString(R.string.kojiki_diag_tag_trace)
        ) {
            KojikiDiag.set(this, g, it)
        }
    }

    private fun addDiagView(g: KojikiDiag.Group, @StringRes titleRes: Int) {
        addActionRow(R.string.kojiki_diag_view, null, indentLevel = 2, opens = true) {
            showDiagTrace(g, getString(titleRes))
        }
    }

    private fun showDiagTrace(g: KojikiDiag.Group, title: String) {
        val text = KojikiDiag.tail(g).ifEmpty { getString(R.string.kojiki_diag_view_empty) }
        KojikiDialog.show(
            this, title,
            listOf(
                KojikiDialog.Action(getString(R.string.kojiki_diag_clear), leading = true) { KojikiDiag.clear(g) },
                KojikiDialog.Action(getString(R.string.kojiki_diag_close))
            ),
            0.78f
        ) { box, _ ->
            box.addView(TextView(this).apply {
                this.text = text
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setTextColor(KojikiDialog.textOf(this@KojikiUiActivity))
                setTextIsSelectable(true)
            })
        }
    }

    // Icon size + roundness with a live preview (the app's own icon) that updates as the sliders move.
    // Shared by the firewall-list icon and the snooping-panel icon — each passes its own config + header.
    private fun addIconSection(
        @StringRes headerRes: Int,
        getSize: () -> Int, setSize: (Int) -> Unit,
        getRound: () -> Int, setRound: (Int) -> Unit
    ) {
        addSectionHeader(headerRes)

        val previewDefaultDp = 40
        var liveSize = getSize()
        var liveRound = getRound()

        val preview = AppCompatImageView(this).apply {
            setImageDrawable(packageManager.getApplicationIcon(applicationInfo))
        }
        fun applyPreview() {
            val sizePx = dp(if (liveSize > 0) liveSize else previewDefaultDp)
            preview.layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            val pct = liveRound.coerceIn(0, 100) / 100f
            if (pct > 0f) {
                preview.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        val r = minOf(view.width, view.height) / 2f * pct
                        outline.setRoundRect(0, 0, view.width, view.height, r)
                    }
                }
                preview.clipToOutline = true
            } else {
                preview.clipToOutline = false
            }
            preview.invalidateOutline()
        }
        val previewBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), dp(16), dp(8))
            addView(preview)
        }
        indent(previewBox, 1)
        b.kojikiUiHolder.addView(previewBox)
        applyPreview()

        addSliderRow(R.string.kojiki_ui_icon_size, getSize(), CustomUiConfig.MAX_ICON_SIZE_DP, ::dpLabel,
            onChange = { liveSize = it; applyPreview() }) { setSize(it); recreate() }
        addSliderRow(R.string.kojiki_ui_icon_roundness, getRound(), 100, ::pctLabel,
            onChange = { liveRound = it; applyPreview() }) { setRound(it); recreate() }
    }

    // A text-size slider with a live preview line that re-renders at the chosen size, in the global
    // font + text colour (which is what the log rows use), so you can see the row text size as you
    // drag. 0 sp = follow the global font size, and the preview then mirrors that.
    private fun addTextSizePreview(
        @StringRes labelRes: Int, getSize: () -> Int, setSize: (Int) -> Unit
    ) {
        var liveSize = getSize()
        val preview = AppCompatTextView(this).apply {
            // opt out of the runtime tree-walk so it doesn't reset the preview to the global size.
            tag = CustomUi.NO_RESTYLE_TAG
            text = getString(R.string.kojiki_ui_log_text_sample)
            setTextColor(cfg.textColor)
            typeface = CustomUi.typefaceFor(this@KojikiUiActivity, cfg.fontFamily, cfg.fontWeight, cfg.fontItalic)
        }
        fun applyPreview() {
            val sp = if (liveSize > 0) liveSize else if (cfg.fontSize > 0) cfg.fontSize else 14
            preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), dp(16), dp(8))
            addView(preview)
        }
        indent(box, 1)
        b.kojikiUiHolder.addView(box)
        applyPreview()
        addSliderRow(labelRes, getSize(), CustomUiConfig.MAX_FONT_SIZE_SP, ::spLabel,
            onChange = { liveSize = it; applyPreview() }) { setSize(it); recreate() }
    }

    // Live preview of a snooping-row's right column — the severity pill + the status text under it —
    // updating as the pill-size / status-font sliders move (mirrors the icon preview). Uses sample
    // HIGH / blocked content (localised) with the panel's own severity/state colours.
    private fun addSnoopPillControls() {
        var livePill = cfg.snoopPillSize
        var livePillWeight = cfg.styleWeight(CustomUiConfig.P_SNOOP_PILL)
        var livePillWidth = cfg.snoopPillWidth
        var livePillRadius = cfg.snoopPillRadius
        var livePillBorderWidth = cfg.snoopPillBorderWidth
        var liveStateWeight = cfg.styleWeight(CustomUiConfig.P_SNOOP_STATE)
        var liveStateSize = cfg.styleSize(CustomUiConfig.P_SNOOP_STATE)

        val previewBadge = AppCompatTextView(this).apply {
            // opt out of the runtime tree-walk so it doesn't override our pill size/white-on-red with
            // the global font/accent (that's exactly what was nullifying the slider).
            tag = CustomUi.NO_RESTYLE_TAG
            text = getString(R.string.snoop_sev_high)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        val previewState = AppCompatTextView(this).apply {
            tag = CustomUi.NO_RESTYLE_TAG
            text = getString(R.string.snoop_state_blocked)
            setTextColor(0xFF2B8E18.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        fun applyPreview() {
            // Match the real list pill exactly: keep the bg drawable's own padding, let text size
            // drive the pill size. (The page now preserves scroll across recreate(), so this live
            // update is visible — earlier it was masked by the jump-to-top on slider release.)
            val pillFamily = cfg.styleFamily(CustomUiConfig.P_SNOOP_PILL).ifEmpty { cfg.fontFamily }
            val pillWeight = if (livePillWeight > 0) livePillWeight else cfg.fontWeight
            previewBadge.typeface =
                CustomUi.typefaceFor(this@KojikiUiActivity, pillFamily, pillWeight, cfg.styleItalic(CustomUiConfig.P_SNOOP_PILL))
            previewBadge.setTextColor(cfg.styleColor(CustomUiConfig.P_SNOOP_PILL, CustomUiConfig.SNOOP_WHITE))
            previewBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, (if (livePill > 0) livePill else 11).toFloat())
            previewBadge.background =
                CustomUi.snoopPillBackground(
                    this@KojikiUiActivity, 0xFFFF1744.toInt(), livePillRadius, livePillBorderWidth, cfg.snoopPillBorderColor
                )
            previewBadge.setPadding(dp(6), dp(2), dp(6), dp(2))
            previewBadge.minimumWidth = dp(livePillWidth)
            val family = cfg.styleFamily(CustomUiConfig.P_SNOOP_STATE).ifEmpty { cfg.fontFamily }
            val weight = if (liveStateWeight > 0) liveStateWeight else cfg.fontWeight
            previewState.typeface =
                CustomUi.typefaceFor(this@KojikiUiActivity, family, weight, cfg.styleItalic(CustomUiConfig.P_SNOOP_STATE))
            previewState.setTextSize(TypedValue.COMPLEX_UNIT_SP, (if (liveStateSize > 0) liveStateSize else 11).toFloat())
            previewState.setTextColor(cfg.snoopStateBlockedColor)
            previewBadge.requestLayout()
            previewState.requestLayout()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), dp(16), dp(8))
        }
        // WRAP_CONTENT (centred) so the pill renders at its true width, not stretched full-width
        // (a vertical LinearLayout defaults children to MATCH_PARENT).
        fun centeredWrap() =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        box.addView(previewBadge, centeredWrap())
        box.addView(previewState, centeredWrap())
        indent(box, 1)
        b.kojikiUiHolder.addView(box)
        applyPreview()

        addSubLabel(R.string.kojiki_ui_snoop_pill)
        addSliderRow(R.string.kojiki_ui_snoop_pill_size, cfg.snoopPillSize, CustomUiConfig.MAX_FONT_SIZE_SP, ::spLabel, 2,
            onChange = { livePill = it; applyPreview() }) { cfg.snoopPillSize = it; recreate() }
        addColorRow(R.string.kojiki_ui_snoop_pill_color, cfg.styleColor(CustomUiConfig.P_SNOOP_PILL, CustomUiConfig.SNOOP_WHITE), indentLevel = 2) {
            cfg.setStyleColor(CustomUiConfig.P_SNOOP_PILL, it); recreate()
        }
        addFontRow(R.string.kojiki_ui_item_font, cfg.styleFamily(CustomUiConfig.P_SNOOP_PILL), false, 2) {
            cfg.setStyleFamily(CustomUiConfig.P_SNOOP_PILL, it); recreate()
        }
        addSliderRow(R.string.kojiki_ui_item_weight, cfg.styleWeight(CustomUiConfig.P_SNOOP_PILL), CustomUiConfig.MAX_WEIGHT, ::weightLabel, 2,
            onChange = { livePillWeight = it; applyPreview() }) { cfg.setStyleWeight(CustomUiConfig.P_SNOOP_PILL, it); recreate() }
        addToggleRow(R.string.kojiki_ui_item_italic, cfg.styleItalic(CustomUiConfig.P_SNOOP_PILL), 2) {
            cfg.setStyleItalic(CustomUiConfig.P_SNOOP_PILL, it); recreate()
        }
        addSliderRow(R.string.kojiki_ui_snoop_pill_width, cfg.snoopPillWidth, CustomUiConfig.MAX_PILL_WIDTH_DP, ::dpLabel, 2,
            onChange = { livePillWidth = it; applyPreview() }) { cfg.snoopPillWidth = it; recreate() }
        addSliderRow(R.string.kojiki_ui_snoop_pill_roundness, cfg.snoopPillRadius, CustomUiConfig.MAX_PILL_RADIUS_DP, ::dpLabel, 2,
            onChange = { livePillRadius = it; applyPreview() }) { cfg.snoopPillRadius = it; recreate() }
        addColorRow(R.string.kojiki_ui_snoop_pill_border, cfg.snoopPillBorderColor, indentLevel = 2) {
            cfg.snoopPillBorderColor = it; recreate()
        }
        addSliderRow(R.string.kojiki_ui_snoop_pill_border_width, cfg.snoopPillBorderWidth, CustomUiConfig.MAX_BORDER_DP, ::dpLabel, 2,
            onChange = { livePillBorderWidth = it; applyPreview() }) { cfg.snoopPillBorderWidth = it; recreate() }

        addSubLabel(R.string.kojiki_ui_snoop_state)
        addColorRow(R.string.kojiki_ui_snoop_state_blocked, cfg.snoopStateBlockedColor, indentLevel = 2) {
            cfg.snoopStateBlockedColor = it; recreate()
        }
        addColorRow(R.string.kojiki_ui_snoop_state_allowed, cfg.snoopStateAllowedColor, indentLevel = 2) {
            cfg.snoopStateAllowedColor = it; recreate()
        }
        addFontRow(R.string.kojiki_ui_item_font, cfg.styleFamily(CustomUiConfig.P_SNOOP_STATE), false, 2) {
            cfg.setStyleFamily(CustomUiConfig.P_SNOOP_STATE, it); recreate()
        }
        addSliderRow(R.string.kojiki_ui_item_weight, cfg.styleWeight(CustomUiConfig.P_SNOOP_STATE), CustomUiConfig.MAX_WEIGHT, ::weightLabel, 2,
            onChange = { liveStateWeight = it; applyPreview() }) { cfg.setStyleWeight(CustomUiConfig.P_SNOOP_STATE, it); recreate() }
        addToggleRow(R.string.kojiki_ui_item_italic, cfg.styleItalic(CustomUiConfig.P_SNOOP_STATE), 2) {
            cfg.setStyleItalic(CustomUiConfig.P_SNOOP_STATE, it); recreate()
        }
        addSliderRow(R.string.kojiki_ui_item_size, cfg.styleSize(CustomUiConfig.P_SNOOP_STATE), CustomUiConfig.MAX_FONT_SIZE_SP, ::spLabel, 2,
            onChange = { liveStateSize = it; applyPreview() }) { cfg.setStyleSize(CustomUiConfig.P_SNOOP_STATE, it); recreate() }

        addSliderRow(R.string.kojiki_ui_snoop_row_padding, cfg.snoopRowPadding, CustomUiConfig.MAX_ROW_PADDING_DP, ::dpLabel) {
            cfg.snoopRowPadding = it; recreate()
        }
    }

    /** A firewall-list text item: sub-label + colour + full font controls (family / weight / italic / size). */
    private fun addFontGroup(
        @StringRes labelRes: Int, prefix: String, defColor: Int = CustomUiConfig.PALETTE_YELLOW
    ) {
        addSubLabel(labelRes)
        addColorRow(R.string.kojiki_ui_item_colour, cfg.styleColor(prefix, defColor), indentLevel = 2) {
            cfg.setStyleColor(prefix, it); recreate()
        }
        addFontControls(2,
            { cfg.styleFamily(prefix) }, { cfg.setStyleFamily(prefix, it) },
            { cfg.styleWeight(prefix) }, { cfg.setStyleWeight(prefix, it) },
            { cfg.styleItalic(prefix) }, { cfg.setStyleItalic(prefix, it) },
            { cfg.styleSize(prefix) }, { cfg.setStyleSize(prefix, it) },
            withImport = false)
    }

    /** Font family + weight slider + italic toggle + size slider (colour is added by the caller). */
    private fun addFontControls(
        indentLevel: Int,
        getFamily: () -> String, setFamily: (String) -> Unit,
        getWeight: () -> Int, setWeight: (Int) -> Unit,
        getItalic: () -> Boolean, setItalic: (Boolean) -> Unit,
        getSize: () -> Int, setSize: (Int) -> Unit,
        withImport: Boolean
    ) {
        addFontRow(R.string.kojiki_ui_item_font, getFamily(), withImport, indentLevel) { setFamily(it); recreate() }
        addSliderRow(R.string.kojiki_ui_item_weight, getWeight(), CustomUiConfig.MAX_WEIGHT, ::weightLabel, indentLevel) {
            setWeight(it); recreate()
        }
        addToggleRow(R.string.kojiki_ui_item_italic, getItalic(), indentLevel) { setItalic(it); recreate() }
        addSliderRow(R.string.kojiki_ui_item_size, getSize(), CustomUiConfig.MAX_FONT_SIZE_SP, ::spLabel, indentLevel) {
            setSize(it); recreate()
        }
    }

    // A switch row. With an explanation it is the black-yellow explained item: bold accent label,
    // an optional tag line (bold monospace tag · dim-gold qualifier, only where the item has a real
    // identifier, e.g. its trace file), the description in dim gold, and a dim-gold rule down the
    // left of the whole block. Minimal padding; the switch carries no 48 dp minimum.
    private fun addToggleRow(
        @StringRes labelRes: Int, current: Boolean, indentLevel: Int,
        desc: String? = null, descColor: Int? = null, onRowClick: (() -> Unit)? = null,
        tag: String? = null, tagNote: String? = null,
        onChange: (Boolean) -> Unit
    ) {
        val sw = androidx.appcompat.widget.SwitchCompat(this).apply {
            isChecked = current
            minHeight = 0
            minimumHeight = 0
        }
        val explained = !desc.isNullOrEmpty()
        val labelBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(itemLabel(getString(labelRes), explained))
            if (!tag.isNullOrEmpty()) addView(tagLine(tag, tagNote))
            if (explained) addView(noteText(desc!!, descColor))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(labelBox)
            addView(sw)
            if (onRowClick != null) {
                background = selectableBackground()
                isClickable = true
                setOnClickListener { onRowClick() }
            }
        }
        sw.setOnCheckedChangeListener { _, c -> onChange(c) }
        addItem(row, indentLevel, ruled = explained, padV = 2)
    }

    // Something to press (black-yellow, settled 2026-11-07; 2026-11-09 the pill hugs its label —
    // a short action stretched across the line read as a banner): an outlined pill, never
    // filled — 3 dp frame + bold for the section's main action, 1.25 dp otherwise — with › when it
    // opens a sheet, picker or dialog, none when it acts in place. The explanation sits beneath the
    // pill in dim gold, aligned with the label, and the rule spans pill + explanation.
    private fun addActionRow(
        @StringRes labelRes: Int, desc: String?, indentLevel: Int = 1,
        main: Boolean = false, opens: Boolean = false, onClick: () -> Unit
    ) {
        val accent = cfg.accentColor
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(9), dp(16), dp(9))
            val frame = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(maxOf(1, (if (main) 3f else 1.25f).let { (it * resources.displayMetrics.density).toInt() }), accent)
                cornerRadius = dp(50).toFloat()
            }
            background = RippleDrawable(ColorStateList.valueOf(KojikiDialog.withAlpha(accent, 0.3f)), frame, null)
            isClickable = true
            setOnClickListener { onClick() }
            addView(AppCompatTextView(this@KojikiUiActivity).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                text = getString(labelRes)
                setTextColor(accent)
                if (main) setTypeface(typeface, Typeface.BOLD)
            })
            if (opens) {
                addView(AppCompatTextView(this@KojikiUiActivity).apply {
                    text = "\u203A"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    includeFontPadding = false
                    setTextColor(accent)
                    if (main) setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also { it.marginStart = dp(10) }
                })
            }
        }
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (!desc.isNullOrEmpty()) addView(noteText(desc).apply { setPadding(dp(18), dp(3), 0, 0) })
        }
        addItem(block, indentLevel, ruled = !desc.isNullOrEmpty(), padV = 3)
    }

    // Places a row: indented, minimal vertical padding, and — for an explained item — a 2 dp
    // dim-gold rule down its whole left edge, 10 dp before the text.
    private fun addItem(content: View, indentLevel: Int, ruled: Boolean, padV: Int) {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(padV), dp(16), dp(padV))
            if (ruled) {
                addView(View(this@KojikiUiActivity).apply { setBackgroundColor(cfg.noteColor) },
                    LinearLayout.LayoutParams(dp(2), ViewGroup.LayoutParams.MATCH_PARENT).also { it.marginEnd = dp(10) })
            }
            addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        indent(wrap, indentLevel)
        b.kojikiUiHolder.addView(wrap)
    }

    // The label of an explained item is bold accent, so its bold tag line never outweighs it; a
    // plain row keeps the ordinary text colour and weight.
    private fun itemLabel(text: String, explained: Boolean) = AppCompatTextView(this).apply {
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        this.text = text
        if (explained) {
            setTextColor(cfg.accentColor)
            setTypeface(typeface, Typeface.BOLD)
        } else {
            setTextColor(cfg.textColor)
        }
    }

    private fun tagLine(tag: String, note: String?) = AppCompatTextView(this).apply {
        val sb = android.text.SpannableStringBuilder(tag)
        sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, tag.length, 0)
        sb.setSpan(android.text.style.TypefaceSpan("monospace"), 0, tag.length, 0)
        sb.setSpan(android.text.style.ForegroundColorSpan(cfg.accentColor), 0, tag.length, 0)
        if (!note.isNullOrEmpty()) sb.append("  ·  ").append(note)
        text = sb
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(cfg.noteColor)
        setPadding(0, dp(1), 0, 0)
    }

    // Explanations are never grey: dim gold, 13 sp (a warning keeps its own colour).
    private fun noteText(text: String, color: Int? = null) = AppCompatTextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(color ?: cfg.noteColor)
        setPadding(0, dp(1), dp(8), 0)
    }

    // A loose note that belongs to no single item (a section intro, the Stored line): dim gold,
    // no rule.
    private fun addNote(text: String, indentLevel: Int): AppCompatTextView {
        val note = noteText(text)
        note.setPadding(0, dp(4), dp(16), dp(2))
        indent(note, indentLevel)
        b.kojikiUiHolder.addView(note)
        return note
    }

    private fun weightLabel(v: Int): String =
        if (v > 0) v.toString() else getString(R.string.kojiki_ui_size_default)

    // kxkb-style subgroup header: 16 sp medium accent title with a SHORT underline matching the
    // text width, controls one indent level deeper — mirrors KxkbSubgroup in the futokxkb
    // Keyboard UI page.
    private fun addSubLabel(@StringRes labelRes: Int) {
        val label = AppCompatTextView(this).apply {
            text = getString(labelRes)
            setTextColor(cfg.accentColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            maxLines = 1
        }
        val underline = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(textWidth(label),
                (1.5f * resources.displayMetrics.density).toInt()).also {
                it.topMargin = dp(2)
            }
            setBackgroundColor(cfg.accentColor)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(2))
            addView(label)
            addView(underline)
        }
        indent(box, 1)
        b.kojikiUiHolder.addView(box)
    }

    // Thin neutral full-width hairline separating one section from the next (kxkb-style page
    // rhythm) — edge to edge, subdued so it stays subordinate to the accent underlines.
    private fun addSectionSpacer() {
        val dark = androidx.core.graphics.ColorUtils.calculateLuminance(cfg.backgroundColor) < 0.5
        val line = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).also {
                it.topMargin = dp(10)
            }
            setBackgroundColor(if (dark) 0x59FFFFFF else 0x59000000)
        }
        b.kojikiUiHolder.addView(line)
    }

    // The exact width of a label's text, so an underline can be sized to the TEXT and not stretch
    // the box to full width (a MATCH_PARENT sibling would).
    private fun textWidth(label: AppCompatTextView): Int {
        label.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        return label.measuredWidth
    }

    // kxkb-style section header: 20 sp bold accent title with a 2 dp underline matching the text
    // width; sections separated by thin spacers.
    private fun addSectionHeader(@StringRes labelRes: Int) {
        if (b.kojikiUiHolder.childCount > 0) addSectionSpacer()
        val label = AppCompatTextView(this).apply {
            text = getString(labelRes)
            setTextColor(cfg.accentColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            maxLines = 1
        }
        val underline = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(textWidth(label), dp(2)).also {
                it.topMargin = dp(2)
            }
            setBackgroundColor(cfg.accentColor)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), dp(8), dp(16), dp(2))
            addView(label)
            addView(underline)
        }
        b.kojikiUiHolder.addView(box)
    }

    private fun addColorRow(@StringRes labelRes: Int, color: Int, indentLevel: Int = 1, onPick: (Int) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = selectableBackground()
            setPadding(0, dp(2), dp(16), dp(2))
            isClickable = true
            setOnClickListener { ColorPickerDialog.show(this@KojikiUiActivity, color, onPick) }
        }
        val label = AppCompatTextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            text = getString(labelRes)
            setTextColor(cfg.textColor)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            setBackgroundColor(color)
        }
        row.addView(label)
        row.addView(swatch)
        indent(row, indentLevel)
        b.kojikiUiHolder.addView(row)
    }

    // A manage-able Snoop tag row: name + effective-colour swatch; tap → rename / recolour / delete.
    private fun addTagManageRow(tag: SnoopTagStore.Tag, domains: List<String>) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = selectableBackground()
            setPadding(0, dp(2), dp(16), dp(2))
            isClickable = true
            setOnClickListener { SnoopTagUi.showEdit(this@KojikiUiActivity, tag) { recreate() } }
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(AppCompatTextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            text = tag.name
            setTextColor(cfg.textColor)
        })
        // Show the assigned domains so tags + assignments are verifiable here (independent of the
        // live Snooping panel, which may be empty on a fresh install / after import).
        textCol.addView(AppCompatTextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            text = if (domains.isEmpty()) getString(R.string.kojiki_ui_snoop_tag_unassigned)
            else getString(R.string.kojiki_ui_snoop_tag_assigned, domains.size, domains.joinToString(", "))
            setTextColor(cfg.noteColor) // explanations are dim gold, never grey
        })
        val swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            setBackgroundColor(tag.color ?: cfg.snoopTagBorderColor)
        }
        row.addView(textCol)
        row.addView(swatch)
        indent(row, 2)
        b.kojikiUiHolder.addView(row)
    }

    private fun addValueRow(@StringRes labelRes: Int, value: String, indentLevel: Int = 1, onClick: () -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = selectableBackground()
            setPadding(0, dp(2), dp(16), dp(2))
            isClickable = true
            setOnClickListener { onClick() }
        }
        box.addView(AppCompatTextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            text = getString(labelRes)
            setTextColor(cfg.textColor)
        })
        if (value.isNotEmpty()) {
            box.addView(AppCompatTextView(this).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                text = value
                setTextColor(cfg.noteColor) // dim gold, never grey
                setPadding(0, dp(1), 0, 0)
            })
        }
        indent(box, indentLevel)
        b.kojikiUiHolder.addView(box)
    }

    @Suppress("EmptyFunctionBlock")
    private fun addSliderRow(
        @StringRes labelRes: Int, current: Int, max: Int, labeler: (Int) -> String,
        indentLevel: Int = 1, defaultable: Boolean = false, onChange: (Int) -> Unit = {}, onSet: (Int) -> Unit
    ) {
        // defaultable: add one slot to the left so the track reads "Default, 0, 1, …" — i.e. progress 0
        // maps to value -1 ("Default"), progress 1 to an explicit 0, and so on. labeler gets the value.
        val offset = if (defaultable) 1 else 0
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), dp(16), dp(2))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val label = AppCompatTextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            text = getString(labelRes)
            setTextColor(cfg.textColor)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val value = AppCompatTextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            text = labeler(current)
            setTextColor(cfg.textColor)
        }
        header.addView(label)
        header.addView(value)
        val seek = SeekBar(this).apply {
            this.max = max + offset
            progress = (current + offset).coerceIn(0, max + offset)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val v = p - offset
                    value.text = labeler(v)
                    onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    onSet(sb.progress - offset)
                }
            })
        }
        box.addView(header)
        box.addView(seek)
        indent(box, indentLevel)
        b.kojikiUiHolder.addView(box)
    }

    private fun addFontRow(
        @StringRes labelRes: Int, current: String, withImport: Boolean, indentLevel: Int = 1,
        onPicked: (String) -> Unit
    ) {
        addValueRow(labelRes, CustomUi.fontDisplayName(this, current), indentLevel) {
            showFontPicker(current, withImport, onPicked)
        }
    }

    private fun addSampleRow() {
        val sample = AppCompatTextView(this).apply {
            text = getString(R.string.kojiki_ui_font_sample)
            setTextColor(cfg.textColor)
            typeface = CustomUi.typefaceFor(this@KojikiUiActivity, cfg.fontFamily, cfg.fontWeight, cfg.fontItalic)
            if (cfg.fontSize > 0) setTextSize(TypedValue.COMPLEX_UNIT_SP, cfg.fontSize.toFloat())
            setPadding(0, dp(4), dp(16), dp(8))
        }
        indent(sample, 1)
        b.kojikiUiHolder.addView(sample)
    }

    private fun spLabel(v: Int): String =
        if (v > 0) getString(R.string.kojiki_ui_size_sp, v) else getString(R.string.kojiki_ui_size_default)

    private fun dpLabel(v: Int): String =
        if (v > 0) getString(R.string.kojiki_ui_size_dp, v) else getString(R.string.kojiki_ui_size_default)

    // Like dpLabel but with an explicit, selectable 0: -1 = "Default", 0 = "0 dp", n = "n dp".
    private fun dpDefaultLabel(v: Int): String =
        if (v < 0) getString(R.string.kojiki_ui_size_default) else getString(R.string.kojiki_ui_size_dp, v)

    private fun pctLabel(v: Int): String =
        if (v > 0) getString(R.string.kojiki_ui_pct, v) else getString(R.string.kojiki_ui_size_default)

    private fun selectableBackground(): android.graphics.drawable.Drawable? {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return androidx.core.content.ContextCompat.getDrawable(this, tv.resourceId)
    }

    private fun showFontPicker(current: String, withImport: Boolean, onPicked: (String) -> Unit) {
        val fonts = CustomUi.availableFonts(this)
        val names = fonts.map { it.displayName }.toTypedArray()
        val sel = fonts.indexOfFirst { it.value == current }.coerceAtLeast(0)
        val builder = KojikiAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle(R.string.kojiki_ui_font_family)
            .setSingleChoiceItems(names, sel) { d, which ->
                onPicked(fonts[which].value)
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
        // Import adds a font to the shared list (then any item can select it); only the global row offers it.
        if (withImport) {
            builder.setNeutralButton(R.string.kojiki_ui_font_import) { _, _ ->
                fontImportLauncher.launch(arrayOf("*/*"))
            }
        }
        builder.show()
    }

    private fun showWeightPicker() {
        val names = CustomUi.weightOptions.map { getString(it.labelRes) }.toTypedArray()
        val current = CustomUi.weightOptions.indexOfFirst { it.value == cfg.fontWeight }.coerceAtLeast(0)
        KojikiAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle(R.string.kojiki_ui_font_weight)
            .setSingleChoiceItems(names, current) { d, which ->
                cfg.fontWeight = CustomUi.weightOptions[which].value
                d.dismiss()
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // -------------------------------------------------------------------------------------------
    // 保存復元 automation (the sister-app contract v2) — three rows under Export / Import
    // -------------------------------------------------------------------------------------------

    /**
     * The master switch, the token switch, and — only when the token is actually being asked for —
     * the token row. Appended directly below the Export / Import entry, never a section of its own:
     * this is a backup feature, so it lives where backup lives, the same place in every sister app.
     * See [com.celzero.bravedns.receiver.StateExportReceiver] (§1) and
     * [com.celzero.bravedns.automation.AutomationProvider] (§2a).
     *
     * Contract v2 ordering and defaults: master switch ON, token OFF, token row hidden while it is
     * off — a 48-character secret sitting under an off switch only invites pasting it somewhere it
     * will do nothing.
     */
    private fun addAutomationRows() {
        val on = persistentState.automationExportEnabled
        renderedAllFilesAccess = hasAllFilesAccess()
        // The warning is about §1's caller-supplied ABSOLUTE path only. §2a's data door writes into
        // a descriptor the caller opened and needs no storage permission at all, so this never
        // blocks 応用管理's backup — it only limits where 自由作業盤's batch may put the ZIP.
        val warn = on && !renderedAllFilesAccess
        addToggleRow(
            R.string.kojiki_auto_switch, on, 1,
            desc = getString(if (warn) R.string.kojiki_auto_no_storage else R.string.kojiki_auto_switch_desc),
            descColor = if (warn) WARN_COLOR else null,
            onRowClick = if (warn) ({ openAllFilesAccess() }) else null
        ) { checked ->
            persistentState.automationExportEnabled = checked
            if (checked && !hasAllFilesAccess()) askAllFilesAccess() else buildRows()
        }
        addToggleRow(
            R.string.kojiki_auto_require_token, persistentState.automationRequireToken, 1,
            desc = getString(R.string.kojiki_auto_require_token_desc)
        ) { checked ->
            persistentState.automationRequireToken = checked
            buildRows() // the token row appears/disappears with it
        }
        if (persistentState.automationRequireToken) addTokenRow()
    }

    /** The token as a tag line, then a pill to copy it and one for a fresh secret (revokes pasted copies). */
    private fun addTokenRow() {
        val token = persistentState.getOrCreateAppRuleToken()
        val short =
            if (token.length > 20) "${token.take(8)}…${token.takeLast(8)}" else token
        addActionRow(R.string.kojiki_auto_token, getString(R.string.kojiki_auto_token_copy) + "  " + short) {
            copyToken()
        }
        addActionRow(R.string.app_rule_token_regenerate, null) { confirmRegenerateToken() }
    }

    private fun copyToken() {
        val token = persistentState.getOrCreateAppRuleToken()
        val cb = ContextCompat.getSystemService(this, ClipboardManager::class.java)
        cb?.setPrimaryClip(ClipData.newPlainText("automation_token", token))
        Toast.makeText(this, R.string.kojiki_auto_token_copied, Toast.LENGTH_SHORT).show()
    }

    private fun confirmRegenerateToken() {
        KojikiAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle(R.string.kojiki_auto_token_regen_title)
            .setMessage(R.string.kojiki_auto_token_regen_msg)
            .setPositiveButton(R.string.app_rule_token_regenerate) { _, _ ->
                persistentState.regenerateAppRuleToken()
                buildRows()
                Toast.makeText(this, R.string.kojiki_auto_token_regenerated, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** All-Files-Access: needed only to write a caller-supplied absolute backup directory. */
    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun askAllFilesAccess() {
        KojikiAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle(R.string.kojiki_auto_storage_title)
            .setMessage(R.string.kojiki_auto_storage_msg)
            .setPositiveButton(R.string.kojiki_auto_storage_open) { _, _ -> openAllFilesAccess() }
            .setNegativeButton(R.string.kojiki_auto_storage_skip, null)
            .setOnDismissListener { buildRows() } // repaint the switch row either way
            .show()
    }

    private fun openAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val direct = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(direct) }.onFailure {
            // Some OEM builds refuse the per-app deep link; fall back to the full list.
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }

    // Fork (白い熊 考直): the category Export/Import lives here at the top of the UI page
    // (moved from Settings → the old Backup & Restore row).
    private fun openExportImport() {
        if (isFinishing || isDestroyed) return
        val sheet = ExportImportBottomSheet()
        sheet.show(supportFragmentManager, sheet.tag)
    }

    private fun onFontImported(uri: Uri?) {
        if (uri == null) return
        val fileName = CustomUi.importFont(this, uri)
        if (fileName == null) {
            Toast.makeText(this, R.string.kojiki_ui_font_invalid, Toast.LENGTH_LONG).show()
            return
        }
        cfg.fontFamily = fileName
        recreate()
    }

}
