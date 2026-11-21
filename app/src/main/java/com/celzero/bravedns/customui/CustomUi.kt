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
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListPopupWindow
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ListItemConnTrackBinding
import com.celzero.bravedns.databinding.ListItemDnsLogBinding
import com.celzero.bravedns.databinding.ListItemFirewallAppBinding
import com.celzero.bravedns.databinding.ListItemSnoopBinding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.shape.MaterialShapeDrawable
import java.io.File

/**
 * Fork (白い熊 考直): runtime application of the custom UI theme + the global font system.
 *
 * [applyTo] is called from BaseActivity.onResume when the Custom theme is active. It paints the
 * window background, and walks the view tree applying the configured text colour + global font to
 * every TextView and the accent colour to every ImageView (icon). Sets are equality-guarded so a
 * re-walk never triggers a relayout loop; a global-layout listener re-walks to catch dynamically
 * bound list rows. The static AppThemeKojikiCustom style provides the black/yellow baseline for
 * surfaces this pass doesn't touch (cards, dividers, switches).
 */
object CustomUi {

    /** Set by RethinkDnsApplication on every activity resume: is the "Custom" theme active right now?
     *  Lets adapters (e.g. FirewallAppListAdapter) apply fork colours only under the Custom theme. */
    @JvmField var customThemeActive: Boolean = false

    /** A view carrying this as its tag (and its subtree) is left untouched by the runtime tree-walk —
     *  for widgets that set their own size/colour, e.g. the 白い熊 考直 UI preview pill/status text. */
    const val NO_RESTYLE_TAG = "kojiki_no_restyle"

    const val FONT_SYSTEM = ""
    const val FONT_MONOSPACE = "@monospace"
    const val FONT_SERIF = "@serif"
    const val FONT_SANS = "@sans"

    // Firewall-row app name, when it declares no size of its own: the global font size scaled up,
    // so the name keeps the prominence the stock layout gives it (extra_large 16sp against the
    // row's 12sp status/traffic lines) instead of flattening to the global size.
    private const val FW_NAME_SIZE_SCALE = 1.15f

    // Firewall-row package id, when it declares no size / colour of its own: a fraction of the app
    // name's (already scaled) size, and the name's colour at a fraction of its opacity.
    private const val ID_SIZE_SCALE = 0.9f
    private const val ID_DIM_ALPHA = 0.65f
    private const val ID_GAP = "  "

    private val FONT_EXTENSIONS = setOf("ttf", "otf")
    private const val SEMIBOLD_WEIGHT = 600
    private val typefaceCache = HashMap<String, Typeface>()
    // Cache for weight/italic-derived typefaces (Typeface.create returns a new instance each call, which
    // would defeat the equality-guarded re-walk), keyed "family|weight|italic".
    private val styledCache = HashMap<String, Typeface>()

    // One global-layout listener per content view, so re-resumes never stack listeners. Only the
    // content view is remembered, weakly — never the listener: it captures the activity, and a
    // WeakHashMap value that reaches its own key pins the entry forever, which leaked every screen
    // ever opened (found 2026-11-02 with KojikiLeakWatch). The listener itself is held by the view's
    // own ViewTreeObserver, so it goes with the window.
    private val layoutListeners: MutableSet<View> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    data class FontOption(val displayName: String, val value: String)

    data class WeightOption(val value: Int, @StringRes val labelRes: Int)

    val weightOptions = listOf(
        WeightOption(0, R.string.kojiki_weight_default),
        WeightOption(100, R.string.kojiki_weight_thin),
        WeightOption(300, R.string.kojiki_weight_light),
        WeightOption(400, R.string.kojiki_weight_regular),
        WeightOption(500, R.string.kojiki_weight_medium),
        WeightOption(600, R.string.kojiki_weight_semibold),
        WeightOption(700, R.string.kojiki_weight_bold),
        WeightOption(900, R.string.kojiki_weight_black),
    )

    fun weightLabelRes(value: Int): Int =
        (weightOptions.firstOrNull { it.value == value } ?: weightOptions.first()).labelRes

    fun fontsDir(context: Context): File =
        File(context.applicationContext.filesDir, "kojiki_fonts").apply { mkdirs() }

    /** Drop in-memory typeface/style caches so freshly-imported fonts/settings re-load. */
    fun invalidateCaches() {
        typefaceCache.clear()
        styledCache.clear()
    }

    /** Built-in families + every .ttf/.otf the user has imported. */
    fun availableFonts(context: Context): List<FontOption> {
        val options = mutableListOf(
            FontOption(context.getString(R.string.kojiki_ui_font_system), FONT_SYSTEM),
            FontOption("Monospace", FONT_MONOSPACE),
            FontOption("Serif", FONT_SERIF),
            FontOption("Sans-serif", FONT_SANS),
        )
        fontsDir(context).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in FONT_EXTENSIONS }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { options.add(FontOption(it.nameWithoutExtension, it.name)) }
        return options
    }

    fun fontDisplayName(context: Context, value: String): String = when (value) {
        FONT_SYSTEM -> context.getString(R.string.kojiki_ui_font_system)
        FONT_MONOSPACE -> "Monospace"
        FONT_SERIF -> "Serif"
        FONT_SANS -> "Sans-serif"
        else -> File(value).nameWithoutExtension
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun baseTypeface(context: Context, family: String): Typeface = when (family) {
        FONT_SYSTEM -> Typeface.DEFAULT
        FONT_MONOSPACE -> Typeface.MONOSPACE
        FONT_SERIF -> Typeface.SERIF
        FONT_SANS -> Typeface.SANS_SERIF
        else -> typefaceCache.getOrPut(family) {
            try {
                Typeface.createFromFile(File(fontsDir(context), family))
            } catch (e: Exception) {
                Typeface.DEFAULT
            }
        }
    }

    fun typefaceFor(context: Context, family: String, weight: Int, italic: Boolean = false): Typeface {
        return styledCache.getOrPut("$family|$weight|$italic") {
            val base = baseTypeface(context, family)
            if (weight <= 0 && !italic) {
                base
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Typeface.create(base, if (weight in 1..1000) weight else base.weight, italic)
            } else {
                val bold = weight >= SEMIBOLD_WEIGHT
                val style = when {
                    bold && italic -> Typeface.BOLD_ITALIC
                    italic -> Typeface.ITALIC
                    bold -> Typeface.BOLD
                    else -> Typeface.NORMAL
                }
                Typeface.create(base, style)
            }
        }
    }

    /** Copy a picked font into the shared fonts dir; returns its file name, or null on failure. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun importFont(context: Context, uri: Uri): String? {
        val name = fontFileName(context, uri) ?: return null
        if (name.substringAfterLast('.', "").lowercase() !in FONT_EXTENSIONS) return null
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            File(fontsDir(context), name).writeBytes(bytes)
            typefaceCache.remove(name)
            styledCache.clear()
            name
        } catch (e: Exception) {
            null
        }
    }

    private fun fontFileName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    // ---- runtime application ----

    fun applyTo(activity: AppCompatActivity) {
        val cfg = CustomUiConfig(activity)
        val global = cfg.globalStyle()
        activity.window.setBackgroundDrawable(ColorDrawable(cfg.backgroundColor))
        // Paint the system status + navigation bars the configured background (black by default), so
        // they don't inherit the parent true-black theme's tinted (brown/olive) bar colour.
        activity.window.statusBarColor = KojikiRoles.color(activity, "status_bar")
        activity.window.navigationBarColor = KojikiRoles.color(activity, "navigation_bar")

        val content = activity.findViewById<View>(android.R.id.content) ?: return
        // The theme's ?attr/background colour — views painted with it (firewall rows, the home root,
        // this settings page) get recoloured to the configured background, so the bg reaches the whole
        // screen and not just the window behind opaque containers.
        val themeBg = resolveThemeColor(activity, R.attr.background)
        setSolidBg(content, cfg.backgroundColor)
        applyToTree(activity, content, cfg, global, themeBg)

        // Catch dynamically-added views (e.g. RecyclerView rows). Re-walking is cheap because every
        // set below is equality-guarded, so only genuinely-new/changed views do any work. Install at
        // most one listener per content view (config edits recreate() the activity, refreshing it).
        if (content !in layoutListeners) {
            val vto = content.viewTreeObserver
            if (vto.isAlive) {
                val listener = ViewTreeObserver.OnGlobalLayoutListener {
                    applyToTree(activity, content, cfg, global, themeBg)
                }
                layoutListeners.add(content)
                vto.addOnGlobalLayoutListener(listener)
            }
        }
    }

    /**
     * Fork (白い熊 考直): run the same styling pass over an arbitrary view tree — a dialog's or a
     * bottom sheet's content root, which [applyTo]'s activity walk never reaches (a dialog lives in
     * its own window). Call it after the content is built AND after any dynamically-added children
     * (e.g. chips remade on a filter change). No-op off the Custom theme.
     */
    /**
     * [applyToDialogTree] now and again on every layout of [root] — for a sheet or a dialog whose
     * rows arrive after it is shown. The listener holds [root] only; it dies with the view tree.
     */
    fun watchDialogTree(root: View) {
        if (!customThemeActive) return
        applyToDialogTree(root)
        if (root in layoutListeners) return
        val vto = root.viewTreeObserver
        if (!vto.isAlive) return
        layoutListeners.add(root)
        val ref = java.lang.ref.WeakReference(root)
        vto.addOnGlobalLayoutListener { ref.get()?.let { applyToDialogTree(it) } }
    }

    fun applyToDialogTree(root: View) {
        if (!customThemeActive) return
        val ctx = root.context
        val cfg = CustomUiConfig(ctx)
        applyToTree(ctx, root, cfg, cfg.globalStyle(), resolveThemeColor(ctx, R.attr.background))
    }

    private fun applyToTree(context: Context, v: View, cfg: CustomUiConfig, global: CustomUiConfig.TextStyle, themeBg: Int) {
        // Opt-out: leave this view (and its subtree) exactly as set by its owner — e.g. the
        // 白い熊 考直 UI preview widgets, which drive their own pill/status size.
        if (v.tag == NO_RESTYLE_TAG) return
        // the leading icon of a row upstream left without one (KojikiRowIcons)
        if (v is ViewGroup) KojikiRowIcons.apply(v)
        when (v) {
            // Material containers carry the tonal "surface tint" (= colorPrimary = yellow) that turns
            // elevated surfaces olive. Force their fill to the background colour and zero their
            // elevation so no tint is composited — flat black, regardless of the Material version.
            is MaterialCardView -> {
                if (v.cardBackgroundColor.defaultColor != cfg.surfaceColor) {
                    v.setCardBackgroundColor(cfg.surfaceColor)
                }
                if (v.cardElevation != 0f) v.cardElevation = 0f
                // Zeroing the elevation is not enough to close the gaps between cards: the Rethink
                // card style sets cardUseCompatPadding, and that padding is reserved from
                // *maxCardElevation*, not from the current elevation — so every flat black card kept
                // ~5dp of invisible shadow room top and bottom (on top of its 2dp margins), which is
                // what made the app list read as over-spaced. No shadow, no shadow padding.
                if (v.maxCardElevation != 0f) v.maxCardElevation = 0f
                if (v.useCompatPadding) v.useCompatPadding = false
                // Configurable card border (0 dp = none).
                val strokePx = (cfg.cardBorderWidth * context.resources.displayMetrics.density).toInt()
                if (v.strokeWidth != strokePx) v.strokeWidth = strokePx
                if (v.strokeColorStateList?.defaultColor != cfg.cardBorderColor) {
                    v.setStrokeColor(cfg.cardBorderColor)
                }
                // Home-screen cards paint a gradient drawable (home_screen_cards_bg — the "olive") on
                // their content child; flatten any non-colour child background to the surface colour.
                for (i in 0 until v.childCount) {
                    val c = v.getChildAt(i)
                    val bg = c.background
                    if (bg != null && bg !is ColorDrawable) c.setBackgroundColor(cfg.surfaceColor)
                }
            }
            is NavigationBarView -> {
                setSolidBg(v, cfg.backgroundColor)
                if (v.elevation != 0f) v.elevation = 0f
            }
            is AppBarLayout -> {
                setSolidBg(v, cfg.backgroundColor)
                if (v.elevation != 0f) v.elevation = 0f
            }
            is Toolbar -> setSolidBg(v, cfg.backgroundColor)
            // Material on/off switches (SwitchMaterial ⊂ SwitchCompat ⊂ TextView) — must precede the
            // TextView branch. Their style pins a blue tint, so recolour the thumb/track here.
            is SwitchCompat -> KojikiPills.switch(v)
            // Extended FABs (WG create/qr/import speed-dial): invert — background-coloured fill with an
            // accent ring + accent text/icon, so the accent-on-accent content is readable.
            is ExtendedFloatingActionButton -> styleAccentFab(context, v, cfg)
            // Material buttons: a *checkable* one is a segmented toggle (e.g. firewall/log filters) — accent
            // fill + on-accent text when selected, background fill + accent text/border when not. Ordinary
            // (non-checkable) buttons fall through to the normal text styling. The WG SIMPLE/ADVANCED toggle
            // is *not* driven by the group's checked-state (WgMainActivity manually select/unselects it on
            // each tap), so it can't be state-list styled here — the activity restyles it via
            // CustomUi.styleToggleButton instead. Skip those two ids so the walk doesn't clobber it.
            is MaterialButton ->
                if (v.id == R.id.one_wg_toggle_btn || v.id == R.id.wg_general_toggle_btn) Unit
                // The home VPN start/stop button is a MaterialButton on v0.5.5v (its style pins a
                // solid-yellow background drawable). Route it to the outline treatment so it reads as a
                // flat background-colour fill + accent border + accent text, never a solid yellow slab.
                else if (v.id == R.id.fhs_dns_on_off_btn) styleHomeButton(context, v, cfg, global)
                else styleMaterialButton(context, v, cfg, global)
            // Filter chips: selected = accent fill + on-accent text; unselected = background fill + accent
            // text + accent border (state-keyed, so it tracks selection without a re-walk).
            is Chip -> styleChip(context, v, cfg, global)
            // radio buttons and check boxes: selection marks, tinted from their own roles
            is android.widget.CompoundButton -> {
                KojikiPills.mark(v)
                styleText(context, v, global, global)
            }
            is TextView -> when (v.id) {
                // Firewall app-list rows (name / status / traffic) are styled by FirewallAppListAdapter
                // at bind time — reliable + type-aware (user vs system). The tree-walk races with their
                // async bind, so skip them here.
                R.id.firewall_app_label_tv, R.id.firewall_app_id_tv,
                R.id.firewall_app_toggle_other, R.id.firewall_app_data_usage,
                // Snooping-panel rows are styled by SnoopEventAdapter at bind time (race-free); the
                // severity badge/bar and blocked/allowed state keep their semantic colours, so skip
                // them all here.
                R.id.snoop_domain, R.id.snoop_app_name, R.id.snoop_meta, R.id.snoop_state,
                R.id.snoop_severity_badge, R.id.snoop_severity_bar,
                // Network-log rows are styled by ConnectionTrackerAdapter at bind time (race-free);
                // the flag/protocol-badge/status-bar keep their own glyphs/colours, so skip them too.
                R.id.connection_app_name, R.id.connection_ip_address, R.id.connection_domain,
                R.id.connection_response_time, R.id.connection_data_usage, R.id.connection_duration,
                R.id.connection_delay, R.id.conn_latency_txt, R.id.connection_flag,
                R.id.connection_status_indicator,
                // DNS-log rows are styled by DnsLogAdapter at bind time; the flag/type-badge/unicode
                // hints/status-bar keep their own glyphs/colours.
                R.id.dns_query, R.id.dns_app_name, R.id.dns_query_type, R.id.dns_wall_time,
                R.id.dns_ips, R.id.dns_latency, R.id.dns_type_name, R.id.dns_flag,
                R.id.dns_unicode_hint, R.id.dns_status_indicator,
                // The BLOCKED/ALLOWED tags carry semantic colours set by the adapters.
                R.id.connection_block_tag, R.id.dns_block_tag ->
                    Unit
                // The home VPN button: a flat background-coloured fill + accent border + accent text and
                // play/pause + chevron icons, kept identical across the start/stop states (its background
                // drawable is swapped at runtime, but a background tint + a foreground border both survive
                // setBackgroundResource).
                R.id.fhs_dns_on_off_btn -> styleHomeButton(context, v, cfg, global)
                else ->
                    styleText(context, v, global, global)
            }
            // Main "+" FAB: background-coloured fill + accent ring (foreground) + accent "+".
            is FloatingActionButton -> styleAccentFab(context, v, cfg)
            is ImageView -> when (v.id) {
                // Firewall row icon + wifi/data toggles are styled by FirewallAppListAdapter at bind time.
                // The network/DNS-log app icons get their size/roundness from their adapters at bind time.
                R.id.firewall_app_icon_iv, R.id.firewall_app_toggle_wifi, R.id.firewall_app_toggle_mobile_data,
                // The Roaming toggle tints itself by state (KojikiToggleBadge.roaming).
                R.id.firewall_app_toggle_roaming, R.id.ffa_toggle_all_roaming,
                // The per-app note glyph carries state in its tint (accent = has a note, dimmed = "+"),
                // set by KojikiAppNotes.styleGlyph at bind time — a flat accent tint here would erase it.
                R.id.firewall_app_note_iv,
                R.id.connection_app_icon, R.id.dns_app_icon ->
                    Unit
                else -> {
                    // Accent only small VECTOR UI icons. App launcher icons / photos are bitmap or
                    // adaptive drawables and must keep their real colours; a full-bleed illustration is
                    // excluded by the icon-size bound. clearColorFilter also undoes a stale tint when a
                    // RecyclerView row is recycled from a vector icon to a bitmap app-icon.
                    val d = v.drawable
                    val iconMaxPx = 96 * context.resources.displayMetrics.density
                    val smallVector = d != null &&
                            d.javaClass.simpleName.contains("Vector", ignoreCase = true) &&
                            v.width <= iconMaxPx && v.height <= iconMaxPx
                    // an icon is coloured by what its row does (KojikiPills.roleForName)
                    if (smallVector) v.setColorFilter(KojikiPills.iconColor(v)) else v.clearColorFilter()
                }
            }
        }
        // Recolour any view painted with the theme's ?attr/background to the configured background, so
        // the firewall rows / home root / this page follow the Background setting (not just the window).
        if (themeBg != 0 && v !is MaterialCardView) {
            val bg = v.background
            if (bg is ColorDrawable && bg.color == themeBg && bg.color != cfg.backgroundColor) {
                v.setBackgroundColor(cfg.backgroundColor)
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) applyToTree(context, v.getChildAt(i), cfg, global, themeBg)
        }
    }

    // Apply a per-item text style: colour, optional sp size (0 = inherit the global size, then the
    // view's own), and font family ("" = inherit the global family). Weight is the global weight.
    // Equality-guarded so the re-walk never churns layout.
    private fun styleText(
        context: Context, v: TextView, style: CustomUiConfig.TextStyle, global: CustomUiConfig.TextStyle
    ) {
        val family = style.family.ifEmpty { global.family }
        val weight = if (style.weight > 0) style.weight else global.weight
        val tf = typefaceFor(context, family, weight, style.italic)
        if (v.typeface !== tf) v.typeface = tf
        if (v.currentTextColor != style.color) v.setTextColor(style.color)
        val sp = if (style.size > 0) style.size else global.size
        if (sp > 0) {
            val px = sp * context.resources.displayMetrics.scaledDensity
            if (kotlin.math.abs(v.textSize - px) > 0.5f) v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
        }
    }

    // Luminance-based readable foreground for a filled accent: black on a light accent, white on a dark one.
    fun onColorFor(bg: Int): Int {
        val r = (bg shr 16) and 0xFF
        val g = (bg shr 8) and 0xFF
        val b = bg and 0xFF
        val lum = 0.299 * r + 0.587 * g + 0.114 * b
        return if (lum > 140) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }

    // A *checkable* MaterialButton is a segmented toggle (e.g. SIMPLE/ADVANCED): checked = accent fill +
    // on-accent text/icon; unchecked = background fill + accent text/icon + accent border. State-keyed so
    // it tracks selection without a re-walk. Non-checkable buttons get the action-button treatment below.
    private fun styleMaterialButton(
        context: Context, v: MaterialButton, cfg: CustomUiConfig, global: CustomUiConfig.TextStyle
    ) {
        // A checkable button is a segment of a selector — a selection mark, whose fill may show the
        // selected one. Every other button is an action: an outlined pill, never filled.
        if (v.isCheckable) {
            KojikiPills.segment(v)
            val tf = typefaceFor(context, global.family, global.weight, global.italic)
            if (v.typeface !== tf) v.typeface = tf
            return
        }
        KojikiPills.button(v)
        val sp = if (global.size > 0) global.size else 0
        if (sp > 0) {
            val px = sp * context.resources.displayMetrics.scaledDensity
            if (kotlin.math.abs(v.textSize - px) > 0.5f) v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
        }
    }

    // A plain (non-checkable) MaterialButton — the sheets' Clear/Apply, the dialogs' actions. Material
    // fills it from colorPrimary, which under this theme *is* the accent, so accent text on an accent
    // slab renders as a blank yellow brick (the apps-view filter sheet's two buttons). Invert it the way
    // the FABs and the home button are inverted: background fill, accent border, accent label.
    //
    // It sits directly under rows of filter chips, and a pill with a hairline ring would read as one more
    // chip — so an action button is deliberately *not* chip-shaped: a square-ish 8dp radius instead of a
    // pill, a heavy 3dp border against the chips' 1dp, and a bold label.
    //
    // The border is a **foreground** overlay, not MaterialButton's own stroke: the stroke goes through
    // MaterialButtonHelper, which silently no-ops whenever the button's background has been replaced
    // (these two carry an `android:background` drawable in the layout) — the fill went black, the stroke
    // never appeared, and the buttons read as bare floating labels. A foreground is drawn by View itself,
    // so it survives regardless (the same reason styleHomeButton draws its ring that way). The native
    // stroke/radius are still set for the case where the helper *is* live, which keeps the black fill
    // aligned with the border box. Buttons whose background tint is transparent (text buttons) keep their
    // flat look; only their colours follow the accent, else every text link in the app would grow a box.

    // Filter Chip: checked = accent fill + on-accent text/check; unchecked = background fill + accent text
    // + accent border. State-keyed colour lists, so selecting/deselecting flips the colours with no re-walk.
    private fun styleChip(
        context: Context, v: Chip, cfg: CustomUiConfig, global: CustomUiConfig.TextStyle
    ) {
        // A chip is a selection control: the fill marks the selected one.
        val acc = KojikiRoles.color(context, "chip_selected_ground")
        val onAcc = KojikiRoles.color(context, "chip_selected_label")
        val frame = KojikiRoles.color(context, "chip_frame")
        val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        v.chipBackgroundColor =
            ColorStateList(st, intArrayOf(acc, KojikiRoles.color(context, "chip_ground")))
        v.setTextColor(ColorStateList(st, intArrayOf(onAcc, frame)))
        v.chipStrokeColor = ColorStateList.valueOf(frame)
        val sw = 1 * context.resources.displayMetrics.density
        if (v.chipStrokeWidth != sw) v.chipStrokeWidth = sw
        v.checkedIconTint = ColorStateList.valueOf(onAcc)
        val tf = typefaceFor(context, global.family, global.weight, global.italic)
        if (v.typeface !== tf) v.typeface = tf
    }

    // FAB ("+" and the extended speed-dial FABs): background-coloured fill + an accent ring + accent
    // content, so the accent-on-accent "+"/label is readable. A plain FAB has no stroke API, so its ring
    // is a foreground oval; the extended FAB uses its native stroke.
    private fun styleAccentFab(context: Context, v: View, cfg: CustomUiConfig) {
        val ringColor = KojikiRoles.color(context, "fab_ring")
        val glyph = KojikiRoles.color(context, "fab_glyph")
        val bg = KojikiRoles.color(context, "fab_ground")
        val ringPx = (2 * context.resources.displayMetrics.density).toInt()
        when (v) {
            is ExtendedFloatingActionButton -> {
                v.backgroundTintList = ColorStateList.valueOf(bg)
                v.setTextColor(glyph)
                v.iconTint = ColorStateList.valueOf(glyph)
                v.strokeColor = ColorStateList.valueOf(ringColor)
                if (v.strokeWidth != ringPx) v.strokeWidth = ringPx
            }
            is FloatingActionButton -> {
                v.backgroundTintList = ColorStateList.valueOf(bg)
                v.setColorFilter(glyph)
                val ring = v.foreground as? GradientDrawable
                    ?: GradientDrawable().also { v.foreground = it }
                ring.shape = GradientDrawable.OVAL
                ring.setColor(0x00000000)
                ring.setStroke(ringPx, ringColor)
            }
        }
    }

    // The home VPN button: a flat background-coloured fill + accent border + accent text and compound
    // icons (play/pause + chevron), kept the same in both the start and stop states. The button's
    // background drawable is swapped at runtime (home_screen_button_start_bg ↔ _stop_bg), so we tint the
    // background (the tint re-applies to whatever drawable is set) and draw the border as a foreground
    // overlay — both survive setBackgroundResource. Corner radius matches the drawables' 16dp.
    private fun styleHomeButton(
        context: Context, v: TextView, cfg: CustomUiConfig, global: CustomUiConfig.TextStyle
    ) {
        // START / STOP is the home screen's main action: a main pill — 3 dp, bold, never filled.
        val acc = KojikiRoles.color(context, "button_main_frame")
        val label = KojikiRoles.color(context, "button_main_label")
        val d = context.resources.displayMetrics.density
        val tf = typefaceFor(context, global.family, maxOf(global.weight, 700), global.italic)
        if (v.typeface !== tf) v.typeface = tf
        if (v.currentTextColor != label) v.setTextColor(label)
        TextViewCompat.setCompoundDrawableTintList(v, ColorStateList.valueOf(label))
        v.backgroundTintList = ColorStateList.valueOf(KojikiRoles.color(context, "button_ground"))
        if (v is MaterialButton) {
            if (v.cornerRadius != (100 * d).toInt()) v.cornerRadius = (100 * d).toInt()
            if (v.strokeWidth != 0) v.strokeWidth = 0
        }
        val ring = v.foreground as? GradientDrawable ?: GradientDrawable().also { v.foreground = it }
        ring.shape = GradientDrawable.RECTANGLE
        ring.cornerRadius = 100 * d
        ring.setColor(0x00000000)
        ring.setStroke((3 * d).toInt(), acc)
    }

    // WG SIMPLE/ADVANCED toggle (WgMainActivity drives selection itself, restyling on every tap): selected
    // = accent fill + on-accent text; unselected = background fill + accent text + accent border. Called
    // from the activity's select/unselect helpers when the Custom theme is active, so it survives taps
    // (the tree-walk skips these two buttons). Public so the activity can reach it.
    fun styleToggleButton(button: MaterialButton, selected: Boolean) {
        val c = button.context
        val acc = KojikiRoles.color(c, "toggle_on_ground")
        val on = KojikiRoles.color(c, "toggle_on_label")
        val off = KojikiRoles.color(c, "button_label")
        val d = c.resources.displayMetrics.density
        button.backgroundTintList =
            ColorStateList.valueOf(if (selected) acc else KojikiRoles.color(c, "toggle_off_ground"))
        button.setTextColor(if (selected) on else off)
        button.iconTint = ColorStateList.valueOf(if (selected) on else off)
        button.strokeColor = ColorStateList.valueOf(KojikiRoles.color(c, "button_frame"))
        button.strokeWidth = (1 * d).toInt()
    }

    // Firewall app icon: optional fixed size (dp) + corner roundness (0..100 = % of half the icon,
    // so 0 = square, 100 = circle). Size sets are guarded so the requestLayout settles after one pass.
    private fun applyIconStyle(context: Context, v: ImageView, cfg: CustomUiConfig) {
        applyIconSizeRoundness(context, v, cfg.iconSize, cfg.iconRoundness)
    }

    // Shared icon size + roundness applier (firewall + snooping rows). sizeDp 0 = leave layout size;
    // roundness 0..100 = % of half the icon (0 = square, 100 = circle).
    private fun applyIconSizeRoundness(context: Context, v: ImageView, sizeDp: Int, roundness: Int) {
        if (sizeDp > 0) {
            val px = (sizeDp * context.resources.displayMetrics.density).toInt()
            val lp = v.layoutParams
            if (lp != null && (lp.width != px || lp.height != px)) {
                lp.width = px
                lp.height = px
                v.layoutParams = lp
            }
        }
        if (roundness > 0) {
            val pct = roundness.coerceIn(0, 100) / 100f
            v.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val r = minOf(view.width, view.height) / 2f * pct
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
            v.clipToOutline = true
        } else if (v.clipToOutline) {
            v.clipToOutline = false
            v.outlineProvider = ViewOutlineProvider.BACKGROUND
        }
    }

    /** Apply the Snooping-panel icon size + roundness (set in the 白い熊 考直 UI) to a snoop row icon.
     *  Called from SnoopEventAdapter at bind time. No-op (and clears any clip, for recycle-safety) when
     *  the Custom theme is off, so other themes keep the row layout's own 34dp square icon. */
    fun applySnoopIcon(context: Context, v: ImageView) {
        if (!customThemeActive) {
            if (v.clipToOutline) {
                v.clipToOutline = false
                v.outlineProvider = ViewOutlineProvider.BACKGROUND
            }
            return
        }
        val cfg = CustomUiConfig(context)
        applyIconSizeRoundness(context, v, cfg.snoopIconSize, cfg.snoopIconRoundness)
    }

    /** Colour a firewall wifi/data toggle from the adapter (state-accurate + recycle-safe). denied=true
     *  → blocked colour, false → allowed colour, null → unused/inactive (leave its own grey). Clears any
     *  tint (no-op) when the Custom theme isn't active. */
    enum class ToggleState { ALLOWED, DENIED, EXCLUDED, BYPASS_DNS, BYPASS_UNIV, NEUTRAL }

    fun tintToggle(context: Context, v: ImageView, state: ToggleState) {
        if (!customThemeActive) { v.clearColorFilter(); return }
        val cfg = CustomUiConfig(context)
        val color = when (state) {
            ToggleState.ALLOWED -> cfg.fwAllowedColor
            ToggleState.DENIED -> cfg.fwDeniedColor
            ToggleState.EXCLUDED -> cfg.fwExcludedColor
            ToggleState.BYPASS_DNS -> cfg.fwBypassDnsColor
            ToggleState.BYPASS_UNIV -> cfg.fwBypassUnivColor
            ToggleState.NEUTRAL -> 0
        }
        if (color != 0) v.setColorFilter(color) else v.clearColorFilter()
    }

    /** Style a whole firewall app-list row from the adapter — reliable (called at bind time, not via the
     *  racy tree-walk) and type-aware: user-vs-system app name colour, per-item status/traffic, the app
     *  icon size/roundness, the row background, and the divider. No-op when the Custom theme is off (the
     *  freshly-inflated row already carries the theme defaults). */
    fun applyFirewallRow(context: Context, b: ListItemFirewallAppBinding, isSystemApp: Boolean) {
        if (!customThemeActive) return
        val cfg = CustomUiConfig(context)
        val g = cfg.globalStyle()
        val y = CustomUiConfig.PALETTE_YELLOW
        b.root.setBackgroundColor(cfg.backgroundColor)
        val namePrefix = if (isSystemApp) CustomUiConfig.P_FW_NAME_SYSTEM else CustomUiConfig.P_FW_NAME_USER
        // the app name leads the row, so an unset size falls back to the global size scaled up
        // rather than to the global size itself; an explicit per-element size still wins outright
        val nameGlobal =
            if (g.size > 0) g.copy(size = Math.round(g.size * FW_NAME_SIZE_SCALE)) else g
        styleText(context, b.firewallAppLabelTv, cfg.styleOf(namePrefix, y), nameGlobal)
        styleText(context, b.firewallAppToggleOther, cfg.styleOf(CustomUiConfig.P_FW_STATUS, y), g)
        styleText(context, b.firewallAppDataUsage, cfg.styleOf(CustomUiConfig.P_FW_TRAFFIC, y), g)
        applyIconStyle(context, b.firewallAppIconIv, cfg)
        if (cfg.dividerColor != 0) b.firewallAppDivider.setBackgroundColor(cfg.dividerColor)
        if (cfg.dividerThickness > 0) {
            val h = (cfg.dividerThickness * context.resources.displayMetrics.density).toInt()
            val lp = b.firewallAppDivider.layoutParams
            if (lp != null && lp.height != h) { lp.height = h; b.firewallAppDivider.layoutParams = lp }
        }
    }

    /**
     * Fork (白い熊 考直): compose a firewall row's identity — the app name (plus the proxy key) on the
     * label line, and the package id on **its own line beneath**, smaller. The id is what the
     * app-list search matches too (`packageName like :search`), so printing it makes the searchable
     * key visible and tells apart same-named apps.
     *
     * The id used to be appended to the label as a sized span. It moved to its own view so the label
     * line stays short: that line also carries the note pill, and a name+id label left the note only
     * the scraps — while shrinking the label to make room simply cut the app's name off.
     *
     * The id keeps its own element style ([CustomUiConfig.P_FW_APP_ID]) — colour, font family /
     * weight / italic and size are each settable on the 白い熊 考直 UI page. Unset size tracks the
     * app name's size at [ID_SIZE_SCALE]; unset colour is a dimmed copy of the name's colour (so it
     * follows the per-type user/system colours by itself). The name is bolded so the two read
     * apart, unless a weight was explicitly configured for it — an explicit choice wins.
     *
     * Call AFTER [applyFirewallRow]: the fallbacks read the label's final size and colour. Works on
     * every theme; off the Custom theme both fallbacks apply and nothing is configurable.
     */
    fun applyFirewallLabel(
        context: Context,
        tv: TextView,
        idTv: TextView,
        name: String,
        key: String,
        // CharSequence, not String: the id line carries the shared-uid marker as an accented span
        // (see KojikiSharedUid.marker), and setTextColor below leaves a span's own colour standing.
        id: CharSequence,
        isSystemApp: Boolean
    ) {
        val cfg = if (customThemeActive) CustomUiConfig(context) else null
        val namePrefix =
            if (isSystemApp) CustomUiConfig.P_FW_NAME_SYSTEM else CustomUiConfig.P_FW_NAME_USER
        val sb = SpannableStringBuilder(name)
        val nameWeighted = cfg != null && (cfg.styleWeight(namePrefix) > 0 || cfg.fontWeight > 0)
        if (sb.isNotEmpty() && !nameWeighted) {
            sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (key.isNotEmpty()) sb.append(key)
        tv.text = sb

        if (id.isEmpty()) {
            idTv.text = ""
            idTv.visibility = View.GONE
            return
        }
        idTv.visibility = View.VISIBLE
        idTv.text = id
        val st = cfg?.styleOf(CustomUiConfig.P_FW_APP_ID, 0)
        val px =
            if (st != null && st.size > 0) st.size * context.resources.displayMetrics.scaledDensity
            else tv.textSize * ID_SIZE_SCALE
        idTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        idTv.setTextColor(if (st != null && st.color != 0) st.color else dimmed(tv.currentTextColor))
        // only set a typeface when the id declares one — otherwise it inherits the label's
        if (cfg != null && st != null && (st.family.isNotEmpty() || st.weight > 0 || st.italic)) {
            val g = cfg.globalStyle()
            idTv.typeface =
                typefaceFor(
                    context,
                    st.family.ifEmpty { g.family },
                    if (st.weight > 0) st.weight else g.weight,
                    st.italic)
        } else {
            idTv.typeface = tv.typeface
        }
    }

    /** The same colour at [ID_DIM_ALPHA] of its opacity — the app id's default shade. */
    fun dimmed(color: Int): Int {
        val a = ((color ushr 24) * ID_DIM_ALPHA).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0xFFFFFF)
    }

    /** A [Typeface] span that also works below API 28, where `TypefaceSpan(Typeface)` doesn't exist. */
    private class TypefaceSpanCompat(private val tf: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(ds: TextPaint) {
            ds.typeface = tf
        }

        override fun updateMeasureState(p: TextPaint) {
            p.typeface = tf
        }
    }

    /** Style a Snooping-panel row from the adapter — race-free (bind time), like applyFirewallRow.
     *  Domain / app / meta follow the global text style; the severity badge, highlight bar, and the
     *  blocked/allowed state keep the adapter's semantic colours. No-op off the Custom theme (the row
     *  already shows the stock-theme look, which is what other themes want). */
    fun applySnoopRow(context: Context, b: ListItemSnoopBinding) {
        if (!customThemeActive) return
        val cfg = CustomUiConfig(context)
        val g = cfg.globalStyle()
        b.root.setBackgroundColor(cfg.backgroundColor)
        styleText(context, b.snoopDomain, g, g)
        styleText(context, b.snoopAppName, g, g)
        styleText(context, b.snoopMeta, g, g)
        // severity pill text: typeface + colour (P_SNOOP_PILL) + size (snoopPillSize). The badge's
        // background stays the severity colour (set by the adapter); default text colour is white.
        val pill = cfg.styleOf(CustomUiConfig.P_SNOOP_PILL, CustomUiConfig.SNOOP_WHITE)
        val pillFamily = pill.family.ifEmpty { g.family }
        val pillWeight = if (pill.weight > 0) pill.weight else g.weight
        b.snoopSeverityBadge.typeface = typefaceFor(context, pillFamily, pillWeight, pill.italic)
        b.snoopSeverityBadge.setTextColor(pill.color)
        if (cfg.snoopPillSize > 0) {
            b.snoopSeverityBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, cfg.snoopPillSize.toFloat())
        }
        // status text (blocked/allowed): size + font; colour is set per-state by the adapter
        styleFontOnly(context, b.snoopState, cfg.styleOf(CustomUiConfig.P_SNOOP_STATE, 0), g)
        // inter-item spacing: the row's own vertical padding (0 dp = icons nearly touch)
        val rowPad = (cfg.snoopRowPadding * context.resources.displayMetrics.density).toInt()
        b.snoopRow.setPadding(b.snoopRow.paddingLeft, rowPad, b.snoopRow.paddingRight, rowPad)
    }

    /** Whether a log row was blocked / maybe-blocked / allowed — drives the status bar + text tag. */
    enum class LogStatus { BLOCKED, MAYBE_BLOCKED, ALLOWED }

    /**
     * Fork (白い熊 考直): arrange a network-log row's two lines. Runs at the end of every bind of the
     * three adapters that share `list_item_conn_track`, Custom theme or not, because the layout
     * depends on it: line 1 leads with the *name*, line 2 carries the address.
     *
     * - the address gains its port (`80.158.109.4:7275`, `[2a00::1]:443`) — upstream shows a port only
     *   when it can name it ("HTTPS"), and an unnamed one was nowhere on the row;
     * - a connection with no domain would leave line 1 empty, so the address moves up into the
     *   name's place.
     *
     * Both adapters set these two views on every bind, so nothing here can go stale on a recycled row.
     */
    fun packConnLogRow(b: ListItemConnTrackBinding, ipAddress: String, port: Int) {
        if (port > 0 && ipAddress.isNotEmpty() && b.connectionIpAddress.text?.toString() == ipAddress) {
            b.connectionIpAddress.text =
                if (ipAddress.contains(':')) "[$ipAddress]:$port" else "$ipAddress:$port"
        }
        if (b.connectionDomain.visibility != View.VISIBLE || b.connectionDomain.text.isNullOrBlank()) {
            b.connectionDomain.text = b.connectionIpAddress.text
            b.connectionDomain.visibility = View.VISIBLE
            b.connectionIpAddress.text = ""
        }
    }

    /** Style a network-log row at bind time (race-free, like applySnoopRow) — call after
     *  [packConnLogRow]. Line 1 is the eye's anchor: a dim tabular time, then the name in the text
     *  colour with its registrable domain in bold and the subdomain dimmed, an outlined protocol pill
     *  and the flag. Line 2 is smaller and dim (the Explanations colour), except the app name. A
     *  blocked row is washed in the blocked colour as well as carrying the left bar. No-op off the
     *  Custom theme. */
    fun applyConnLogRow(context: Context, b: ListItemConnTrackBinding, status: LogStatus) {
        if (!customThemeActive) return
        val cfg = CustomUiConfig(context)
        val g = cfg.globalStyle()
        val row = rowStyle(g, cfg.connLogTextSize)
        val small = smallStyle(row, g)
        val dim = small.copy(color = cfg.noteColor)
        b.root.setBackgroundColor(logRowBackground(cfg, status))
        styleText(context, b.connectionDomain, row, row)
        highlightHost(b.connectionDomain, cfg.noteColor)
        styleText(context, b.connectionResponseTime, row.copy(color = cfg.noteColor), row)
        tabularDigits(b.connectionResponseTime)
        styleText(context, b.connectionAppName, small, small)
        styleText(context, b.connectionIpAddress, dim, dim)
        styleText(context, b.connectionDataUsage, dim, dim)
        styleText(context, b.connectionDuration, dim, dim)
        styleText(context, b.connectionDelay, small, small)
        styleLogBadge(context, b.connLatencyTxt, cfg, g)
        applyIconSizeRoundness(context, b.connectionAppIcon, cfg.connLogIconSize, cfg.connLogIconRoundness)
        val density = context.resources.displayMetrics.density
        applyLogSpacing(b.connectionScreenLl, b.connectionNameRow, cfg.connLogRowPadding, cfg.connLogLineSpacing, density)
        applyLogStatus(context, b.connectionStatusIndicator, b.connectionBlockTag, status, cfg, g)
        applyLogDivider(context, b.connectionDivider, cfg.connLogDividerWidth, cfg.connLogDividerColor, density)
    }

    /** Style a DNS-log row at bind time — the same two-line scheme as [applyConnLogRow]: time · name
     *  asked · resolver pill · flag, then app · record type · answer · latency · hints. No-op off
     *  the Custom theme. */
    fun applyDnsLogRow(context: Context, b: ListItemDnsLogBinding, status: LogStatus) {
        if (!customThemeActive) return
        val cfg = CustomUiConfig(context)
        val g = cfg.globalStyle()
        val row = rowStyle(g, cfg.dnsLogTextSize)
        val small = smallStyle(row, g)
        val dim = small.copy(color = cfg.noteColor)
        b.root.setBackgroundColor(logRowBackground(cfg, status))
        styleText(context, b.dnsQuery, row, row)
        highlightHost(b.dnsQuery, cfg.noteColor)
        styleText(context, b.dnsWallTime, row.copy(color = cfg.noteColor), row)
        tabularDigits(b.dnsWallTime)
        styleText(context, b.dnsAppName, small, small)
        styleText(context, b.dnsQueryType, dim, dim)
        styleText(context, b.dnsIps, dim, dim)
        styleText(context, b.dnsLatency, dim, dim)
        styleText(context, b.dnsUnicodeHint, small, small)
        styleLogBadge(context, b.dnsTypeName, cfg, g)
        applyIconSizeRoundness(context, b.dnsAppIcon, cfg.dnsLogIconSize, cfg.dnsLogIconRoundness)
        val density = context.resources.displayMetrics.density
        applyLogSpacing(b.dnsScreenLl, b.dnsTypeRow, cfg.dnsLogRowPadding, cfg.dnsLogLineSpacing, density)
        applyLogStatus(context, b.dnsStatusIndicator, b.dnsBlockTag, status, cfg, g)
        applyLogDivider(context, b.dnsDivider, cfg.dnsLogDividerWidth, cfg.dnsLogDividerColor, density)
    }

    // Line 2 of a log row: three sizes down from line 1, never below 10sp.
    private fun smallStyle(row: CustomUiConfig.TextStyle, g: CustomUiConfig.TextStyle): CustomUiConfig.TextStyle {
        val base = if (row.size > 0) row.size else g.size
        return if (base > 0) row.copy(size = maxOf(LOG_SMALL_MIN_SP, base - LOG_SMALL_STEP_SP)) else row
    }

    // A blocked row is washed in its status colour so a run of blocks reads as a band down the list;
    // an allowed row keeps the plain background.
    private fun logRowBackground(cfg: CustomUiConfig, status: LogStatus): Int {
        val (color, share) = when (status) {
            LogStatus.BLOCKED -> cfg.logStatusBlockedColor to 0.16f
            LogStatus.MAYBE_BLOCKED -> cfg.logStatusMaybeColor to 0.10f
            LogStatus.ALLOWED -> return cfg.backgroundColor
        }
        if (color == 0) return cfg.backgroundColor
        val bg = cfg.backgroundColor
        fun mix(shift: Int) =
            (((bg shr shift) and 0xFF) * (1 - share) + ((color shr shift) and 0xFF) * share).toInt()
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    // Row padding (above line 1 / below line 2) and the gap between the two lines. The sliders'
    // 0 is the tightest that still keeps the text off the separator (the rows have no font padding);
    // -1 = "Default", one step roomier.
    private fun applyLogSpacing(box: View, line1: View, rowPadding: Int, lineSpacing: Int, density: Float) {
        val pad = ((if (rowPadding >= 0) LOG_ROW_PAD_BASE_DP + rowPadding else LOG_ROW_PAD_DEFAULT_DP) * density).toInt()
        if (box.paddingTop != pad || box.paddingBottom != pad) {
            box.setPadding(box.paddingLeft, pad, box.paddingRight, pad)
        }
        val gap = ((if (lineSpacing >= 0) lineSpacing else LOG_LINE_GAP_DEFAULT_DP) * density).toInt()
        setVPaddingBottom(line1, gap)
    }

    // Digits of equal width, so the time column lines up down the list in any font.
    private fun tabularDigits(v: TextView) {
        if (v.fontFeatureSettings != "tnum") v.fontFeatureSettings = "tnum"
    }

    // The protocol / resolver pill: unfilled, fully round, thin frame — the black-yellow pill, at
    // label size. Hidden when it has nothing to say rather than drawn as an empty ring.
    private class LogBadgeOutline : GradientDrawable()

    private fun styleLogBadge(context: Context, v: TextView, cfg: CustomUiConfig, g: CustomUiConfig.TextStyle) {
        if (v.text.isNullOrEmpty()) {
            v.visibility = View.GONE
            return
        }
        v.visibility = View.VISIBLE
        styleFontOnly(context, v, fontOnlyStyle(g), g)
        if (v.currentTextColor != g.color) v.setTextColor(g.color)
        val d = context.resources.displayMetrics.density
        val bg = v.background as? LogBadgeOutline ?: LogBadgeOutline().also {
            it.shape = GradientDrawable.RECTANGLE
            it.cornerRadius = 100 * d
            it.setColor(0)
            v.background = it
            v.setPadding((6 * d).toInt(), (1 * d).toInt(), (6 * d).toInt(), (1 * d).toInt())
        }
        bg.setStroke(maxOf(1, (1.25f * d).toInt()), cfg.noteColor)
    }

    // Make the part of a host name that identifies its owner stand out: the registrable domain in
    // bold, everything left of it dimmed — `supl.platform.` dim, `dbankcloud.cn` bold. Addresses and
    // anything that is not a dotted name are left alone.
    private fun highlightHost(v: TextView, dimColor: Int) {
        val s = v.text?.toString() ?: return
        val cut = registrableStart(s)
        if (cut <= 0) return
        val sp = SpannableStringBuilder(s)
        sp.setSpan(ForegroundColorSpan(dimColor), 0, cut, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(StyleSpan(Typeface.BOLD), cut, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        v.text = sp
    }

    // Index where the registrable domain starts, or -1. No public-suffix list: two labels, or three
    // when the last is a country code under a generic second level (`bbc.co.uk`, `nhk.or.jp`).
    private fun registrableStart(host: String): Int {
        if (host.isEmpty() || host.contains(':') || host.contains(' ') || host.none { it.isLetter() }) return -1
        val h = host.trimEnd('.')
        val labels = h.split('.')
        if (labels.size < 3) return -1
        val keep = if (labels.last().length == 2 && labels[labels.size - 2] in GENERIC_SECOND_LEVELS) 3 else 2
        if (labels.size <= keep) return -1
        return h.length - labels.takeLast(keep).sumOf { it.length } - (keep - 1)
    }

    private val GENERIC_SECOND_LEVELS = setOf("co", "com", "org", "net", "ac", "gov", "edu", "or", "ne", "go")
    private const val LOG_SMALL_STEP_SP = 3
    private const val LOG_SMALL_MIN_SP = 10
    private const val LOG_ROW_PAD_BASE_DP = 2
    private const val LOG_ROW_PAD_DEFAULT_DP = 4
    private const val LOG_LINE_GAP_DEFAULT_DP = 1

    // Vertical-padding / vertical-margin setters used to collapse a log row's fixed spacing (so the
    // inter-item-spacing slider can reach a truly tight 0). Equality-guarded against relayout churn.
    private fun setVPaddingTop(v: View, top: Int) {
        if (v.paddingTop != top) v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
    }

    private fun setVPaddingBottom(v: View, bottom: Int) {
        if (v.paddingBottom != bottom) v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottom)
    }

    private fun setVMargin(v: View, m: Int) {
        val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.topMargin != m || lp.bottomMargin != m) {
            lp.topMargin = m
            lp.bottomMargin = m
            v.layoutParams = lp
        }
    }

    // Set a view's top & bottom padding, keeping its horizontal padding (for the protocol/type badge,
    // whose vertical padding inflates the line).
    private fun setVPaddingBoth(v: View, vpad: Int) {
        if (v.paddingTop != vpad || v.paddingBottom != vpad) {
            v.setPadding(v.paddingLeft, vpad, v.paddingRight, vpad)
        }
    }

    // Set a view's layout height (for the per-item divider line). 0 = no line.
    private fun setHeight(v: View, h: Int) {
        val lp = v.layoutParams ?: return
        if (lp.height != h) { lp.height = h; v.layoutParams = lp }
    }

    // Per-item divider line under a log row. width -1 = "Default" (restore the as-shipped 1dp theme
    // line); >= 0 = explicit dp (0 = no line) painted in the chosen colour. Always definite (recycle-safe).
    private fun applyLogDivider(context: Context, divider: View, width: Int, color: Int, density: Float) {
        if (width >= 0) {
            setHeight(divider, (width * density).toInt())
            divider.setBackgroundColor(color)
        } else {
            // Default: a one-pixel hairline in the dim Explanations colour — visible on black,
            // thinner than any whole-dp setting.
            setHeight(divider, 1)
            divider.setBackgroundColor(CustomUiConfig(context).noteColor)
        }
    }

    // The left status bar + the optional BLOCKED/MAYBE/ALLOWED text tag, both keyed off the row's
    // status. Overrides the adapter's default bar (run after it at bind time, under the Custom theme).
    private fun applyLogStatus(
        context: Context, bar: TextView, tag: TextView, status: LogStatus,
        cfg: CustomUiConfig, g: CustomUiConfig.TextStyle
    ) {
        val density = context.resources.displayMetrics.density
        val color = when (status) {
            LogStatus.BLOCKED -> cfg.logStatusBlockedColor
            LogStatus.MAYBE_BLOCKED -> cfg.logStatusMaybeColor
            LogStatus.ALLOWED -> cfg.logStatusAllowedColor
        }
        if (cfg.logStatusBarWidth > 0) {
            val w = (cfg.logStatusBarWidth * density).toInt()
            val lp = bar.layoutParams
            if (lp != null && lp.width != w) { lp.width = w; bar.layoutParams = lp }
        }
        // 0 = no bar for this state (e.g. allowed, by default); else paint it and show it.
        if (color != 0) {
            bar.setBackgroundColor(color)
            bar.visibility = View.VISIBLE
        } else {
            bar.visibility = View.INVISIBLE
        }
        if (cfg.logTagShow) {
            val labelRes = when (status) {
                LogStatus.BLOCKED -> R.string.snoop_state_blocked
                LogStatus.MAYBE_BLOCKED -> R.string.lbl_maybe_blocked
                LogStatus.ALLOWED -> R.string.snoop_state_allowed
            }
            tag.text = context.getString(labelRes).uppercase()
            tag.setTextColor(if (color != 0) color else g.color)
            tag.typeface = typefaceFor(context, g.family, g.weight, g.italic)
            val sp = if (cfg.logTagSize > 0) cfg.logTagSize else DEFAULT_LOG_TAG_SP
            tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
            tag.visibility = View.VISIBLE
        } else {
            tag.visibility = View.GONE
        }
    }

    private const val DEFAULT_LOG_TAG_SP = 10

    // The global style, optionally with a per-log size override (sp; 0 = keep the global size).
    private fun rowStyle(g: CustomUiConfig.TextStyle, sizeOverride: Int) =
        if (sizeOverride > 0) g.copy(size = sizeOverride) else g

    // A copy of the global style with size 0, so styleFontOnly applies only the family/weight/italic
    // (the view keeps its own size + colour) — for coloured badges that should still take the font.
    private fun fontOnlyStyle(g: CustomUiConfig.TextStyle) =
        CustomUiConfig.TextStyle(0, 0, g.family, g.weight, g.italic)

    /** Build the severity-pill background: a rounded rect filled with the severity colour, with a
     *  configurable corner radius + optional border. Replaces the static bg_snoop_badge so radius/
     *  border/width can be driven from the 白い熊 考直 UI (and the preview can mirror it). */
    fun snoopPillBackground(
        context: Context, fill: Int, radiusDp: Int, borderWidthDp: Int, borderColor: Int
    ): GradientDrawable {
        val d = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radiusDp * d
            if (borderWidthDp > 0) setStroke((borderWidthDp * d).toInt(), borderColor)
        }
    }

    // Like styleText but never touches the colour or the view's own size (size 0 = leave it) — for text
    // whose colour carries meaning (e.g. the blocked/allowed state, coloured by the adapter).
    private fun styleFontOnly(
        context: Context, v: TextView, style: CustomUiConfig.TextStyle, global: CustomUiConfig.TextStyle
    ) {
        val family = style.family.ifEmpty { global.family }
        val weight = if (style.weight > 0) style.weight else global.weight
        val tf = typefaceFor(context, family, weight, style.italic)
        if (v.typeface !== tf) v.typeface = tf
        if (style.size > 0) {
            val px = style.size * context.resources.displayMetrics.scaledDensity
            if (kotlin.math.abs(v.textSize - px) > 0.5f) {
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.size.toFloat())
            }
        }
    }

    /** A themed dropdown menu item. id is returned to the caller on selection. */
    /**
     * One row of [showMenu]. A menu in which any row carries an [icon] is an **action menu**: every
     * row is a full-width outlined pill with its icon inside at the left, coloured by [role] (what
     * the row does), a › at the right when it [opens] something, and frame, icon and label in the
     * warning red when it is [danger]. A menu with no icons at all is a pure **choice list** (a
     * sort order, a filter): plain rows, the current one marked with a ✓.
     */
    data class MenuItem(
        val id: Int,
        val title: String,
        val checked: Boolean = false,
        val icon: Int = 0,
        val role: String = "icon_view",
        val opens: Boolean = false,
        val danger: Boolean = false,
        val dividerBefore: Boolean = false
    )

    /**
     * Show a popup menu in the black-yellow menu look (see [MenuItem]). Off the Custom theme it
     * keeps the activity theme's colours. The menu is shifted up by what is missing below its
     * anchor, so it does not scroll while the screen has room.
     */
    fun showMenu(anchor: View, items: List<MenuItem>, onSelect: (Int) -> Unit) {
        val ctx = anchor.context
        val cfg = CustomUiConfig(ctx)
        val active = customThemeActive
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * d + 0.5f).toInt()
        val global = cfg.globalStyle()
        val menuStyle = cfg.styleOf(CustomUiConfig.P_SNOOP_MENU, CustomUiConfig.PALETTE_YELLOW)
        val labelColor =
            if (active) KojikiRoles.color(ctx, "menu_label")
            else resolveThemeColor(ctx, android.R.attr.textColorPrimary)
        val dangerColor = if (active) KojikiRoles.color(ctx, "menu_danger") else 0xFFFF0000.toInt()
        val bgColor =
            if (active) KojikiRoles.color(ctx, "menu_ground")
            else resolveThemeColor(ctx, android.R.attr.colorBackground)
        val actions = items.any { it.icon != 0 }

        fun styleLabel(tv: TextView, color: Int) {
            tv.setTextColor(color)
            tv.textSize = 15f
            if (active) {
                val family = menuStyle.family.ifEmpty { global.family }
                val weight = if (menuStyle.weight > 0) menuStyle.weight else global.weight
                tv.typeface = typefaceFor(ctx, family, weight, menuStyle.italic)
                val sp = if (menuStyle.size > 0) menuStyle.size else global.size
                if (sp > 0) tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
            }
        }

        val column = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, dp(5f), 0, dp(5f))
        }
        val scroll = android.widget.ScrollView(ctx).apply { addView(column) }
        val popup = android.widget.PopupWindow(ctx)

        for (item in items) {
            if (item.dividerBefore) {
                column.addView(
                    View(ctx).apply {
                        setBackgroundColor(
                            if (active) KojikiRoles.color(ctx, "group_divider") else labelColor)
                    },
                    android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1f).coerceAtLeast(1)).also {
                        it.setMargins(dp(8f), dp(4f), dp(8f), dp(4f))
                    })
            }
            val color = if (item.danger) dangerColor else labelColor
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true
                setOnClickListener {
                    popup.dismiss()
                    onSelect(item.id)
                }
            }
            if (actions) {
                row.minimumHeight = dp(42f)
                row.setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
                val frame = GradientDrawable().apply {
                    cornerRadius = 100 * d
                    setColor(0x00000000)
                    setStroke(dp(1.25f).coerceAtLeast(1), color)
                }
                row.background =
                    android.graphics.drawable.RippleDrawable(
                        ColorStateList.valueOf(KojikiRoles.al(color, 0.3f)), frame, null)
                // one icon column, so the labels share one left edge even where an icon is missing
                row.addView(
                    ImageView(ctx).apply {
                        if (item.icon != 0) {
                            setImageDrawable(
                                androidx.appcompat.content.res.AppCompatResources
                                    .getDrawable(ctx, item.icon)?.mutate())
                            setColorFilter(
                                if (item.danger) dangerColor
                                else if (active) KojikiRoles.color(ctx, item.role) else labelColor)
                        }
                    },
                    android.widget.LinearLayout.LayoutParams(dp(20f), dp(20f)).also {
                        it.marginEnd = dp(12f)
                    })
                row.addView(
                    TextView(ctx).apply {
                        text = item.title
                        styleLabel(this, color)
                    },
                    android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (item.opens) {
                    row.addView(
                        TextView(ctx).apply {
                            text = "\u203A"
                            setTextColor(color)
                            textSize = 20f
                            includeFontPadding = false
                            setPadding(dp(10f), 0, 0, 0)
                        })
                }
                column.addView(
                    row,
                    android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                        it.setMargins(dp(8f), dp(3f), dp(8f), dp(3f))
                    })
            } else {
                row.setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
                row.addView(
                    TextView(ctx).apply {
                        text = if (item.checked) "\u2713" else ""
                        styleLabel(this, color)
                        minWidth = dp(24f)
                    })
                row.addView(
                    TextView(ctx).apply {
                        text = item.title
                        styleLabel(this, color)
                        if (item.checked) setTypeface(typeface, Typeface.BOLD)
                    })
                column.addView(row)
            }
        }

        val screen = android.graphics.Rect()
        anchor.getWindowVisibleDisplayFrame(screen)
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        column.measure(unspecified, unspecified)
        val width =
            (column.measuredWidth + dp(28f)).coerceIn(dp(200f), (screen.width() * 0.9f).toInt())
        column.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), unspecified)
        val wanted = column.measuredHeight
        val height = minOf(wanted, screen.height() - dp(16f))
        val at = IntArray(2)
        anchor.getLocationOnScreen(at)
        val below = screen.bottom - (at[1] + anchor.height) - dp(8f)
        // a stock popup gets only the room below its anchor and scrolls with half the screen free
        val shiftUp = if (height > below) -(height - below) else 0

        popup.contentView = scroll
        popup.width = width
        popup.height = height
        popup.isFocusable = true
        popup.isOutsideTouchable = true
        popup.elevation = 8 * d
        popup.setBackgroundDrawable(
            GradientDrawable().apply {
                cornerRadius = 12f * d
                setColor(bgColor)
                if (active && cfg.menuBorderWidth > 0) {
                    setStroke((cfg.menuBorderWidth * d).toInt(), cfg.menuBorderColor)
                }
            })
        popup.showAsDropDown(anchor, 0, shiftUp)
    }

    // Tint a Material on/off switch: checked = the configured switch colour, unchecked = grey.

    // Resolve a theme colour attribute (e.g. R.attr.background) to an int, following a colour reference.
    private fun resolveThemeColor(context: Context, attr: Int): Int {
        val tv = TypedValue()
        if (!context.theme.resolveAttribute(attr, tv, true)) return 0
        return if (tv.resourceId != 0) ContextCompat.getColor(context, tv.resourceId) else tv.data
    }

    // Set a flat background colour only when it isn't already that colour (avoids relayout churn).
    private fun setSolidBg(v: View, color: Int) {
        if ((v.background as? ColorDrawable)?.color != color) v.setBackgroundColor(color)
    }

    // --- Fork (白い熊 考直): borders for dialog / bottom-sheet surfaces. The activity tree-walk never
    // reaches these — a dialog and a sheet each live in their own window — so they are painted from
    // their own entry points, after the dialog/sheet is shown. ---

    /**
     * Fork (白い熊 考直): paint a **Material alert's own outer container** with the bordered box.
     *
     * This is the mechanism that gives upstream's ~144 dialogs the fork's look without rewriting any
     * of them (they are routed through [KojikiAlertDialogBuilder], which calls this on attach).
     * Painting a plain View is deterministic — unlike the two theme attributes, which either get
     * replaced by Material at show time (`android:windowBackground`) or are applied to each panel
     * separately and render three stacked boxes (`android:background`).
     *
     * `parentPanel` is AppCompat's single outer container. Two things had to be true for the border
     * to actually show (found on 0.5.6+028, where every alert came up borderless): the stroke is the
     * panel's **foreground**, never part of its background — every view inflated under
     * `App.Dialog.NoDim` carries the theme's `android:background` (`?attr/colorSurface`), so the
     * title row, the message scroller and the button bar each painted an opaque full-width rectangle
     * over a background stroke and only a sliver survived at the corners — and those theme-painted
     * fills are stripped, so the box shows the *configured* fill rather than the palette's static
     * one. The panel also clips its children to the box's outline, so nothing inflated later (list
     * rows, a custom view) can poke a square corner out of it.
     */
    fun themeAlertSurface(dialog: android.app.Dialog) {
        if (!customThemeActive) return
        val window = dialog.window ?: return
        val root =
            window.findViewById<View>(androidx.appcompat.R.id.parentPanel)
                ?: window.findViewById<View>(android.R.id.content)
                ?: return
        val cfg = CustomUiConfig(dialog.context)
        for (id in ALERT_INNER_PANELS) window.findViewById<View>(id)?.background = null
        stripThemeFills(root, themeBackgroundColor(dialog.context))
        paintDialogBox(root, cfg)
        // The Material surface under the panel: same fill, no elevation overlay — so nothing grey
        // or olive can peek out where its own (28 dp) rounding differs from the box's.
        recolourSurfaceShape(window.decorView.background, cfg.backgroundColor)
        // the buttons are pills too: positive = the main action, negative / neutral regular
        window.findViewById<android.widget.Button>(android.R.id.button1)?.let { KojikiDialog.stylePill(it, true) }
        window.findViewById<android.widget.Button>(android.R.id.button2)?.let { KojikiDialog.stylePill(it, false) }
        window.findViewById<android.widget.Button>(android.R.id.button3)?.let { KojikiDialog.stylePill(it, false) }
        root.invalidate()
    }

    private val ALERT_INNER_PANELS = intArrayOf(
        androidx.appcompat.R.id.topPanel,
        androidx.appcompat.R.id.contentPanel,
        androidx.appcompat.R.id.customPanel,
        androidx.appcompat.R.id.buttonPanel
    )

    /**
     * Fork (白い熊 考直): a dialog whose surface is a [MaterialCardView] inside a transparent window
     * (upstream's SSID dialogs): theme its content and make the outermost card the bordered box.
     * Call after the content view exists. No-op off the Custom theme.
     */
    fun themeCardDialog(root: View) {
        if (!customThemeActive) return
        applyToDialogTree(root)
        val card = findCard(root) ?: return
        paintDialogBox(card, CustomUiConfig(root.context))
    }

    private fun findCard(v: View): MaterialCardView? {
        if (v is MaterialCardView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findCard(v.getChildAt(i))?.let { return it }
        return null
    }

    private const val DIALOG_BOX_RADIUS_DP = 18
    private const val DIALOG_BOX_STROKE_DP = 2

    /**
     * The fork's dialog box on [v] — the same geometry as [KojikiDialog]'s: an 18 dp rounded fill in
     * the configured background colour with a 2 dp accent stroke. The stroke goes in the
     * **foreground** so no child can paint over it, and the children are clipped to the rounded
     * outline. A [MaterialCardView] owns its background, so it gets the same box through its own API
     * (its stroke is already drawn above the content).
     */
    private fun paintDialogBox(v: View, cfg: CustomUiConfig) {
        val d = v.resources.displayMetrics.density
        val radius = DIALOG_BOX_RADIUS_DP * d
        val stroke = maxOf(2, (DIALOG_BOX_STROKE_DP * d).toInt())
        val border = KojikiRoles.color(v.context, "dialog_frame")
        val ground = KojikiRoles.color(v.context, "dialog_ground")
        if (v is MaterialCardView) {
            v.setCardBackgroundColor(ground)
            v.cardElevation = 0f
            v.maxCardElevation = 0f
            v.useCompatPadding = false
            v.radius = radius
            v.strokeWidth = stroke
            v.strokeColor = border
            return
        }
        v.background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(ground)
        }
        v.foreground = GradientDrawable().apply {
            cornerRadius = radius
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke(stroke, border)
        }
        v.clipToOutline = true
    }

    /** The colour a theme's `android:background` paints on every view inflated under it, or 0 when
     *  the theme sets none (or sets a drawable). */
    private fun themeBackgroundColor(context: Context): Int {
        val tv = TypedValue()
        if (!context.theme.resolveAttribute(android.R.attr.background, tv, true)) return 0
        val isColor = tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT
        return if (isColor) tv.data else 0
    }

    /** Drop the flat fills a dialog theme's `android:background` stamped on every inflated view, so
     *  the box underneath is what shows. Only that exact colour goes; a card, a button, a ripple or
     *  anything an owner set on purpose is untouched. */
    private fun stripThemeFills(v: View, fill: Int) {
        if (fill == 0) return
        if (v !is MaterialCardView) {
            val bg = v.background
            if (bg is ColorDrawable && bg.color == fill) v.background = null
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) stripThemeFills(v.getChildAt(i), fill)
    }

    /** Recolour the shape inside a window background (Material's inset surface, or a plain inset
     *  shape) to a flat [color], unwrapping insets and layers on the way. Unknown drawables are
     *  left alone. */
    private fun recolourSurfaceShape(drawable: Drawable?, color: Int) {
        when (drawable) {
            null -> return
            is MaterialShapeDrawable -> {
                drawable.fillColor = ColorStateList.valueOf(color)
                drawable.elevation = 0f
            }
            is GradientDrawable -> (drawable.mutate() as GradientDrawable).setColor(color)
            is DrawableWrapper -> recolourSurfaceShape(drawable.drawable, color)
            is LayerDrawable ->
                for (i in 0 until drawable.numberOfLayers) recolourSurfaceShape(drawable.getDrawable(i), color)
            else -> Unit
        }
    }

    /** Give a BottomSheetDialog's panel the custom look: an accent border + black fill, top-rounded.
     *  Call from the sheet's onStart (after the panel exists). No-op off the Custom theme. */
    fun themeBottomSheet(dialog: android.app.Dialog) {
        if (!customThemeActive) return
        val sheet =
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val cfg = CustomUiConfig(dialog.context)
        val d = dialog.context.resources.displayMetrics.density
        val r = 16 * d
        // Plain black, top-rounded panel — NO stroke here. A full-width panel's side strokes land at the
        // screen edge (clipped/invisible), so the caller draws the visible border on an INSET content box
        // instead (see ExportImportBottomSheet). This just kills the Material grey/elevation behind it.
        sheet.background = GradientDrawable().apply {
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(KojikiRoles.color(dialog.context, "sheet_ground"))
        }
        sheet.invalidate()
    }
}
