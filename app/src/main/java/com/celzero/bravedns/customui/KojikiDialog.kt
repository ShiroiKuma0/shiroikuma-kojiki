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

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import com.celzero.bravedns.R
import com.celzero.bravedns.util.UIUtils

/**
 * Fork (白い熊 考直): a dialog that draws its own single bordered box.
 *
 * Why not MaterialAlertDialogBuilder + a theme: a Material alert splits its surface into separate
 * title / content / button panels, and **every** lever a theme offers paints the wrong thing.
 * `android:windowBackground` is replaced by `MaterialAlertDialogBuilder.create()` at show time, so a
 * border set there is discarded; `android:background` is applied to each panel *individually*, which
 * produced three stacked bordered boxes with clipped corners. There is no theme attribute that
 * strokes the one outer surface.
 *
 * So this builds the whole dialog: one rounded, accent-bordered box on a transparent window, holding
 * the title, the content, and a right-aligned button row. Content taller than the screen scrolls
 * inside the box rather than pushing the buttons off it. Off the Custom theme it falls back to the
 * activity theme's accent/text colours, so it stays legible on any theme.
 */
object KojikiDialog {

    /** A dialog button. [leading] pins it to the left of the row (the "neutral" slot). */
    class Action(
        val label: CharSequence,
        val leading: Boolean = false,
        val onClick: (() -> Unit)? = null
    )

    private const val MAX_CONTENT_FRACTION = 0.55f

    private fun accentOf(context: Context): Int =
        if (CustomUi.customThemeActive) CustomUiConfig(context).accentColor
        else UIUtils.fetchColor(context, R.attr.accentGood)

    private fun backgroundOf(context: Context): Int =
        if (CustomUi.customThemeActive) CustomUiConfig(context).backgroundColor
        else UIUtils.fetchColor(context, R.attr.background)

    private fun textOf(context: Context): Int =
        if (CustomUi.customThemeActive) CustomUiConfig(context).textColor
        else UIUtils.fetchColor(context, R.attr.primaryTextColor)

    /**
     * Show a themed dialog. [content] receives the vertical container to fill (already inside the
     * scrolling area) and the dialog itself, so a row can dismiss it.
     */
    fun show(
        context: Context,
        title: CharSequence?,
        actions: List<Action>,
        content: ((LinearLayout, Dialog) -> Unit)? = null
    ): Dialog {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val accent = accentOf(context)
        val bg = backgroundOf(context)
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = 18 * d
                setColor(bg)
                setStroke(dp(2), accent)
            }
        }

        if (!title.isNullOrEmpty()) {
            box.addView(
                TextView(context).apply {
                    text = title
                    textSize = 18f
                    setTextColor(accent)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 0, dp(10))
                })
        }

        if (content != null) {
            val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            content(inner, dialog)
            // Clamp the scrolling area so a long list can never push the buttons off the screen.
            val maxH = (context.resources.displayMetrics.heightPixels * MAX_CONTENT_FRACTION).toInt()
            val scroll = object : ScrollView(context) {
                override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                    super.onMeasure(
                        widthSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
                }
            }
            scroll.isFillViewport = false
            scroll.addView(
                inner,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            box.addView(
                scroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        if (actions.isNotEmpty()) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            for (a in actions.filter { it.leading }) row.addView(actionButton(context, a, dialog, accent))
            row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            for (a in actions.filterNot { it.leading }) row.addView(actionButton(context, a, dialog, accent))
            box.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // The window itself stays transparent and full-width; the side padding here is the dialog's
        // margin, so the bordered box never touches (or gets clipped by) the screen edge.
        val wrapper = FrameLayout(context).apply {
            setPadding(dp(16), 0, dp(16), 0)
            addView(
                box,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        dialog.setContentView(wrapper)
        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0f) // matches the app's no-dim dialog convention
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        return dialog
    }

    private fun actionButton(context: Context, a: Action, dialog: Dialog, accent: Int): Button {
        val d = context.resources.displayMetrics.density
        return Button(context).apply {
            text = a.label
            isAllCaps = false
            setTextColor(accent)
            typeface = Typeface.DEFAULT_BOLD
            background = null
            stateListAnimator = null
            minWidth = 0
            minimumWidth = 0
            setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
            setOnClickListener {
                dialog.dismiss()
                a.onClick?.invoke()
            }
        }
    }

    // ---- content helpers -------------------------------------------------------------------------

    /** A single-line or multi-line text field for a dialog body. */
    fun input(
        context: Context,
        initial: CharSequence?,
        hint: CharSequence?,
        multiLine: Boolean = false
    ): EditText {
        val accent = accentOf(context)
        val fg = textOf(context)
        return EditText(context).apply {
            setText(initial)
            setSelection(text?.length ?: 0)
            this.hint = hint
            setTextColor(fg)
            setHintTextColor(withAlpha(fg, 0.45f))
            backgroundTintList = ColorStateList.valueOf(accent)
            if (multiLine) {
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
            } else {
                isSingleLine = true
            }
        }
    }

    /** A dim helper line under a field. */
    fun helper(context: Context, text: CharSequence): TextView {
        val d = context.resources.displayMetrics.density
        return TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(withAlpha(textOf(context), 0.6f))
            setPadding(0, (6 * d).toInt(), 0, 0)
        }
    }

    /** A themed checkbox row. */
    fun checkbox(context: Context, label: CharSequence, checked: Boolean): CheckBox {
        val d = context.resources.displayMetrics.density
        return CheckBox(context).apply {
            text = label
            isChecked = checked
            textSize = 15f
            setTextColor(textOf(context))
            buttonTintList = ColorStateList.valueOf(accentOf(context))
            setPadding((8 * d).toInt(), (7 * d).toInt(), 0, (7 * d).toInt())
        }
    }

    /** A tappable list row (the "pick one" body). */
    fun row(context: Context, label: CharSequence, onClick: () -> Unit): TextView {
        val d = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = label
            textSize = 15f
            setTextColor(textOf(context))
            setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt())
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    /** [color] at [fraction] of its opacity. */
    fun withAlpha(color: Int, fraction: Float): Int {
        val a = (((color ushr 24) and 0xFF) * fraction).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0xFFFFFF)
    }
}
