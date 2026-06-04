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

    fun resetToDefaults() {
        prefs.edit().clear().apply()
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
        private const val KEY_BORDER_COLOR = "kojiki_card_border_color"
        private const val KEY_BORDER_WIDTH = "kojiki_card_border_width"
        private const val KEY_DIVIDER_COLOR = "kojiki_divider_color"
        private const val KEY_DIVIDER_WIDTH = "kojiki_divider_width"

        // Text-style prefixes (each gets _color/_size/_font/_weight/_italic keys).
        const val P_FW_NAME_USER = "kojiki_fw_name_user"
        const val P_FW_NAME_SYSTEM = "kojiki_fw_name_system"
        const val P_FW_STATUS = "kojiki_fw_status"
        const val P_FW_TRAFFIC = "kojiki_fw_traffic"

        const val PALETTE_BLACK = 0xFF000000.toInt()
        const val PALETTE_YELLOW = 0xFFFFEB3B.toInt()
        const val MAX_FONT_SIZE_SP = 40
        const val MAX_WEIGHT = 900
        const val MAX_ICON_SIZE_DP = 120
        const val MAX_BORDER_DP = 12
        const val MAX_DIVIDER_DP = 8
    }
}
