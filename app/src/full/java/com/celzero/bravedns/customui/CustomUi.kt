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
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ListItemFirewallAppBinding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.card.MaterialCardView
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
            is TextView -> when (v.id) {
                // Firewall app-list rows (name / status / traffic) are styled by FirewallAppListAdapter
                // at bind time — reliable + type-aware (user vs system). The tree-walk races with their
                // async bind, so skip them here.
                R.id.firewall_app_label_tv, R.id.firewall_app_toggle_other, R.id.firewall_app_data_usage ->
                    Unit
                else ->
                    styleText(context, v, global, global)
            }
            is ImageView -> when (v.id) {
                // Firewall row icon + wifi/data toggles are styled by FirewallAppListAdapter at bind time.
                R.id.firewall_app_icon_iv, R.id.firewall_app_toggle_wifi, R.id.firewall_app_toggle_mobile_data ->
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

    // Firewall app icon: optional fixed size (dp) + corner roundness (0..100 = % of half the icon,
    // so 0 = square, 100 = circle). Size sets are guarded so the requestLayout settles after one pass.
    private fun applyIconStyle(context: Context, v: ImageView, cfg: CustomUiConfig) {
        if (cfg.iconSize > 0) {
            val px = (cfg.iconSize * context.resources.displayMetrics.density).toInt()
            val lp = v.layoutParams
            if (lp != null && (lp.width != px || lp.height != px)) {
                lp.width = px
                lp.height = px
                v.layoutParams = lp
            }
        }
        if (cfg.iconRoundness > 0) {
            val pct = cfg.iconRoundness.coerceIn(0, 100) / 100f
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
