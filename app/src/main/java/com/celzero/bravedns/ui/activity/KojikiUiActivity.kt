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
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.widget.AppCompatTextView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.customui.ColorPickerDialog
import com.celzero.bravedns.customui.CustomUi
import com.celzero.bravedns.customui.CustomUiConfig
import com.celzero.bravedns.databinding.ActivityKojikiUiBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.util.Themes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koin.android.ext.android.inject

/**
 * Fork (白い熊 考直): the 白い熊 考直 UI page — configure the foundation colours (background,
 * accent/icons, text) and a global font (family / weight / size). Applied app-wide at runtime by
 * [CustomUi] when the "Custom" theme is active. Rows are built programmatically (sections + indents)
 * to mirror the sister apps' layout without per-row layout files.
 */
class KojikiUiActivity : BaseActivity(R.layout.activity_kojiki_ui) {

    private val b by viewBinding(ActivityKojikiUiBinding::bind)
    private val persistentState by inject<PersistentState>()
    private lateinit var cfg: CustomUiConfig

    private val fontImportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> onFontImported(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        setSupportActionBar(b.kojikiUiToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        cfg = CustomUiConfig(this)
        buildRows()
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
        val start = dp(16) + level * dp(20)
        view.setPaddingRelative(start, view.paddingTop, view.paddingEnd, view.paddingBottom)
    }

    private fun buildRows() {
        val holder = b.kojikiUiHolder
        holder.removeAllViews()

        addSectionHeader(R.string.kojiki_ui_section_colors)
        addColorRow(R.string.kojiki_ui_color_background, cfg.backgroundColor) {
            cfg.backgroundColor = it; recreate()
        }
        addColorRow(R.string.kojiki_ui_color_accent, cfg.accentColor) {
            cfg.accentColor = it; recreate()
        }
        addColorRow(R.string.kojiki_ui_color_text, cfg.textColor) {
            cfg.textColor = it; recreate()
        }

        addSectionHeader(R.string.kojiki_ui_section_font)
        addValueRow(R.string.kojiki_ui_font_family, CustomUi.fontDisplayName(this, cfg.fontFamily)) {
            showFontPicker()
        }
        addValueRow(R.string.kojiki_ui_font_weight, getString(CustomUi.weightLabelRes(cfg.fontWeight))) {
            showWeightPicker()
        }
        addSizeRow()
        addSampleRow()

        addValueRow(R.string.kojiki_ui_reset, "") {
            cfg.resetToDefaults(); recreate()
        }
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

    private fun addColorRow(@StringRes labelRes: Int, color: Int, onPick: (Int) -> Unit) {
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
        indent(row, 1)
        b.kojikiUiHolder.addView(row)
    }

    private fun addValueRow(@StringRes labelRes: Int, value: String, onClick: () -> Unit) {
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
        indent(box, 1)
        b.kojikiUiHolder.addView(box)
    }

    @Suppress("EmptyFunctionBlock")
    private fun addSizeRow() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), dp(16), dp(14))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val label = AppCompatTextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            text = getString(R.string.kojiki_ui_font_size)
            setTextColor(cfg.textColor)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val value = AppCompatTextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            text = sizeLabel(cfg.fontSize)
            setTextColor(cfg.textColor)
        }
        header.addView(label)
        header.addView(value)
        val seek = SeekBar(this).apply {
            max = CustomUiConfig.MAX_FONT_SIZE_SP
            progress = cfg.fontSize
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    value.text = sizeLabel(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    cfg.fontSize = sb.progress
                    recreate()
                }
            })
        }
        box.addView(header)
        box.addView(seek)
        indent(box, 1)
        b.kojikiUiHolder.addView(box)
    }

    private fun addSampleRow() {
        val sample = AppCompatTextView(this).apply {
            text = getString(R.string.kojiki_ui_font_sample)
            setTextColor(cfg.textColor)
            typeface = CustomUi.typefaceFor(this@KojikiUiActivity, cfg.fontFamily, cfg.fontWeight)
            if (cfg.fontSize > 0) setTextSize(TypedValue.COMPLEX_UNIT_SP, cfg.fontSize.toFloat())
            setPadding(0, dp(6), dp(16), dp(18))
        }
        indent(sample, 1)
        b.kojikiUiHolder.addView(sample)
    }

    private fun sizeLabel(sp: Int): String =
        if (sp > 0) getString(R.string.kojiki_ui_size_sp, sp) else getString(R.string.kojiki_ui_size_default)

    private fun selectableBackground(): android.graphics.drawable.Drawable? {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return androidx.core.content.ContextCompat.getDrawable(this, tv.resourceId)
    }

    private fun showFontPicker() {
        val fonts = CustomUi.availableFonts(this)
        val names = fonts.map { it.displayName }.toTypedArray()
        val current = fonts.indexOfFirst { it.value == cfg.fontFamily }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle(R.string.kojiki_ui_font_family)
            .setSingleChoiceItems(names, current) { d, which ->
                cfg.fontFamily = fonts[which].value
                d.dismiss()
                recreate()
            }
            .setNeutralButton(R.string.kojiki_ui_font_import) { _, _ ->
                fontImportLauncher.launch(arrayOf("*/*"))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
