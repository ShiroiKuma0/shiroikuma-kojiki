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
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.celzero.bravedns.R
import com.celzero.bravedns.customui.KojikiAlertDialogBuilder
import java.util.Locale

/**
 * Fork (白い熊 考直): a small opaque RGB colour picker (three sliders + live preview + hex), built
 * programmatically so it needs no extra layout. Used by the 白い熊 考直 UI page.
 */
object ColorPickerDialog {

    private const val MAX_CHANNEL = 255

    @Suppress("EmptyFunctionBlock") // SeekBar start/stop callbacks are intentionally no-ops
    fun show(context: Context, initial: Int, onPicked: (Int) -> Unit) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        var a = Color.alpha(initial)
        var r = Color.red(initial)
        var g = Color.green(initial)
        var b = Color.blue(initial)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(4))
        }

        val preview = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
        }
        val hex = TextView(context).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(12))
        }
        container.addView(preview)
        container.addView(hex)

        fun current() = Color.argb(a, r, g, b)
        fun refresh() {
            preview.setBackgroundColor(current())
            // 8-digit #AARRGGBB so the alpha is visible/editable.
            hex.text = String.format(Locale.ROOT, "#%08X", current())
        }

        fun addChannel(name: String, value: Int, onChange: (Int) -> Unit) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val label = TextView(context).apply {
                text = name
                width = dp(20)
            }
            val bar = SeekBar(context).apply {
                max = MAX_CHANNEL
                progress = value
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        onChange(p)
                        refresh()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }
            row.addView(label)
            row.addView(bar)
            container.addView(row)
        }

        addChannel("A", a) { a = it }
        addChannel("R", r) { r = it }
        addChannel("G", g) { g = it }
        addChannel("B", b) { b = it }
        refresh()

        KojikiAlertDialogBuilder(context, R.style.App_Dialog_NoDim)
            .setTitle(R.string.kojiki_ui_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ -> onPicked(current()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
