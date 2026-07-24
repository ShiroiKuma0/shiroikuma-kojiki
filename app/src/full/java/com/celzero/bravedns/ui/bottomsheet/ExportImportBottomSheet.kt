/*
 * Copyright 2025 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.bottomsheet

import Logger
import Logger.LOG_TAG_BACKUP_RESTORE
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.NestedScrollView
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.customui.CustomUi
import com.celzero.bravedns.customui.CustomUiConfig
import com.celzero.bravedns.customui.KojikiExport
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.delay
import com.celzero.bravedns.util.useTransparentNoDimBackground
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Fork (白い熊 考直): the unified, category-based Export / Import sheet — the single home for carrying
 * settings, replacing RethinkDNS's backup/restore AND the old appearance-only export. One checklist
 * drives both directions: Export saves the ticked categories to a .zip; Import applies the ticked
 * categories the chosen .zip contains (absent ones skipped). It also owns the persisted export folder
 * + the "last export" line. Opens full-height (no scrolling on a normal screen) and themed black/yellow.
 */
class ExportImportBottomSheet : BottomSheetDialogFragment() {

    private val persistentState by inject<PersistentState>()
    private val checks = LinkedHashMap<KojikiExport.Cat, CheckBox>()

    private var folderValueTv: TextView? = null
    private var statusTv: TextView? = null

    private lateinit var exportLauncher: ActivityResultLauncher<String>
    private lateinit var importLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var dirPickerLauncher: ActivityResultLauncher<Uri?>

    private companion object {
        const val EXIMPORT_PREFS = "kojiki_eximport" // device-local; never exported
        const val KEY_DIR_URI = "dir_uri"
        const val EXPORT_PREFIX = "shiroikuma-kojiki-"
        const val WARN_COLOR = 0xFFFF5252.toInt()
    }

    override fun getTheme(): Int =
        Themes.getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    private fun isDarkThemeOn(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                if (uri != null) exportToUri(uri)
            }
        importLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) doImport(uri)
            }
        dirPickerLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) onDirPicked(uri)
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = buildView()

    override fun onStart() {
        super.onStart()
        dialog?.useTransparentNoDimBackground()
        dialog?.let { CustomUi.themeBottomSheet(it) }
        // Open fully — size to content, no collapsed peek — so everything shows without scrolling.
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        if (sheet != null) {
            BottomSheetBehavior.from(sheet).apply {
                isFitToContents = true
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }

    // ---- UI ----

    private fun buildView(): View {
        val ctx = requireContext()
        val cfg = CustomUiConfig(ctx)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
            // The bordered box itself — inset from the sheet edges (below) so all sides show.
            background = GradientDrawable().apply {
                cornerRadius = 16 * d
                setColor(cfg.backgroundColor)
                setStroke((2f * d).toInt(), cfg.accentColor)
            }
        }

        root.addView(text(ctx, getString(R.string.kojiki_eim_title), 18f, cfg.accentColor, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(6))
        })
        root.addView(text(ctx, getString(R.string.kojiki_eim_desc), 13f, cfg.textColor).apply {
            alpha = 0.85f
            setPadding(0, 0, 0, dp(10))
        })

        // Persisted export directory — a bordered, clearly-tappable box that stands out when unset.
        val dirBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = 10 * d
                setColor(cfg.backgroundColor)
                setStroke((2f * d).toInt(), cfg.accentColor)
            }
            setOnClickListener { dirPickerLauncher.launch(dirUri()) }
        }
        dirBox.addView(text(ctx, getString(R.string.kojiki_eim_dir), 12f, cfg.accentColor))
        folderValueTv = text(ctx, "", 15f, cfg.textColor, bold = true)
        dirBox.addView(folderValueTv)
        root.addView(
            dirBox,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = dp(6); it.bottomMargin = dp(6) }
        )
        statusTv = text(ctx, "", 14f, cfg.textColor).apply { setPadding(dp(2), 0, 0, dp(8)) }
        root.addView(statusTv)

        root.addView(divider(ctx, cfg, dp(1)))

        val selectAll = checkbox(ctx, getString(R.string.kojiki_eim_select_all), cfg, bold = true).apply {
            isChecked = true
        }
        root.addView(selectAll)
        for (cat in KojikiExport.Cat.entries) {
            val cb = checkbox(ctx, getString(cat.labelRes), cfg).apply { isChecked = true }
            checks[cat] = cb
            root.addView(cb)
        }
        selectAll.setOnCheckedChangeListener { _, isChecked ->
            checks.values.forEach { it.isChecked = isChecked }
        }

        root.addView(divider(ctx, cfg, dp(1)).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8) })

        // ArcaneChat-style dialog button row: round pills, Cancel alone on the left, the
        // Import / Export actions grouped on the right.
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        buttons.addView(outlineButton(ctx, getString(R.string.lbl_cancel), cfg).apply {
            setOnClickListener { dismiss() }
        })
        buttons.addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f))
        buttons.addView(outlineButton(ctx, getString(R.string.kojiki_eim_import), cfg).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = dp(8) }
            setOnClickListener { onImportClicked() }
        })
        buttons.addView(outlineButton(ctx, getString(R.string.kojiki_eim_export), cfg).apply {
            setOnClickListener { onExportClicked() }
        })
        root.addView(buttons)

        refreshStatus()
        val m = dp(10)
        return NestedScrollView(ctx).apply {
            // Inset the bordered box from the sheet edges so its left/right/top borders are all visible.
            addView(
                root,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(m, m, m, m) }
            )
        }
    }

    private fun selected(): Set<KojikiExport.Cat> = checks.filterValues { it.isChecked }.keys

    // ---- folder + status ----

    private fun eximportPrefs() = requireContext().getSharedPreferences(EXIMPORT_PREFS, Context.MODE_PRIVATE)

    private fun dirUri(): Uri? =
        eximportPrefs().getString(KEY_DIR_URI, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    private fun exportDir(): DocumentFile? =
        dirUri()?.let { runCatching { DocumentFile.fromTreeUri(requireContext(), it) }.getOrNull() }
            ?.takeIf { it.isDirectory }

    private fun onDirPicked(uri: Uri) {
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        eximportPrefs().edit().putString(KEY_DIR_URI, uri.toString()).apply()
        refreshStatus()
    }

    private fun refreshStatus() {
        val cfg = CustomUiConfig(requireContext())
        val name = exportDir()?.name ?: dirUri()?.lastPathSegment
        folderValueTv?.text = name ?: getString(R.string.kojiki_eim_dir_unset)
        folderValueTv?.setTextColor(if (name == null) WARN_COLOR else cfg.textColor) // warn-prominent when unset
        val (msg, warn) = lastExportStatus()
        statusTv?.text = msg
        statusTv?.setTextColor(if (warn) WARN_COLOR else cfg.textColor)
        statusTv?.alpha = if (warn) 1f else 0.8f
    }

    /** (message, isWarning) for the "last export" line. */
    private fun lastExportStatus(): Pair<String, Boolean> {
        val dir = exportDir() ?: return getString(R.string.kojiki_eim_warn_nodir) to true
        val newest = runCatching {
            dir.listFiles().filter {
                it.isFile && it.name?.startsWith(EXPORT_PREFIX) == true && it.name?.endsWith(".zip") == true
            }.maxByOrNull { it.lastModified() }
        }.getOrNull()
        return if (newest == null) getString(R.string.kojiki_eim_warn_none) to true
        else getString(R.string.kojiki_eim_last, fmtTs(newest.lastModified())) to false
    }

    private fun fmtTs(t: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(t))

    private fun exportFileName(): String =
        EXPORT_PREFIX + com.celzero.bravedns.BuildConfig.VERSION_NAME + "-export_" +
            SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date()) + ".zip"

    // ---- export ----

    private fun onExportClicked() {
        if (selected().isEmpty()) { toast(getString(R.string.kojiki_eim_none_selected)); return }
        val dir = exportDir()
        if (dir == null) {
            exportLauncher.launch(exportFileName()) // no folder set → save-as picker
        } else {
            exportToFolder(dir)
        }
    }

    private fun exportToFolder(dir: DocumentFile) {
        val ctx = requireContext().applicationContext
        val cats = selected()
        toast(getString(R.string.kojiki_eim_exporting)) // immediate "started" flash
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = exportFileName()
                    val file = dir.createFile("application/zip", name) ?: error("could not create file in folder")
                    ctx.contentResolver.openOutputStream(file.uri)?.use { os ->
                        KojikiExport.export(ctx, cats, os)
                    } ?: error("no output stream")
                    name
                }
            }
            result.onSuccess { name ->
                toast(getString(R.string.kojiki_eim_export_ok, name)); refreshStatus()
            }.onFailure { e ->
                Logger.w(LOG_TAG_BACKUP_RESTORE, "kojiki export failed: ${e.message}", e as? Exception)
                toast(getString(R.string.kojiki_eim_export_fail, e.message ?: ""))
            }
        }
    }

    private fun exportToUri(uri: Uri) {
        val ctx = requireContext().applicationContext
        val cats = selected()
        toast(getString(R.string.kojiki_eim_exporting)) // immediate "started" flash
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { os -> KojikiExport.export(ctx, cats, os) }
                        ?: error("no output stream")
                }
            }
            result.onSuccess { summary -> toast(getString(R.string.kojiki_eim_export_ok, summary)) }
                .onFailure { e ->
                    Logger.w(LOG_TAG_BACKUP_RESTORE, "kojiki export failed: ${e.message}", e as? Exception)
                    toast(getString(R.string.kojiki_eim_export_fail, e.message ?: ""))
                }
        }
    }

    // ---- import ----

    private fun onImportClicked() {
        if (selected().isEmpty()) { toast(getString(R.string.kojiki_eim_none_selected)); return }
        importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }

    private fun doImport(uri: Uri) {
        val ctx = requireContext().applicationContext
        val cats = selected()
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                toast(getString(R.string.kojiki_eim_import_fail, "no input stream"), long = true)
                return@launch
            }
            // Pre-restore guard: if the backup carries a blocklist selection but this device hasn't
            // downloaded the on-device blocklist, the selection can't be applied — warn first so the
            // user can download the blocklist and restore again.
            val warnBlocklists = KojikiExport.Cat.BLOCKLISTS in cats &&
                withContext(Dispatchers.IO) {
                    KojikiExport.hasLocalBlocklistSelection(bytes) && !KojikiExport.localBlocklistsReady(ctx)
                }
            if (warnBlocklists) {
                showBlocklistNotDownloadedWarning(onProceed = { runImport(ctx, bytes, cats) })
            } else {
                runImport(ctx, bytes, cats)
            }
        }
    }

    private fun runImport(ctx: Context, bytes: ByteArray, cats: Set<KojikiExport.Cat>) {
        toast(getString(R.string.kojiki_eim_importing)) // immediate "started" flash; result dialog follows
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    require(KojikiExport.categoriesIn(bytes).isNotEmpty()) {
                        getString(R.string.kojiki_eim_import_none)
                    }
                    KojikiExport.import(ctx, bytes, cats)
                }
            }
            result.onSuccess { summary ->
                // Show a persistent result dialog (study it) with an explicit Restart button — never
                // a toast that flies by, and never an auto-restart.
                showImportResult(summary)
            }.onFailure { e ->
                Logger.w(LOG_TAG_BACKUP_RESTORE, "kojiki import failed: ${e.message}", e as? Exception)
                toast(getString(R.string.kojiki_eim_import_fail, e.message ?: ""), long = true)
            }
        }
    }

    private fun showBlocklistNotDownloadedWarning(onProceed: () -> Unit) {
        val ctx = requireContext()
        val cfg = CustomUiConfig(ctx)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = 16 * d
                setColor(cfg.backgroundColor)
                setStroke((2f * d).toInt(), cfg.accentColor)
            }
        }
        box.addView(text(ctx, getString(R.string.kojiki_eim_bl_warn_title), 19f, cfg.accentColor, bold = true))
        box.addView(text(ctx, getString(R.string.kojiki_eim_bl_warn_body), 14f, cfg.accentColor).apply {
            setPadding(0, dp(10), 0, 0)
        })
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx, R.style.App_Dialog_NoDim)
            .setView(NestedScrollView(ctx).apply { addView(box) })
            .setCancelable(true)
            .create()
        val btns = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(16), 0, 0)
        }
        btns.addView(outlineButton(ctx, getString(R.string.kojiki_eim_bl_warn_download_first), cfg).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = dp(10) }
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener { dialog.dismiss() } // cancel: user goes to download the blocklist first
        })
        btns.addView(outlineButton(ctx, getString(R.string.kojiki_eim_bl_warn_restore_anyway), cfg).apply {
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener { dialog.dismiss(); onProceed() }
        })
        box.addView(btns)
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    private fun showImportResult(summary: String) {
        val ctx = requireContext()
        val cfg = CustomUiConfig(ctx)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        // A fully custom bordered box with yellow (#accent) text — Material's dialog stroke/text colour
        // don't render the way we need, so we own the whole surface and make the window transparent.
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = 16 * d
                setColor(cfg.backgroundColor)
                setStroke((2f * d).toInt(), cfg.accentColor)
            }
        }
        box.addView(text(ctx, getString(R.string.kojiki_eim_import_done_title), 19f, cfg.accentColor, bold = true))
        box.addView(text(ctx, getString(R.string.kojiki_eim_import_done_body, summary), 14f, cfg.accentColor).apply {
            setPadding(0, dp(10), 0, 0)
        })
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx, R.style.App_Dialog_NoDim)
            .setView(NestedScrollView(ctx).apply { addView(box) })
            .setCancelable(false)
            .create()
        val btns = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(16), 0, 0)
        }
        btns.addView(outlineButton(ctx, getString(R.string.kojiki_eim_restart_later), cfg).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = dp(10) }
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener { dialog.dismiss(); dismiss() }
        })
        btns.addView(outlineButton(ctx, getString(R.string.kojiki_eim_restart_now), cfg).apply {
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener { restartApp(requireContext().applicationContext) }
        })
        box.addView(btns)
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    private fun restartApp(context: Context) {
        val pm: PackageManager = context.packageManager
        val intent = pm.getLaunchIntentForPackage(context.packageName) ?: return
        val main = Intent.makeRestartActivityTask(intent.component)
        context.startActivity(main)
        Runtime.getRuntime().exit(0)
    }

    // ---- view helpers (themed) ----

    private fun text(ctx: Context, s: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun checkbox(ctx: Context, label: String, cfg: CustomUiConfig, bold: Boolean = false): CheckBox =
        CheckBox(ctx).apply {
            text = label
            setTextColor(cfg.textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            buttonTintList = ColorStateList.valueOf(cfg.accentColor)
            val dd = resources.displayMetrics.density
            setPadding((8 * dd).toInt(), (7 * dd).toInt(), 0, (7 * dd).toInt())
        }

    private fun divider(ctx: Context, cfg: CustomUiConfig, h: Int): View =
        View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
            setBackgroundColor(cfg.accentColor)
            alpha = 0.4f
        }

    // A round-pill outline button (ArcaneChat-style dialog action): fully rounded ends,
    // wrap-content width with side padding.
    private fun outlineButton(ctx: Context, label: String, cfg: CustomUiConfig): Button {
        val dd = resources.displayMetrics.density
        val bg = GradientDrawable().apply {
            cornerRadius = 100 * dd // > half the height → a pill
            setColor(cfg.backgroundColor)
            setStroke((1.5f * dd).toInt(), cfg.accentColor)
        }
        return Button(ctx).apply {
            text = label
            isAllCaps = false
            setTextColor(cfg.accentColor)
            background = bg
            stateListAnimator = null
            minWidth = 0
            minimumWidth = 0
            setPadding((20 * dd).toInt(), (10 * dd).toInt(), (20 * dd).toInt(), (10 * dd).toInt())
        }
    }

    private fun toast(msg: String, long: Boolean = false) {
        val ctx = context ?: return
        Utilities.showToastUiCentered(ctx, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)
    }
}
