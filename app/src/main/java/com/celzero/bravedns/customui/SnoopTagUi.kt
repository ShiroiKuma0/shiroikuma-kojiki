/*
 * Copyright 2024 RethinkDNS and its authors
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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import com.celzero.bravedns.R
import com.celzero.bravedns.service.SnoopTagStore
import com.celzero.bravedns.customui.KojikiAlertDialogBuilder

/**
 * Fork (白い熊 考直): the create / edit dialogs for Snooping-panel domain tags, shared by the Snoop
 * adapter (in-context) and the 白い熊 考直 UI tag-management section. A new tag saves with NO colour by
 * default (the "OK" button) — it then renders in the configurable default style (yellow border/text);
 * the separate "Colour" button is for giving a tag its own colour.
 */
object SnoopTagUi {

    /** New tag: name + (OK = no colour) / (Colour = pick one). On success calls onCreated(name). */
    fun showNew(context: Context, onCreated: (String) -> Unit) {
        val input = nameInput(context, "")
        KojikiAlertDialogBuilder(context, R.style.App_Dialog_NoDim)
            .setTitle(R.string.snoop_tag_new)
            .setView(wrap(context, input))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val n = input.text.toString().trim()
                if (n.isEmpty()) return@setPositiveButton
                SnoopTagStore.addOrUpdateTag(context, SnoopTagStore.Tag(n, null))
                onCreated(n)
            }
            .setNeutralButton(R.string.snoop_tag_color) { _, _ ->
                val n = input.text.toString().trim()
                if (n.isEmpty()) return@setNeutralButton
                ColorPickerDialog.show(context, SnoopTagStore.suggestedColor(context)) { c ->
                    SnoopTagStore.addOrUpdateTag(context, SnoopTagStore.Tag(n, c))
                    onCreated(n)
                }
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
    }

    /** Edit a tag: rename, set/clear its colour, or delete it. Calls onChanged() after any change. */
    fun showEdit(context: Context, original: SnoopTagStore.Tag, onChanged: () -> Unit) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        var picked: Int? = original.color

        val input = nameInput(context, original.name)
        val swatch =
            View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) }
            }
        fun refreshSwatch() {
            // show the effective colour: the tag's own, or the configured default border colour.
            swatch.setBackgroundColor(picked ?: CustomUiConfig(context).snoopTagBorderColor)
        }
        refreshSwatch()
        val colorBtn =
            Button(context).apply {
                text = context.getString(R.string.snoop_tag_color)
                setOnClickListener {
                    val init = picked ?: CustomUiConfig(context).snoopTagBorderColor
                    ColorPickerDialog.show(context, init) { c -> picked = c; refreshSwatch() }
                }
            }
        val clearBtn =
            Button(context).apply {
                text = context.getString(R.string.snoop_tag_clear_color)
                setOnClickListener { picked = null; refreshSwatch() }
            }
        val btnRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(swatch)
                addView(colorBtn)
                addView(clearBtn)
            }
        val box =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(input)
                addView(btnRow)
            }
        KojikiAlertDialogBuilder(context, R.style.App_Dialog_NoDim)
            .setTitle(R.string.snoop_tag_edit)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) SnoopTagStore.updateTag(context, original.name, n, picked)
                onChanged()
            }
            .setNeutralButton(R.string.snoop_tag_delete) { _, _ ->
                SnoopTagStore.deleteTag(context, original.name)
                onChanged()
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
    }

    private fun nameInput(context: Context, text: String): EditText =
        EditText(context).apply {
            setText(text)
            setSelection(text.length)
            isSingleLine = true
            hint = context.getString(R.string.snoop_tag_name_hint)
        }

    private fun wrap(context: Context, v: View): View {
        val p = (20 * context.resources.displayMetrics.density).toInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p / 2, p, 0)
            addView(
                v,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }
}
