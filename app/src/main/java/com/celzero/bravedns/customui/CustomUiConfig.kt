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
 * Holds the foundation colours (background / accent-icons / text) and a global font (family /
 * weight / size). Defaults are the seeded black-background + yellow-text/accent look. Applied at
 * runtime by [CustomUi] when the "Custom" theme ([com.celzero.bravedns.util.Themes.CUSTOM]) is on.
 */
class CustomUiConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var backgroundColor: Int
        get() = prefs.getInt(KEY_BG, PALETTE_BLACK)
        set(value) = prefs.edit().putInt(KEY_BG, value).apply()

    var accentColor: Int
        get() = prefs.getInt(KEY_ACCENT, PALETTE_YELLOW)
        set(value) = prefs.edit().putInt(KEY_ACCENT, value).apply()

    var textColor: Int
        get() = prefs.getInt(KEY_TEXT, PALETTE_YELLOW)
        set(value) = prefs.edit().putInt(KEY_TEXT, value).apply()

    /** "" = system default; "@monospace"/"@serif"/"@sans" = built-in; else an imported file name. */
    var fontFamily: String
        get() = prefs.getString(KEY_FONT_FAMILY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FONT_FAMILY, value).apply()

    /** 0 = the family's own default weight; else a 100..900 target. */
    var fontWeight: Int
        get() = prefs.getInt(KEY_FONT_WEIGHT, 0)
        set(value) = prefs.edit().putInt(KEY_FONT_WEIGHT, value).apply()

    /** 0 = leave each view's own size; else an absolute sp size. */
    var fontSize: Int
        get() = prefs.getInt(KEY_FONT_SIZE, 0)
        set(value) = prefs.edit().putInt(KEY_FONT_SIZE, value).apply()

    fun resetToDefaults() {
        backgroundColor = PALETTE_BLACK
        accentColor = PALETTE_YELLOW
        textColor = PALETTE_YELLOW
        fontFamily = ""
        fontWeight = 0
        fontSize = 0
    }

    companion object {
        private const val PREFS = "kojiki_ui"
        private const val KEY_BG = "kojiki_bg_color"
        private const val KEY_ACCENT = "kojiki_accent_color"
        private const val KEY_TEXT = "kojiki_text_color"
        private const val KEY_FONT_FAMILY = "kojiki_font_family"
        private const val KEY_FONT_WEIGHT = "kojiki_font_weight"
        private const val KEY_FONT_SIZE = "kojiki_font_size"

        const val PALETTE_BLACK = 0xFF000000.toInt()
        const val PALETTE_YELLOW = 0xFFFFEB3B.toInt()
        const val MAX_FONT_SIZE_SP = 40
    }
}
