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
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.celzero.bravedns.R
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

    const val FONT_SYSTEM = ""
    const val FONT_MONOSPACE = "@monospace"
    const val FONT_SERIF = "@serif"
    const val FONT_SANS = "@sans"

    private val FONT_EXTENSIONS = setOf("ttf", "otf")
    private const val SEMIBOLD_WEIGHT = 600
    private val typefaceCache = HashMap<String, Typeface>()

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

    fun typefaceFor(context: Context, family: String, weight: Int): Typeface {
        val base = baseTypeface(context, family)
        if (weight <= 0) return base
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(base, weight, false)
        } else {
            Typeface.create(base, if (weight >= SEMIBOLD_WEIGHT) Typeface.BOLD else Typeface.NORMAL)
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
        val typeface = typefaceFor(activity, cfg.fontFamily, cfg.fontWeight)
        activity.window.setBackgroundDrawable(ColorDrawable(cfg.backgroundColor))

        val content = activity.findViewById<View>(android.R.id.content) ?: return
        setSolidBg(content, cfg.backgroundColor)
        applyToTree(activity, content, cfg, typeface)

        // Catch dynamically-added views (e.g. RecyclerView rows). Re-walking is cheap because every
        // set below is equality-guarded, so only genuinely-new/changed views do any work. Install at
        // most one listener per content view (config edits recreate() the activity, refreshing it).
        if (!layoutListeners.containsKey(content)) {
            val vto = content.viewTreeObserver
            if (vto.isAlive) {
                val listener = ViewTreeObserver.OnGlobalLayoutListener {
                    applyToTree(activity, content, cfg, typeface)
                }
                layoutListeners[content] = listener
                vto.addOnGlobalLayoutListener(listener)
            }
        }
    }

    private fun applyToTree(context: Context, v: View, cfg: CustomUiConfig, typeface: Typeface) {
        when (v) {
            // Material containers carry the tonal "surface tint" (= colorPrimary = yellow) that turns
            // elevated surfaces olive. Force their fill to the background colour and zero their
            // elevation so no tint is composited — flat black, regardless of the Material version.
            is MaterialCardView -> {
                if (v.cardBackgroundColor.defaultColor != cfg.backgroundColor) {
                    v.setCardBackgroundColor(cfg.backgroundColor)
                }
                if (v.cardElevation != 0f) v.cardElevation = 0f
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
            is TextView -> {
                if (v.typeface !== typeface) v.typeface = typeface
                if (v.currentTextColor != cfg.textColor) v.setTextColor(cfg.textColor)
                if (cfg.fontSize > 0) {
                    val targetPx = cfg.fontSize * context.resources.displayMetrics.scaledDensity
                    if (kotlin.math.abs(v.textSize - targetPx) > 0.5f) {
                        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, cfg.fontSize.toFloat())
                    }
                }
            }
            is ImageView -> {
                // Accent only small VECTOR UI icons. Two things to leave alone:
                //  - app launcher icons / photos (bitmap or adaptive drawables — e.g. the firewall
                //    app list) must keep their real colours, not become solid accent squares; so tint
                //    only drawables whose class name says "Vector".
                //  - a full-bleed illustration/banner (the Backup & restore "yellow box") — excluded
                //    by the icon-size bound (width/height are valid on the post-layout re-walk).
                // clearColorFilter on the else-branch also undoes a stale tint when a RecyclerView row
                // is recycled from a vector icon to a bitmap app-icon.
                val d = v.drawable
                val iconMaxPx = 96 * context.resources.displayMetrics.density
                val smallVector = d != null &&
                        d.javaClass.simpleName.contains("Vector", ignoreCase = true) &&
                        v.width <= iconMaxPx && v.height <= iconMaxPx
                if (smallVector) v.setColorFilter(cfg.accentColor) else v.clearColorFilter()
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) applyToTree(context, v.getChildAt(i), cfg, typeface)
        }
    }

    // Set a flat background colour only when it isn't already that colour (avoids relayout churn).
    private fun setSolidBg(v: View, color: Int) {
        if ((v.background as? ColorDrawable)?.color != color) v.setBackgroundColor(color)
    }
}
