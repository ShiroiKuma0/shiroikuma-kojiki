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

import android.content.Context
import android.content.res.Configuration
import android.graphics.Outline
import android.net.Uri
import android.os.Bundle
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
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.customui.ColorPickerDialog
import com.celzero.bravedns.customui.CustomUi
import com.celzero.bravedns.customui.CustomUiConfig
import com.celzero.bravedns.customui.SnoopTagUi
import com.celzero.bravedns.databinding.ActivityKojikiUiBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.SnoopTagStore
import com.celzero.bravedns.util.Themes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koin.android.ext.android.inject

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

    private companion object {
        const val KEY_SCROLL_Y = "kojiki_ui_scroll_y"
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
        // Fork (白い熊 考直): indents tripled (per-level step 20 → 60 dp) for clearer group nesting.
        val start = dp(16) + level * dp(60)
        view.setPaddingRelative(start, view.paddingTop, view.paddingEnd, view.paddingBottom)
    }

    private fun buildRows() {
        val holder = b.kojikiUiHolder
        holder.removeAllViews()

        // --- Colours ---
        addSectionHeader(R.string.kojiki_ui_section_colors)
        addColorRow(R.string.kojiki_ui_color_background, cfg.backgroundColor) { cfg.backgroundColor = it; recreate() }
        addColorRow(R.string.kojiki_ui_color_surface, cfg.surfaceColor) { cfg.surfaceColor = it; recreate() }
        addColorRow(R.string.kojiki_ui_color_accent, cfg.accentColor) { cfg.accentColor = it; recreate() }
        addColorRow(R.string.kojiki_ui_color_text, cfg.textColor) { cfg.textColor = it; recreate() }
        addColorRow(R.string.kojiki_ui_switch, cfg.switchColor) { cfg.switchColor = it; recreate() }

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
            addValueRow(R.string.kojiki_ui_snoop_tag_none, "", indentLevel = 2) {}
        } else {
            snoopTags.forEach { addTagManageRow(it) }
        }
        addValueRow(R.string.snoop_tag_new, "", indentLevel = 2) { SnoopTagUi.showNew(this) { recreate() } }

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

        addValueRow(R.string.kojiki_ui_reset, "") { cfg.resetToDefaults(); recreate() }
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
            setPadding(0, dp(12), dp(16), dp(12))
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
            setPadding(0, dp(12), dp(16), dp(12))
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
            setPadding(0, dp(12), dp(16), dp(12))
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
    private fun addFontGroup(@StringRes labelRes: Int, prefix: String) {
        addSubLabel(labelRes)
        addColorRow(R.string.kojiki_ui_item_colour, cfg.styleColor(prefix, CustomUiConfig.PALETTE_YELLOW), indentLevel = 2) {
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

    private fun addToggleRow(@StringRes labelRes: Int, current: Boolean, indentLevel: Int, onChange: (Boolean) -> Unit) {
        val sw = androidx.appcompat.widget.SwitchCompat(this).apply { isChecked = current }
        val label = AppCompatTextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            text = getString(labelRes)
            setTextColor(cfg.textColor)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), dp(16), dp(14))
            addView(label)
            addView(sw)
        }
        sw.setOnCheckedChangeListener { _, c -> onChange(c) }
        indent(row, indentLevel)
        b.kojikiUiHolder.addView(row)
    }

    private fun weightLabel(v: Int): String =
        if (v > 0) v.toString() else getString(R.string.kojiki_ui_size_default)

    private fun addSubLabel(@StringRes labelRes: Int) {
        val label = AppCompatTextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            text = getString(labelRes)
            setTextColor(cfg.accentColor)
            setPadding(0, dp(12), 0, dp(2))
        }
        indent(label, 1)
        b.kojikiUiHolder.addView(label)
    }

    private fun addSectionHeader(@StringRes labelRes: Int) {
        val label = AppCompatTextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            text = getString(labelRes)
            setTextColor(cfg.accentColor)
        }
        val underline = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).also {
                it.topMargin = dp(4)
            }
            setBackgroundColor(cfg.accentColor)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(6))
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
            setPadding(0, dp(14), dp(16), dp(14))
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
    private fun addTagManageRow(tag: SnoopTagStore.Tag) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = selectableBackground()
            setPadding(0, dp(14), dp(16), dp(14))
            isClickable = true
            setOnClickListener { SnoopTagUi.showEdit(this@KojikiUiActivity, tag) { recreate() } }
        }
        val label = AppCompatTextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            text = tag.name
            setTextColor(cfg.textColor)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            setBackgroundColor(tag.color ?: cfg.snoopTagBorderColor)
        }
        row.addView(label)
        row.addView(swatch)
        indent(row, 2)
        b.kojikiUiHolder.addView(row)
    }

    private fun addValueRow(@StringRes labelRes: Int, value: String, indentLevel: Int = 1, onClick: () -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = selectableBackground()
            setPadding(0, dp(14), dp(16), dp(14))
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
                setTextColor(cfg.textColor)
                alpha = 0.7f
                setPadding(0, dp(3), 0, 0)
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
            setPadding(0, dp(14), dp(16), dp(14))
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
            setPadding(0, dp(6), dp(16), dp(18))
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
        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
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
        MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle(R.string.kojiki_ui_font_weight)
            .setSingleChoiceItems(names, current) { d, which ->
                cfg.fontWeight = CustomUi.weightOptions[which].value
                d.dismiss()
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
