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
package com.celzero.bravedns.customui

import android.content.Context
import android.content.SharedPreferences

/**
 * Fork (白い熊 考直): SharedPreferences-backed config for the custom UI theme.
 *
 * Foundation colours (background / surface / accent / text / switch), a global font, per-item text
 * styles for the firewall list (app name — split user vs system —, status, traffic; each its own
 * colour + size + font family + weight + italic), firewall toggle-icon colours per state, the
 * firewall icon size + roundness, card border, and list dividers. Applied at runtime by [CustomUi]
 * when the "Custom" theme ([com.celzero.bravedns.util.Themes.CUSTOM]) is on.
 *
 * Text-style fields: size 0 = inherit the global size (then the view's own); family "" = inherit the
 * global family; weight 0 = inherit the global weight; colour defaults to yellow. iconSize 0 = leave
 * the layout size; iconRoundness 0..100 (% of half the icon). Divider/toggle colours of 0 = inherit
 * (leave the theme / icon's own colour).
 */
class CustomUiConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        seedDefaults()
        migratePureYellow()
    }

    private fun int(key: String, def: Int) = prefs.getInt(key, def)
    private fun putInt(key: String, v: Int) = prefs.edit().putInt(key, v).apply()
    private fun str(key: String) = prefs.getString(key, "") ?: ""
    private fun putStr(key: String, v: String) = prefs.edit().putString(key, v).apply()
    private fun bool(key: String, def: Boolean) = prefs.getBoolean(key, def)
    private fun putBool(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()

    /** A resolved text style (read snapshot). */
    data class TextStyle(
        val color: Int, val size: Int, val family: String, val weight: Int, val italic: Boolean
    )

    // --- Per-prefix text-style accessors (used by the UI to read/write individual fields) ---
    fun styleColor(p: String, def: Int) = int("${p}_color", def)
    fun setStyleColor(p: String, v: Int) = putInt("${p}_color", v)
    fun styleSize(p: String) = int("${p}_size", 0)
    fun setStyleSize(p: String, v: Int) = putInt("${p}_size", v)
    fun styleFamily(p: String) = str("${p}_font")
    fun setStyleFamily(p: String, v: String) = putStr("${p}_font", v)
    fun styleWeight(p: String) = int("${p}_weight", 0)
    fun setStyleWeight(p: String, v: Int) = putInt("${p}_weight", v)
    fun styleItalic(p: String) = bool("${p}_italic", false)
    fun setStyleItalic(p: String, v: Boolean) = putBool("${p}_italic", v)
    fun styleOf(p: String, defColor: Int) =
        TextStyle(styleColor(p, defColor), styleSize(p), styleFamily(p), styleWeight(p), styleItalic(p))

    // --- Foundation colours ---
    var backgroundColor: Int
        get() = int(KEY_BG, PALETTE_BLACK); set(v) = putInt(KEY_BG, v)
    /** Cards / elevated surfaces / the home ("front page") cards. */
    var surfaceColor: Int
        get() = int(KEY_SURFACE, PALETTE_BLACK); set(v) = putInt(KEY_SURFACE, v)
    var accentColor: Int
        get() = int(KEY_ACCENT, PALETTE_YELLOW); set(v) = putInt(KEY_ACCENT, v)
    var textColor: Int
        get() = int(KEY_TEXT, PALETTE_YELLOW); set(v) = putInt(KEY_TEXT, v)
    /** Material on/off switches (checked colour). */
    var switchColor: Int
        get() = int(KEY_SWITCH_COLOR, PALETTE_YELLOW); set(v) = putInt(KEY_SWITCH_COLOR, v)

    // --- Global font (applies to all text not overridden per-item) ---
    /** "" = system default; "@monospace"/"@serif"/"@sans" = built-in; else an imported file name. */
    var fontFamily: String
        get() = str(KEY_FONT_FAMILY); set(v) = putStr(KEY_FONT_FAMILY, v)
    /** 0 = the family's own default weight; else a 1..900 target. */
    var fontWeight: Int
        get() = int(KEY_FONT_WEIGHT, 0); set(v) = putInt(KEY_FONT_WEIGHT, v)
    /** 0 = leave each view's own size; else an absolute sp size. */
    var fontSize: Int
        get() = int(KEY_FONT_SIZE, 0); set(v) = putInt(KEY_FONT_SIZE, v)
    var fontItalic: Boolean
        get() = bool(KEY_FONT_ITALIC, false); set(v) = putBool(KEY_FONT_ITALIC, v)

    fun globalStyle() = TextStyle(textColor, fontSize, fontFamily, fontWeight, fontItalic)

    // --- Firewall wifi/data toggle icon colours (by state; 0 = leave the icon's own colour) ---
    var fwAllowedColor: Int
        get() = int(KEY_FW_ALLOWED_COLOR, PALETTE_YELLOW); set(v) = putInt(KEY_FW_ALLOWED_COLOR, v)
    var fwDeniedColor: Int
        get() = int(KEY_FW_DENIED_COLOR, PALETTE_YELLOW); set(v) = putInt(KEY_FW_DENIED_COLOR, v)
    var fwExcludedColor: Int
        get() = int(KEY_FW_EXCLUDED_COLOR, 0); set(v) = putInt(KEY_FW_EXCLUDED_COLOR, v)
    var fwBypassDnsColor: Int
        get() = int(KEY_FW_BYPASS_DNS_COLOR, 0); set(v) = putInt(KEY_FW_BYPASS_DNS_COLOR, v)
    var fwBypassUnivColor: Int
        get() = int(KEY_FW_BYPASS_UNIV_COLOR, 0); set(v) = putInt(KEY_FW_BYPASS_UNIV_COLOR, v)

    // --- Firewall app icon ---
    /** dp; 0 = leave the layout's own size. */
    var iconSize: Int
        get() = int(KEY_ICON_SIZE, 0); set(v) = putInt(KEY_ICON_SIZE, v)
    /** 0..100, percentage of half the icon (0 = square, 100 = circle). */
    var iconRoundness: Int
        get() = int(KEY_ICON_ROUND, 0); set(v) = putInt(KEY_ICON_ROUND, v)

    // --- Snooping panel app icon ---
    /** dp; 0 = leave the layout's own size. */
    var snoopIconSize: Int
        get() = int(KEY_SNOOP_ICON_SIZE, 0); set(v) = putInt(KEY_SNOOP_ICON_SIZE, v)
    /** 0..100, percentage of half the icon (0 = square, 100 = circle). */
    var snoopIconRoundness: Int
        get() = int(KEY_SNOOP_ICON_ROUND, 0); set(v) = putInt(KEY_SNOOP_ICON_ROUND, v)

    // --- Snooping panel severity pill (HIGH/MED/LOW badge) ---
    /** sp; 0 = leave the badge's own size. The badge keeps its severity background + white text;
     *  only the text size is overridden. */
    var snoopPillSize: Int
        get() = int(KEY_SNOOP_PILL_SIZE, 0); set(v) = putInt(KEY_SNOOP_PILL_SIZE, v)
    /** dp min width; 0 = natural (wrap text). Lets the pill be wider / more uniform. */
    var snoopPillWidth: Int
        get() = int(KEY_SNOOP_PILL_WIDTH, 0); set(v) = putInt(KEY_SNOOP_PILL_WIDTH, v)
    /** dp corner radius of the pill (default 6; raise for a rounder/capsule pill). */
    var snoopPillRadius: Int
        get() = int(KEY_SNOOP_PILL_RADIUS, 6); set(v) = putInt(KEY_SNOOP_PILL_RADIUS, v)
    /** dp; 0 = no border. */
    var snoopPillBorderWidth: Int
        get() = int(KEY_SNOOP_PILL_BORDER_W, 0); set(v) = putInt(KEY_SNOOP_PILL_BORDER_W, v)
    var snoopPillBorderColor: Int
        get() = int(KEY_SNOOP_PILL_BORDER_C, PALETTE_YELLOW); set(v) = putInt(KEY_SNOOP_PILL_BORDER_C, v)

    // --- Snooping panel status text (blocked/allowed), colour per state; font via P_SNOOP_STATE ---
    var snoopStateBlockedColor: Int
        get() = int(KEY_SNOOP_STATE_BLOCKED, SNOOP_GREEN); set(v) = putInt(KEY_SNOOP_STATE_BLOCKED, v)
    var snoopStateAllowedColor: Int
        get() = int(KEY_SNOOP_STATE_ALLOWED, SNOOP_AMBER); set(v) = putInt(KEY_SNOOP_STATE_ALLOWED, v)

    // --- Snooping-panel tag chips: default outline style (a tag's own colour overrides text+border) ---
    var snoopTagTextColor: Int
        get() = int(KEY_SNOOP_TAG_TEXT, PALETTE_YELLOW); set(v) = putInt(KEY_SNOOP_TAG_TEXT, v)
    var snoopTagBorderColor: Int
        get() = int(KEY_SNOOP_TAG_BORDER, PALETTE_YELLOW); set(v) = putInt(KEY_SNOOP_TAG_BORDER, v)
    /** dp; 0 = text-only chip (no border). */
    var snoopTagBorderWidth: Int
        get() = int(KEY_SNOOP_TAG_BORDER_W, 1); set(v) = putInt(KEY_SNOOP_TAG_BORDER_W, v)

    // --- Snooping panel inter-item (row) vertical padding ---
    /** dp added top & bottom of each row; 0 = rows as tight as the icon (icons nearly touch). */
    var snoopRowPadding: Int
        get() = int(KEY_SNOOP_ROW_PADDING, 0); set(v) = putInt(KEY_SNOOP_ROW_PADDING, v)

    // --- Network (connection) log app icon + row spacing ---
    /** dp; 0 = leave the layout's own size. */
    var connLogIconSize: Int
        get() = int(KEY_CONN_LOG_ICON_SIZE, 0); set(v) = putInt(KEY_CONN_LOG_ICON_SIZE, v)
    /** 0..100, percentage of half the icon (0 = square, 100 = circle). */
    var connLogIconRoundness: Int
        get() = int(KEY_CONN_LOG_ICON_ROUND, 0); set(v) = putInt(KEY_CONN_LOG_ICON_ROUND, v)
    /** Inter-item spacing: -1 = "Default" (keep the as-shipped paddings); >= 0 = explicit dp (0 = tight). */
    var connLogRowPadding: Int
        get() = int(KEY_CONN_LOG_ROW_PADDING, -1); set(v) = putInt(KEY_CONN_LOG_ROW_PADDING, v)

    // --- DNS log app icon + row spacing ---
    /** dp; 0 = leave the layout's own size. */
    var dnsLogIconSize: Int
        get() = int(KEY_DNS_LOG_ICON_SIZE, 0); set(v) = putInt(KEY_DNS_LOG_ICON_SIZE, v)
    /** 0..100, percentage of half the icon (0 = square, 100 = circle). */
    var dnsLogIconRoundness: Int
        get() = int(KEY_DNS_LOG_ICON_ROUND, 0); set(v) = putInt(KEY_DNS_LOG_ICON_ROUND, v)
    /** Inter-item spacing: -1 = "Default" (keep the as-shipped paddings); >= 0 = explicit dp (0 = tight). */
    var dnsLogRowPadding: Int
        get() = int(KEY_DNS_LOG_ROW_PADDING, -1); set(v) = putInt(KEY_DNS_LOG_ROW_PADDING, v)

    // --- Per-log row text size (sp; 0 = follow the global font size) ---
    var connLogTextSize: Int
        get() = int(KEY_CONN_LOG_TEXT, 0); set(v) = putInt(KEY_CONN_LOG_TEXT, v)
    var dnsLogTextSize: Int
        get() = int(KEY_DNS_LOG_TEXT, 0); set(v) = putInt(KEY_DNS_LOG_TEXT, v)

    // --- Per-log line spacing (gap between the text lines within a row). -1 = "Default" (as-shipped,
    // with the large country flag + roomy badges); >= 0 = explicit dp + compact flag/badge so the
    // lines pack tight (0 = lines touch). ---
    var connLogLineSpacing: Int
        get() = int(KEY_CONN_LOG_LINE, -1); set(v) = putInt(KEY_CONN_LOG_LINE, v)
    var dnsLogLineSpacing: Int
        get() = int(KEY_DNS_LOG_LINE, -1); set(v) = putInt(KEY_DNS_LOG_LINE, v)

    // --- Per-log divider line between items. width: -1 = "Default" (the as-shipped 1dp theme line);
    // >= 0 = explicit dp (0 = no line) painted in the divider colour. ---
    var connLogDividerWidth: Int
        get() = int(KEY_CONN_LOG_DIV_W, -1); set(v) = putInt(KEY_CONN_LOG_DIV_W, v)
    var connLogDividerColor: Int
        get() = int(KEY_CONN_LOG_DIV_C, PALETTE_YELLOW); set(v) = putInt(KEY_CONN_LOG_DIV_C, v)
    var dnsLogDividerWidth: Int
        get() = int(KEY_DNS_LOG_DIV_W, -1); set(v) = putInt(KEY_DNS_LOG_DIV_W, v)
    var dnsLogDividerColor: Int
        get() = int(KEY_DNS_LOG_DIV_C, PALETTE_YELLOW); set(v) = putInt(KEY_DNS_LOG_DIV_C, v)

    // --- Log status indicator (the blocked/allowed bar + optional text tag), shared by both logs ---
    /** dp width of the left status bar; 0 = the layout's own ~1.5dp. */
    var logStatusBarWidth: Int
        get() = int(KEY_LOG_STATUS_W, 0); set(v) = putInt(KEY_LOG_STATUS_W, v)
    var logStatusBlockedColor: Int
        get() = int(KEY_LOG_STATUS_BLOCKED, LOG_RED); set(v) = putInt(KEY_LOG_STATUS_BLOCKED, v)
    var logStatusMaybeColor: Int
        get() = int(KEY_LOG_STATUS_MAYBE, SNOOP_AMBER); set(v) = putInt(KEY_LOG_STATUS_MAYBE, v)
    /** 0 = allowed rows show no bar (the default look). */
    var logStatusAllowedColor: Int
        get() = int(KEY_LOG_STATUS_ALLOWED, 0); set(v) = putInt(KEY_LOG_STATUS_ALLOWED, v)
    /** show a BLOCKED / MAYBE / ALLOWED text tag on each log row. */
    var logTagShow: Boolean
        get() = bool(KEY_LOG_TAG_SHOW, false); set(v) = putBool(KEY_LOG_TAG_SHOW, v)
    /** sp; 0 = a small default. */
    var logTagSize: Int
        get() = int(KEY_LOG_TAG_SIZE, 0); set(v) = putInt(KEY_LOG_TAG_SIZE, v)

    // --- Popup menus (snoop row actions / sort / filter) ---
    var menuBorderColor: Int
        get() = int(KEY_MENU_BORDER_COLOR, PALETTE_YELLOW); set(v) = putInt(KEY_MENU_BORDER_COLOR, v)
    /** dp; 0 = no border. Defaults to a thin accent border. */
    var menuBorderWidth: Int
        get() = int(KEY_MENU_BORDER_WIDTH, 1); set(v) = putInt(KEY_MENU_BORDER_WIDTH, v)

    // --- Card border (applied to every MaterialCardView) ---
    var cardBorderColor: Int
        get() = int(KEY_BORDER_COLOR, PALETTE_YELLOW); set(v) = putInt(KEY_BORDER_COLOR, v)
    /** dp; 0 = no border. */
    var cardBorderWidth: Int
        get() = int(KEY_BORDER_WIDTH, 0); set(v) = putInt(KEY_BORDER_WIDTH, v)

    // --- Firewall list dividers (lines between rows) ---
    /** 0 = inherit the theme divider colour. */
    var dividerColor: Int
        get() = int(KEY_DIVIDER_COLOR, 0); set(v) = putInt(KEY_DIVIDER_COLOR, v)
    /** dp; 0 = leave the layout's own thickness. */
    var dividerThickness: Int
        get() = int(KEY_DIVIDER_WIDTH, 0); set(v) = putInt(KEY_DIVIDER_WIDTH, v)

    /**
     * Fork (白い熊 考直): seed 白い熊's exported look as the out-of-the-box defaults, so a fresh install
     * (or a "reset to defaults") comes up already tuned — black + pure-yellow, the firewall/snoop/log
     * sizes & colours 白い熊 settled on. Only runs when the kojiki prefs are empty (a truly fresh
     * install / post-reset), so it never clobbers an existing customisation. Values mirror the
     * `kojiki-settings` JSON export; sets `pure_yellow_migrated` so [migratePureYellow] is a no-op
     * (the seeded colours are already pure yellow).
     */
    private fun seedDefaults() {
        // Gate on the background key (a core setting) rather than "any pref", so a prefs file holding
        // only the one-shot migrate flag (custom theme previewed once, never tuned) still gets seeded,
        // while a real customisation (which always sets the background) is preserved.
        if (prefs.contains(KEY_BG)) return
        val e = prefs.edit()
        val ints = mapOf(
            KEY_FONT_SIZE to 16,
            KEY_ACCENT to PALETTE_YELLOW,
            KEY_BG to PALETTE_BLACK,
            KEY_TEXT to PALETTE_YELLOW,
            KEY_FONT_WEIGHT to 0,
            KEY_ICON_SIZE to 82,
            KEY_SNOOP_ICON_SIZE to 74,
            KEY_SNOOP_PILL_SIZE to 23,
            KEY_SNOOP_PILL_WIDTH to 153,
            KEY_SNOOP_PILL_RADIUS to 14,
            KEY_SNOOP_PILL_BORDER_W to 0,
            "${P_SNOOP_STATE}_size" to 19,
            KEY_SNOOP_STATE_ALLOWED to PALETTE_YELLOW,
            KEY_SNOOP_STATE_BLOCKED to 0xFFFF0000.toInt(),
            "kojiki_snoop_pill_color" to PALETTE_YELLOW,
            KEY_CONN_LOG_ICON_SIZE to 70,
            KEY_CONN_LOG_TEXT to 16,
            KEY_CONN_LOG_ROW_PADDING to 0,
            KEY_CONN_LOG_LINE to 0,
            KEY_CONN_LOG_DIV_W to 1,
            KEY_DNS_LOG_ICON_SIZE to 70,
            KEY_DNS_LOG_TEXT to 15,
            KEY_DNS_LOG_ROW_PADDING to 0,
            KEY_DNS_LOG_LINE to 0,
            KEY_DNS_LOG_DIV_W to 1,
            KEY_LOG_STATUS_W to 6,
            KEY_DIVIDER_WIDTH to 1,
            KEY_DIVIDER_COLOR to PALETTE_YELLOW,
            KEY_BORDER_WIDTH to 1,
            "${P_FW_NAME_SYSTEM}_color" to 0xFFFF0000.toInt(),
            "${P_FW_STATUS}_color" to 0xFFFFFFFF.toInt(),
            KEY_FW_DENIED_COLOR to 0xFFFF003B.toInt(),
        )
        for ((k, v) in ints) e.putInt(k, v)
        e.putString(KEY_FONT_FAMILY, "")
        e.putBoolean(KEY_LOG_TAG_SHOW, true)
        e.putBoolean("${P_FW_NAME_SYSTEM}_italic", true)
        e.putBoolean(KEY_PURE_YELLOW_MIGRATED, true)
        e.apply()
    }

    /**
     * One-time migration: [PALETTE_YELLOW] changed from material yellow (#FFEB3B) to pure yellow
     * (#FFFF00). Rewrite every persisted colour whose RGB part is the old yellow to the new one,
     * preserving the alpha byte (colours are full ARGB). Unset slots have no persisted entry and
     * the 0 = inherit sentinels never match, so both are left alone. Guarded by a persisted flag
     * so it runs once per install.
     */
    private fun migratePureYellow() {
        if (bool(KEY_PURE_YELLOW_MIGRATED, false)) return
        val e = prefs.edit()
        for ((key, v) in prefs.all) {
            if (v !is Int || !isColorKey(key)) continue
            if (v and 0xFFFFFF == OLD_YELLOW_RGB) {
                e.putInt(key, (v and 0xFF000000.toInt()) or (PALETTE_YELLOW and 0xFFFFFF))
            }
        }
        e.putBoolean(KEY_PURE_YELLOW_MIGRATED, true).apply()
    }

    /** True for persisted colour keys: the "*_color" ones plus those without the suffix. */
    private fun isColorKey(key: String) = key.endsWith("_color") || key in NON_SUFFIX_COLOR_KEYS

    fun resetToDefaults() {
        prefs.edit().clear().apply()
        // Re-seed 白い熊's tuned look (the configured default), not the bare PALETTE baseline.
        seedDefaults()
    }

    companion object {
        private const val PREFS = "kojiki_ui"
        private const val KEY_BG = "kojiki_bg_color"
        private const val KEY_SURFACE = "kojiki_surface_color"
        private const val KEY_ACCENT = "kojiki_accent_color"
        private const val KEY_TEXT = "kojiki_text_color"
        private const val KEY_SWITCH_COLOR = "kojiki_switch_color"
        private const val KEY_FONT_FAMILY = "kojiki_font_family"
        private const val KEY_FONT_WEIGHT = "kojiki_font_weight"
        private const val KEY_FONT_SIZE = "kojiki_font_size"
        private const val KEY_FONT_ITALIC = "kojiki_font_italic"
        private const val KEY_FW_ALLOWED_COLOR = "kojiki_fw_allowed_color"
        private const val KEY_FW_DENIED_COLOR = "kojiki_fw_denied_color"
        private const val KEY_FW_EXCLUDED_COLOR = "kojiki_fw_excluded_color"
        private const val KEY_FW_BYPASS_DNS_COLOR = "kojiki_fw_bypass_dns_color"
        private const val KEY_FW_BYPASS_UNIV_COLOR = "kojiki_fw_bypass_univ_color"
        private const val KEY_ICON_SIZE = "kojiki_icon_size"
        private const val KEY_ICON_ROUND = "kojiki_icon_round"
        private const val KEY_SNOOP_ICON_SIZE = "kojiki_snoop_icon_size"
        private const val KEY_SNOOP_ICON_ROUND = "kojiki_snoop_icon_round"
        private const val KEY_SNOOP_PILL_SIZE = "kojiki_snoop_pill_size"
        private const val KEY_SNOOP_PILL_WIDTH = "kojiki_snoop_pill_width"
        private const val KEY_SNOOP_PILL_RADIUS = "kojiki_snoop_pill_radius"
        private const val KEY_SNOOP_PILL_BORDER_W = "kojiki_snoop_pill_border_w"
        private const val KEY_SNOOP_PILL_BORDER_C = "kojiki_snoop_pill_border_c"
        private const val KEY_SNOOP_STATE_BLOCKED = "kojiki_snoop_state_blocked_color"
        private const val KEY_SNOOP_STATE_ALLOWED = "kojiki_snoop_state_allowed_color"
        private const val KEY_SNOOP_ROW_PADDING = "kojiki_snoop_row_padding"
        private const val KEY_SNOOP_TAG_TEXT = "kojiki_snoop_tag_text"
        private const val KEY_SNOOP_TAG_BORDER = "kojiki_snoop_tag_border"
        private const val KEY_SNOOP_TAG_BORDER_W = "kojiki_snoop_tag_border_w"
        private const val KEY_CONN_LOG_ICON_SIZE = "kojiki_conn_log_icon_size"
        private const val KEY_CONN_LOG_ICON_ROUND = "kojiki_conn_log_icon_round"
        private const val KEY_CONN_LOG_ROW_PADDING = "kojiki_conn_log_row_padding"
        private const val KEY_DNS_LOG_ICON_SIZE = "kojiki_dns_log_icon_size"
        private const val KEY_DNS_LOG_ICON_ROUND = "kojiki_dns_log_icon_round"
        private const val KEY_DNS_LOG_ROW_PADDING = "kojiki_dns_log_row_padding"
        private const val KEY_CONN_LOG_TEXT = "kojiki_conn_log_text_size"
        private const val KEY_DNS_LOG_TEXT = "kojiki_dns_log_text_size"
        private const val KEY_CONN_LOG_LINE = "kojiki_conn_log_line_spacing"
        private const val KEY_DNS_LOG_LINE = "kojiki_dns_log_line_spacing"
        private const val KEY_CONN_LOG_DIV_W = "kojiki_conn_log_div_w"
        private const val KEY_CONN_LOG_DIV_C = "kojiki_conn_log_div_c"
        private const val KEY_DNS_LOG_DIV_W = "kojiki_dns_log_div_w"
        private const val KEY_DNS_LOG_DIV_C = "kojiki_dns_log_div_c"
        private const val KEY_LOG_STATUS_W = "kojiki_log_status_w"
        private const val KEY_LOG_STATUS_BLOCKED = "kojiki_log_status_blocked"
        private const val KEY_LOG_STATUS_MAYBE = "kojiki_log_status_maybe"
        private const val KEY_LOG_STATUS_ALLOWED = "kojiki_log_status_allowed"
        private const val KEY_LOG_TAG_SHOW = "kojiki_log_tag_show"
        private const val KEY_LOG_TAG_SIZE = "kojiki_log_tag_size"
        private const val KEY_MENU_BORDER_COLOR = "kojiki_menu_border_color"
        private const val KEY_MENU_BORDER_WIDTH = "kojiki_menu_border_width"
        private const val KEY_BORDER_COLOR = "kojiki_card_border_color"
        private const val KEY_BORDER_WIDTH = "kojiki_card_border_width"
        private const val KEY_DIVIDER_COLOR = "kojiki_divider_color"
        private const val KEY_DIVIDER_WIDTH = "kojiki_divider_width"
        // One-shot [migratePureYellow] flag: old material-yellow prefs rewritten to pure yellow.
        private const val KEY_PURE_YELLOW_MIGRATED = "pure_yellow_migrated"
        /** RGB of the pre-pure-yellow PALETTE_YELLOW (material yellow), matched alpha-agnostically. */
        private const val OLD_YELLOW_RGB = 0xFFEB3B
        /** Persisted colour keys whose names don't end in "_color" (see [isColorKey]). */
        private val NON_SUFFIX_COLOR_KEYS = setOf(
            KEY_SNOOP_PILL_BORDER_C, KEY_SNOOP_TAG_TEXT, KEY_SNOOP_TAG_BORDER,
            KEY_CONN_LOG_DIV_C, KEY_DNS_LOG_DIV_C,
            KEY_LOG_STATUS_BLOCKED, KEY_LOG_STATUS_MAYBE, KEY_LOG_STATUS_ALLOWED
        )

        // Text-style prefixes (each gets _color/_size/_font/_weight/_italic keys).
        const val P_FW_NAME_USER = "kojiki_fw_name_user"
        const val P_FW_NAME_SYSTEM = "kojiki_fw_name_system"
        const val P_FW_STATUS = "kojiki_fw_status"
        const val P_FW_TRAFFIC = "kojiki_fw_traffic"
        // Snooping panel severity pill (HIGH/MED/LOW) text — colour + font (size via snoopPillSize).
        const val P_SNOOP_PILL = "kojiki_snoop_pill"
        // Snooping panel status text (blocked/allowed) — font; colour per state (blocked/allowed).
        const val P_SNOOP_STATE = "kojiki_snoop_state"
        // Popup-menu item text — colour + font.
        const val P_SNOOP_MENU = "kojiki_menu_item"

        const val PALETTE_BLACK = 0xFF000000.toInt()
        const val PALETTE_YELLOW = 0xFFFFFF00.toInt()
        const val SNOOP_GREEN = 0xFF2B8E18.toInt()
        const val SNOOP_AMBER = 0xFFFFA000.toInt()
        const val LOG_RED = 0xFFFF1744.toInt()
        const val SNOOP_WHITE = 0xFFFFFFFF.toInt()
        const val MAX_FONT_SIZE_SP = 40
        const val MAX_WEIGHT = 900
        const val MAX_ICON_SIZE_DP = 120
        const val MAX_BORDER_DP = 12
        const val MAX_DIVIDER_DP = 8
        const val MAX_ROW_PADDING_DP = 24
        const val MAX_PILL_WIDTH_DP = 160
        const val MAX_PILL_RADIUS_DP = 30
    }
}
