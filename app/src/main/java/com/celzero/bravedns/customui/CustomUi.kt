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
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
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

    private val FONT_EXTENSIONS = setOf("ttf", "otf")
    private const val SEMIBOLD_WEIGHT = 600
    private val typefaceCache = HashMap<String, Typeface>()
    // Cache for weight/italic-derived typefaces (Typeface.create returns a new instance each call, which
    // would defeat the equality-guarded re-walk), keyed "family|weight|italic".
    private val styledCache = HashMap<String, Typeface>()

    // One global-layout listener per content view, so re-resumes never stack listeners. Keyed weakly
    // by the content view, so the entry (and listener) is collected with the activity.
    private val layoutListeners =
        java.util.WeakHashMap<View, ViewTreeObserver.OnGlobalLayoutListener>()

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

    private fun fontsDir(context: Context): File =
        File(context.applicationContext.filesDir, "kojiki_fonts").apply { mkdirs() }

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
        if (!layoutListeners.containsKey(content)) {
            val vto = content.viewTreeObserver
            if (vto.isAlive) {
                val listener = ViewTreeObserver.OnGlobalLayoutListener {
                    applyToTree(activity, content, cfg, global, themeBg)
                }
                layoutListeners[content] = listener
                vto.addOnGlobalLayoutListener(listener)
            }
        }
    }

    private fun applyToTree(context: Context, v: View, cfg: CustomUiConfig, global: CustomUiConfig.TextStyle, themeBg: Int) {
        // Opt-out: leave this view (and its subtree) exactly as set by its owner — e.g. the
        // 白い熊 考直 UI preview widgets, which drive their own pill/status size.
        if (v.tag == NO_RESTYLE_TAG) return
        when (v) {
            // Material containers carry the tonal "surface tint" (= colorPrimary = yellow) that turns
            // elevated surfaces olive. Force their fill to the background colour and zero their
            // elevation so no tint is composited — flat black, regardless of the Material version.
            is MaterialCardView -> {
                if (v.cardBackgroundColor.defaultColor != cfg.surfaceColor) {
                    v.setCardBackgroundColor(cfg.surfaceColor)
                }
                if (v.cardElevation != 0f) v.cardElevation = 0f
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
            is SwitchCompat -> applySwitchTint(v, cfg.switchColor)
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
                else styleMaterialButton(context, v, cfg, global)
            // Filter chips: selected = accent fill + on-accent text; unselected = background fill + accent
            // text + accent border (state-keyed, so it tracks selection without a re-walk).
            is Chip -> styleChip(context, v, cfg, global)
            is TextView -> when (v.id) {
                // Firewall app-list rows (name / status / traffic) are styled by FirewallAppListAdapter
                // at bind time — reliable + type-aware (user vs system). The tree-walk races with their
                // async bind, so skip them here.
                R.id.firewall_app_label_tv, R.id.firewall_app_toggle_other, R.id.firewall_app_data_usage,
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
                R.id.dns_unicode_hint, R.id.dns_status_indicator ->
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
                    if (smallVector) v.setColorFilter(cfg.accentColor) else v.clearColorFilter()
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
    private fun onColorFor(bg: Int): Int {
        val r = (bg shr 16) and 0xFF
        val g = (bg shr 8) and 0xFF
        val b = bg and 0xFF
        val lum = 0.299 * r + 0.587 * g + 0.114 * b
        return if (lum > 140) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }

    // A *checkable* MaterialButton is a segmented toggle (e.g. SIMPLE/ADVANCED): checked = accent fill +
    // on-accent text/icon; unchecked = background fill + accent text/icon + accent border. State-keyed so
    // it tracks selection without a re-walk. Non-checkable buttons keep the ordinary text styling.
    private fun styleMaterialButton(
        context: Context, v: MaterialButton, cfg: CustomUiConfig, global: CustomUiConfig.TextStyle
    ) {
        if (!v.isCheckable) { styleText(context, v, global, global); return }
        val acc = cfg.accentColor
        val onAcc = onColorFor(acc)
        val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        v.backgroundTintList = ColorStateList(st, intArrayOf(acc, cfg.backgroundColor))
        v.setTextColor(ColorStateList(st, intArrayOf(onAcc, acc)))
        v.iconTint = ColorStateList(st, intArrayOf(onAcc, acc))
        v.strokeColor = ColorStateList.valueOf(acc)
        val sp = (1 * context.resources.displayMetrics.density).toInt()
        if (v.strokeWidth != sp) v.strokeWidth = sp
        val tf = typefaceFor(context, global.family, global.weight, global.italic)
        if (v.typeface !== tf) v.typeface = tf
    }

    // Filter Chip: checked = accent fill + on-accent text/check; unchecked = background fill + accent text
    // + accent border. State-keyed colour lists, so selecting/deselecting flips the colours with no re-walk.
    private fun styleChip(
        context: Context, v: Chip, cfg: CustomUiConfig, global: CustomUiConfig.TextStyle
    ) {
        val acc = cfg.accentColor
        val onAcc = onColorFor(acc)
        val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        v.chipBackgroundColor = ColorStateList(st, intArrayOf(acc, cfg.backgroundColor))
        v.setTextColor(ColorStateList(st, intArrayOf(onAcc, acc)))
        v.chipStrokeColor = ColorStateList.valueOf(acc)
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
        val acc = cfg.accentColor
        val bg = cfg.backgroundColor
        val ringPx = (2 * context.resources.displayMetrics.density).toInt()
        when (v) {
            is ExtendedFloatingActionButton -> {
                v.backgroundTintList = ColorStateList.valueOf(bg)
                v.setTextColor(acc)
                v.iconTint = ColorStateList.valueOf(acc)
                v.strokeColor = ColorStateList.valueOf(acc)
                if (v.strokeWidth != ringPx) v.strokeWidth = ringPx
            }
            is FloatingActionButton -> {
                v.backgroundTintList = ColorStateList.valueOf(bg)
                v.setColorFilter(acc)
                if (v.foreground !is GradientDrawable) {
                    v.foreground = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0x00000000)
                        setStroke(ringPx, acc)
                    }
                } else {
                    (v.foreground as GradientDrawable).setStroke(ringPx, acc)
                }
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
        val acc = cfg.accentColor
        val d = context.resources.displayMetrics.density
        val tf = typefaceFor(context, global.family, global.weight, global.italic)
        if (v.typeface !== tf) v.typeface = tf
        if (v.currentTextColor != acc) v.setTextColor(acc)
        TextViewCompat.setCompoundDrawableTintList(v, ColorStateList.valueOf(acc))
        v.backgroundTintList = ColorStateList.valueOf(cfg.backgroundColor)
        if (v.foreground !is GradientDrawable) {
            v.foreground = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * d
                setColor(0x00000000)
                setStroke((1.5f * d).toInt(), acc)
            }
        } else {
            (v.foreground as GradientDrawable).setStroke((1.5f * d).toInt(), acc)
        }
    }

    // WG SIMPLE/ADVANCED toggle (WgMainActivity drives selection itself, restyling on every tap): selected
    // = accent fill + on-accent text; unselected = background fill + accent text + accent border. Called
    // from the activity's select/unselect helpers when the Custom theme is active, so it survives taps
    // (the tree-walk skips these two buttons). Public so the activity can reach it.
    fun styleToggleButton(button: MaterialButton, selected: Boolean) {
        val cfg = CustomUiConfig(button.context)
        val acc = cfg.accentColor
        val d = button.context.resources.displayMetrics.density
        button.backgroundTintList =
            ColorStateList.valueOf(if (selected) acc else cfg.backgroundColor)
        button.setTextColor(if (selected) onColorFor(acc) else acc)
        button.iconTint = ColorStateList.valueOf(if (selected) onColorFor(acc) else acc)
        button.strokeColor = ColorStateList.valueOf(acc)
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
        styleText(context, b.firewallAppLabelTv, cfg.styleOf(namePrefix, y), g)
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

    /** Style a network-log (connection tracker) row from the adapter — race-free (bind time), like
     *  applySnoopRow. The app name / ip / domain / time / data-usage follow the global text style; the
     *  app icon gets the configured size + roundness; the protocol badge keeps its colour but takes the
     *  global font. No-op off the Custom theme. */
    fun applyConnLogRow(context: Context, b: ListItemConnTrackBinding) {
        if (!customThemeActive) return
        val cfg = CustomUiConfig(context)
        val g = cfg.globalStyle()
        b.root.setBackgroundColor(cfg.backgroundColor)
        styleText(context, b.connectionAppName, g, g)
        styleText(context, b.connectionIpAddress, g, g)
        styleText(context, b.connectionDomain, g, g)
        styleText(context, b.connectionResponseTime, g, g)
        styleText(context, b.connectionDataUsage, g, g)
        styleText(context, b.connectionDuration, g, g)
        styleText(context, b.connectionDelay, g, g)
        // protocol badge keeps its purple bg + own size/colour; only the font follows the global.
        styleFontOnly(context, b.connLatencyTxt, fontOnlyStyle(g), g)
        applyIconSizeRoundness(context, b.connectionAppIcon, cfg.connLogIconSize, cfg.connLogIconRoundness)
        val pad = (cfg.connLogRowPadding * context.resources.displayMetrics.density).toInt()
        b.connectionParentLayout.setPadding(
            b.connectionParentLayout.paddingLeft, pad, b.connectionParentLayout.paddingRight, pad
        )
    }

    /** Style a DNS-log row from the adapter — race-free (bind time), like applySnoopRow. The query /
     *  app name / time / ips / latency follow the global text style; the app icon gets the configured
     *  size + roundness; the DNS-type badge keeps its colour but takes the global font. No-op off the
     *  Custom theme. */
    fun applyDnsLogRow(context: Context, b: ListItemDnsLogBinding) {
        if (!customThemeActive) return
        val cfg = CustomUiConfig(context)
        val g = cfg.globalStyle()
        b.root.setBackgroundColor(cfg.backgroundColor)
        styleText(context, b.dnsQuery, g, g)
        styleText(context, b.dnsAppName, g, g)
        styleText(context, b.dnsQueryType, g, g)
        styleText(context, b.dnsWallTime, g, g)
        styleText(context, b.dnsIps, g, g)
        styleText(context, b.dnsLatency, g, g)
        // DNS-type badge keeps its purple bg + own size/colour; only the font follows the global.
        styleFontOnly(context, b.dnsTypeName, fontOnlyStyle(g), g)
        applyIconSizeRoundness(context, b.dnsAppIcon, cfg.dnsLogIconSize, cfg.dnsLogIconRoundness)
        val pad = (cfg.dnsLogRowPadding * context.resources.displayMetrics.density).toInt()
        b.dnsParentLayout.setPadding(
            b.dnsParentLayout.paddingLeft, pad, b.dnsParentLayout.paddingRight, pad
        )
    }

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
    data class MenuItem(val id: Int, val title: String, val checked: Boolean = false)

    /** Show a popup menu themed by the 白い熊 考直 UI: configurable accent border (colour + thickness),
     *  surface fill, and item text colour + font. Off the Custom theme it falls back to the activity
     *  theme's colours and no border. Replaces android.widget.PopupMenu so the border/colour/font can be
     *  driven dynamically from config (a static menu style can't). */
    fun showMenu(anchor: View, items: List<MenuItem>, onSelect: (Int) -> Unit) {
        val ctx = anchor.context
        val cfg = CustomUiConfig(ctx)
        val active = customThemeActive
        val density = ctx.resources.displayMetrics.density
        val global = cfg.globalStyle()
        val menuStyle = cfg.styleOf(CustomUiConfig.P_SNOOP_MENU, CustomUiConfig.PALETTE_YELLOW)
        val textColor =
            if (active) menuStyle.color else resolveThemeColor(ctx, android.R.attr.textColorPrimary)
        val bgColor =
            if (active) cfg.surfaceColor else resolveThemeColor(ctx, android.R.attr.colorBackground)

        val bg = GradientDrawable().apply {
            cornerRadius = 10f * density
            setColor(bgColor)
            if (active && cfg.menuBorderWidth > 0) {
                setStroke((cfg.menuBorderWidth * density).toInt(), cfg.menuBorderColor)
            }
        }

        val adapter = object : ArrayAdapter<MenuItem>(ctx, 0, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = (convertView as? TextView) ?: TextView(ctx).apply {
                    // LayoutParams are required: setText() reuses the view across the measure pass and
                    // calls checkForRelayout(), which dereferences layoutParams.width (NPE if null).
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    val ph = (16 * density).toInt()
                    val pv = (12 * density).toInt()
                    setPadding(ph, pv, ph, pv)
                }
                val item = items[position]
                tv.text = if (item.checked) "✓  ${item.title}" else " ${item.title}"
                tv.setTextColor(textColor)
                if (active) {
                    val family = menuStyle.family.ifEmpty { global.family }
                    val weight = if (menuStyle.weight > 0) menuStyle.weight else global.weight
                    tv.typeface = typefaceFor(ctx, family, weight, menuStyle.italic)
                    val sp = if (menuStyle.size > 0) menuStyle.size else global.size
                    if (sp > 0) tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
                }
                return tv
            }
        }

        val lpw = ListPopupWindow(ctx)
        lpw.anchorView = anchor
        lpw.setBackgroundDrawable(bg)
        lpw.isModal = true
        lpw.setAdapter(adapter)
        lpw.width = measureMenuWidth(ctx, adapter, density)
        lpw.setOnItemClickListener { _, _, pos, _ ->
            lpw.dismiss()
            onSelect(items[pos].id)
        }
        lpw.show()
    }

    private fun measureMenuWidth(context: Context, adapter: ArrayAdapter<MenuItem>, density: Float): Int {
        var max = (160 * density).toInt()
        val parent = FrameLayout(context)
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        var convert: View? = null
        for (i in 0 until adapter.count) {
            convert = adapter.getView(i, convert, parent)
            convert.measure(spec, spec)
            if (convert.measuredWidth > max) max = convert.measuredWidth
        }
        val cap = (context.resources.displayMetrics.widthPixels * 0.9f).toInt()
        return minOf(max + (24 * density).toInt(), cap)
    }

    // Tint a Material on/off switch: checked = the configured switch colour, unchecked = grey.
    private fun applySwitchTint(v: SwitchCompat, color: Int) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val offThumb = 0xFF9E9E9E.toInt()
        val trackOn = (color and 0x00FFFFFF) or (0x66 shl 24)
        val trackOff = 0x619E9E9E
        v.thumbTintList = ColorStateList(states, intArrayOf(color, offThumb))
        v.trackTintList = ColorStateList(states, intArrayOf(trackOn, trackOff))
    }

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
}
