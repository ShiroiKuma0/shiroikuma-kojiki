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
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.SparseArray
import android.view.View
import android.widget.CompoundButton
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.CompoundButtonCompat
import com.celzero.bravedns.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Fork (白い熊 考直): the shapes of the black-yellow look, drawn in code from the colour roles —
 * the pill (never filled: a 3 dp frame and bold for a block's main action, 1.25 dp otherwise, a
 * deleting one lending its red to frame and label), the outlined switch with its dot, and the role
 * that colours an icon by what its row does. Called from the run-time pass ([CustomUi]) and from
 * the theme's view inflater ([KojikiViewInflater]), so a widget is covered wherever it appears.
 */
object KojikiPills {

    private const val MAIN_DP = 3f
    private const val REGULAR_DP = 1.25f
    private const val DISABLED = 0.38f

    private const val KIND_REGULAR = 1
    private const val KIND_MAIN = 2
    private const val KIND_DANGER = 3
    private const val KIND_GLYPH = 4

    private val DANGER_WORDS =
        listOf("delete", "remove", "uninstall", "purge", "revoke", "clear", "reset", "discard")

    private fun px(context: Context, dp: Float): Int =
        (dp * context.resources.displayMetrics.density + 0.5f).toInt().coerceAtLeast(1)

    private fun idName(v: View): String =
        if (v.id == View.NO_ID) ""
        else try { v.resources.getResourceEntryName(v.id) } catch (_: Exception) { "" }

    /** A pill for a view the fork builds itself: frame + ground + ripple. */
    fun pill(context: Context, main: Boolean, danger: Boolean = false): Drawable {
        val frame = frameColor(context, main, danger)
        val shape = GradientDrawable().apply {
            cornerRadius = 100 * context.resources.displayMetrics.density
            setColor(KojikiRoles.color(context, "button_ground"))
            setStroke(px(context, if (main) MAIN_DP else REGULAR_DP), frame)
        }
        return RippleDrawable(ColorStateList.valueOf(KojikiRoles.al(frame, 0.3f)), shape, null)
    }

    fun frameColor(context: Context, main: Boolean, danger: Boolean): Int = when {
        danger -> KojikiRoles.color(context, "button_danger")
        main -> KojikiRoles.color(context, "button_main_frame")
        else -> KojikiRoles.color(context, "button_frame")
    }

    fun labelColor(context: Context, main: Boolean, danger: Boolean): Int = when {
        danger -> KojikiRoles.color(context, "button_danger")
        main -> KojikiRoles.color(context, "button_main_label")
        else -> KojikiRoles.color(context, "button_label")
    }

    /**
     * What a button was before the fork restyled it, decided once and kept in a tag: a button
     * upstream drew **filled** is its block's main action, one named or labelled as deleting is a
     * danger pill, one with an icon and no label is a glyph (left a glyph), any other is regular.
     */
    private fun kindOf(v: MaterialButton): Int {
        (v.getTag(R.id.kojiki_pill_kind) as? Int)?.let { return it }
        val context = v.context
        val name = idName(v).lowercase()
        val label = v.text?.toString().orEmpty().lowercase()
        val kind =
            when {
                label.isBlank() && v.icon != null -> KIND_GLYPH
                DANGER_WORDS.any { name.contains(it) } -> KIND_DANGER
                else -> {
                    val fill = v.backgroundTintList?.defaultColor ?: 0
                    val filled =
                        (fill ushr 24) > 0x80 &&
                            (fill == KojikiRoles.color(context, "primary") ||
                                fill == KojikiRoles.color(context, "good") ||
                                fill == KojikiRoles.color(context, "tour_button_ground"))
                    if (filled) KIND_MAIN else KIND_REGULAR
                }
            }
        v.setTag(R.id.kojiki_pill_kind, kind)
        return kind
    }

    /** Whether [v] was already styled for the current colours (the pass runs on every layout). */
    private fun fresh(v: View): Boolean {
        val g = KojikiRoles.generation()
        if (v.getTag(R.id.kojiki_style_generation) == g) return true
        v.setTag(R.id.kojiki_style_generation, g)
        return false
    }

    /** An action button as a pill. Checkable buttons (segments) are selection marks: see [segment]. */
    fun button(v: MaterialButton) {
        val context = v.context
        val kind = kindOf(v)
        val ground = KojikiRoles.color(context, "button_ground")
        val wantStroke =
            if (kind == KIND_GLYPH) 0 else px(context, if (kind == KIND_MAIN) MAIN_DP else REGULAR_DP)
        val wantFill = if (kind == KIND_GLYPH) 0 else ground
        // Styled already for these colours, and nothing has repainted it since: leave it.
        if (fresh(v) && v.strokeWidth == wantStroke &&
            v.backgroundTintList?.defaultColor == wantFill) return
        if (kind == KIND_GLYPH) {
            val glyph = KojikiRoles.color(context, "button_label")
            v.iconTint = ColorStateList.valueOf(glyph)
            v.backgroundTintList = ColorStateList.valueOf(0)
            v.strokeWidth = 0
            v.rippleColor = ColorStateList.valueOf(KojikiRoles.al(glyph, 0.3f))
            return
        }
        val main = kind == KIND_MAIN
        val danger = kind == KIND_DANGER
        val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
        val frame = frameColor(context, main, danger)
        val label = labelColor(context, main, danger)
        v.isAllCaps = false
        v.setTextColor(ColorStateList(states, intArrayOf(KojikiRoles.al(label, DISABLED), label)))
        v.iconTint = ColorStateList(states, intArrayOf(KojikiRoles.al(label, DISABLED), label))
        v.backgroundTintList = ColorStateList.valueOf(ground)
        v.strokeColor = ColorStateList(states, intArrayOf(KojikiRoles.al(frame, DISABLED), frame))
        v.strokeWidth = px(context, if (main) MAIN_DP else REGULAR_DP)
        v.cornerRadius = px(context, 100f)
        v.rippleColor = ColorStateList.valueOf(KojikiRoles.color(context, "button_ripple"))
        v.stateListAnimator = null
        v.elevation = 0f
        // the rectangular ring earlier builds drew over the button
        if (v.foreground is GradientDrawable) v.foreground = null
        val base = v.typeface ?: Typeface.DEFAULT
        v.typeface = Typeface.create(base, if (main) Typeface.BOLD else Typeface.NORMAL)
    }

    /** A segment of a selector: its fill marks the selected one — a selection, not an action. */
    fun segment(v: MaterialButton) {
        if (fresh(v)) return
        val context = v.context
        val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val onGround = KojikiRoles.color(context, "toggle_on_ground")
        val onLabel = KojikiRoles.color(context, "toggle_on_label")
        val offLabel = KojikiRoles.color(context, "button_label")
        v.backgroundTintList =
            ColorStateList(st, intArrayOf(onGround, KojikiRoles.color(context, "toggle_off_ground")))
        v.setTextColor(ColorStateList(st, intArrayOf(onLabel, offLabel)))
        v.iconTint = ColorStateList(st, intArrayOf(onLabel, offLabel))
        v.strokeColor = ColorStateList.valueOf(KojikiRoles.color(context, "button_frame"))
        v.strokeWidth = px(context, 1f)
    }

    /**
     * The outlined switch: a 2 dp fully round outline with the ground showing through and a solid
     * dot inside, right when on and left when off. Never filled; on and off differ in colour only.
     */
    fun switch(v: SwitchCompat) {
        if (fresh(v)) return
        val context = v.context
        val d = context.resources.displayMetrics.density
        val colors =
            ColorStateList(
                arrayOf(
                    intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_checked),
                    intArrayOf(-android.R.attr.state_enabled),
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()),
                intArrayOf(
                    KojikiRoles.al(KojikiRoles.color(context, "switch_on"), DISABLED),
                    KojikiRoles.al(KojikiRoles.color(context, "switch_off"), DISABLED),
                    KojikiRoles.color(context, "switch_on"),
                    KojikiRoles.color(context, "switch_off")))
        val h = (28 * d).toInt()
        val track = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = h / 2f
            setColor(0)
            setStroke((2 * d).toInt().coerceAtLeast(1), 0xFFFFFFFF.toInt())
            setSize((52 * d).toInt(), h)
        }
        val dotSize = (h * 0.46f).toInt()
        val inset = (h - dotSize) / 2
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFFFFFFF.toInt())
            setSize(dotSize, dotSize)
        }
        if (v is MaterialSwitch) {
            v.thumbIconDrawable = null
            v.trackDecorationDrawable = null
        }
        // The dot sits in a box of half the track's width and all of its height. A LayerDrawable
        // with layer insets, NOT an InsetDrawable: SwitchCompat takes an InsetDrawable's insets
        // for the thumb's optical insets and shrinks the TRACK by them on every side, which drew
        // the 52 × 28 dp outline at about 30 × 13 dp with the dot as tall as the track (seen
        // 2026-11-22). A layer inset is neither padding nor an optical inset.
        val boxW = (26 * d).toInt()
        val insetH = ((boxW - dotSize) / 2).coerceAtLeast(0)
        v.thumbDrawable = android.graphics.drawable.LayerDrawable(arrayOf(dot)).apply {
            setLayerInset(0, insetH, inset, insetH, inset)
        }
        v.trackDrawable = track
        v.thumbTintList = colors
        v.trackTintList = colors
        v.switchMinWidth = (52 * d).toInt()
        v.showText = false
        v.splitTrack = false
    }

    /** A radio button or a check box: a selection mark, tinted from its two roles. */
    fun mark(v: CompoundButton) {
        if (v is SwitchCompat || fresh(v)) return
        CompoundButtonCompat.setButtonTintList(
            v,
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(
                    KojikiRoles.color(v.context, "mark_on"),
                    KojikiRoles.color(v.context, "mark_off"))))
    }

    // ---- icons coloured by what the row does ----------------------------------------------------

    private val roleById = SparseArray<String>()

    private val ROLE_WORDS: List<Pair<String, List<String>>> = listOf(
        "icon_delete" to listOf("delete", "remove", "uninstall", "purge", "clear", "reset", "tombstone"),
        "icon_block" to listOf("block", "pause", "stop", "lockdown", "isolate", "mute", "deny"),
        "icon_share" to listOf("share", "export", "import", "copy", "backup", "restore", "download", "upload", "pcap"),
        "icon_connect" to listOf("wireguard", "wg", "proxy", "vpn", "connect", "start", "rpn", "tunnel", "network", "orbot", "socks5", "http"),
        "icon_message" to listOf("log", "logs", "dns", "query", "stats", "statistics", "event", "events", "console", "alert", "alerts"),
        "icon_contact" to listOf("apps", "user", "person", "contact", "profile", "sponsor", "authors"),
    )

    /** The icon role a name points at — an id's entry name, or any identifier in snake case. */
    fun roleForName(name: String): String {
        val words = name.lowercase().split('_')
        for ((role, list) in ROLE_WORDS) if (words.any { it in list }) return role
        return "icon_view"
    }

    /** The colour of [v]'s icon, told from its id. Cached per id: the pass asks on every layout. */
    fun iconColor(v: View): Int {
        val id = v.id
        if (id == View.NO_ID) return KojikiRoles.color(v.context, "icon_view")
        val role = roleById[id] ?: roleForName(idName(v)).also { roleById.put(id, it) }
        return KojikiRoles.color(v.context, role)
    }

    /** [res] tinted in [role]'s colour, sized [dp] — the leading icon of a row built in code. */
    fun icon(context: Context, res: Int, role: String, dp: Int = 18): Drawable? {
        val d = AppCompatResources.getDrawable(context, res)?.mutate() ?: return null
        val s = px(context, dp.toFloat())
        d.setTint(KojikiRoles.color(context, role))
        d.setBounds(0, 0, s, s)
        return d
    }
}
