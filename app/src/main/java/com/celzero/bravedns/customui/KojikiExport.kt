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
package com.celzero.bravedns.customui

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_BACKUP_RESTORE
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.annotation.StringRes
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.DnsCryptEndpoint
import com.celzero.bravedns.database.DnsProxyEndpoint
import com.celzero.bravedns.database.DoHEndpoint
import com.celzero.bravedns.database.DoTEndpoint
import com.celzero.bravedns.database.ODoHEndpoint
import com.celzero.bravedns.database.ProxyEndpoint
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomDomainRepository
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.database.CustomIpRepository
import com.celzero.bravedns.database.ProxyAppMappingRepository
import com.celzero.bravedns.database.RethinkLocalFileTagRepository
import com.celzero.bravedns.database.RethinkRemoteFileTagRepository
import com.celzero.bravedns.database.DoHEndpointRepository
import com.celzero.bravedns.database.WgConfigFilesRepository
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.service.EncryptedFileManager
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.KojikiPendingFw
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.SnoopTagStore
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.wireguard.Config
import com.google.gson.Gson
import org.json.JSONArray
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Fork (白い熊 考直): the unified, category-based settings export/import that supersedes RethinkDNS's
 * own (faulty, all-or-nothing) backup/restore.
 *
 * The export is a ZIP of **plain JSON files** — one per category — plus any imported font files as
 * real files under `fonts/`. No binary blobs, no serialized objects, no `.db` files. The blocklists
 * category carries only the *selection* (per-list names + the portable stamp), never the multi-MB
 * blocklist data — that is re-downloaded on the target device. A `manifest.json` lists the format,
 * version and the categories present.
 *
 * **Future-proof by construction:** every category is an independent file; import iterates the
 * *selected* categories, skips any whose file is absent, and tolerates missing/extra keys & rows —
 * prefs round-trip per-key (a missing key keeps its current value), DB rows deserialize through
 * entities whose fields all carry defaults (Gson fills the gaps), and unknown categories/keys are
 * ignored. So an older app reading a newer export (or vice-versa) never breaks.
 *
 * **Safe import semantics:** rows are upserted (never wiped); per-app firewall + WG bindings apply
 * only to *installed* apps (no phantom AppInfo rows — the trap that bricked the old restore).
 */
object KojikiExport : KoinComponent {

    const val FORMAT = "kojiki-export"
    const val VERSION = 2 // v2: blocklists carry per-list names + portable stamp/prefs (no data files)

    // --- backup file naming — the 白い熊 family convention (2026-07-25) ---
    // `<english-dash-separated-app-name>_<yyyy-MM-dd_HH-mm-ss>.zip`, no version / no `-export` infix,
    // so every sister app's backups sort and read uniformly in one shared directory. ONE zip per
    // export, always. LEGACY_PREFIX keeps previously written backups recognised by the "last export"
    // line (they were `shiroikuma-kojiki-<version>-export_<ts>.zip`).
    const val EXPORT_PREFIX = "shiroikuma-kojiki_"
    private const val LEGACY_EXPORT_PREFIX = "shiroikuma-kojiki-"

    /** The name of the ZIP to write now — identical for the UI panel and the automation receiver. */
    fun exportFileName(): String =
        EXPORT_PREFIX + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date()) + ".zip"

    /** True if [name] is one of this app's backups (current or legacy naming). */
    fun isExportFileName(name: String?): Boolean =
        name != null && name.endsWith(".zip") &&
            (name.startsWith(EXPORT_PREFIX) || name.startsWith(LEGACY_EXPORT_PREFIX))

    // --- the configured export directory (a persisted SAF tree) ---
    // Device-local by design: this prefs file is NOT part of any category, so the directory (and the
    // automation token) never travel in a backup ZIP.
    const val EXIMPORT_PREFS = "kojiki_eximport"
    private const val KEY_DIR_URI = "dir_uri"

    fun exportDirUri(context: Context): Uri? =
        context.getSharedPreferences(EXIMPORT_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DIR_URI, null)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun setExportDirUri(context: Context, uri: Uri) {
        context.getSharedPreferences(EXIMPORT_PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DIR_URI, uri.toString()).apply()
    }

    /** The configured export directory, or null when unset / no longer reachable. */
    fun exportDir(context: Context): DocumentFile? =
        exportDirUri(context)
            ?.let { runCatching { DocumentFile.fromTreeUri(context, it) }.getOrNull() }
            ?.takeIf { it.isDirectory }

    private val appInfoRepo: AppInfoRepository by inject()
    private val customDomainRepo: CustomDomainRepository by inject()
    private val customIpRepo: CustomIpRepository by inject()
    private val proxyAppMappingRepo: ProxyAppMappingRepository by inject()
    private val wgConfigRepo: WgConfigFilesRepository by inject()
    private val localTagRepo: RethinkLocalFileTagRepository by inject()
    private val remoteTagRepo: RethinkRemoteFileTagRepository by inject()
    private val dohRepo: DoHEndpointRepository by inject()
    private val appConfig: AppConfig by inject()
    private val persistentState: PersistentState by inject()
    private val db: AppDatabase by inject() // DNS/proxy DAOs lack getAll on their repos
    private val gson = Gson()

    // Filled by importWireGuard, surfaced in the import dialog (lockdown + bound-app count are otherwise
    // invisible until the user digs into the WG screen).
    private var wgImportDetail = ""

    /**
     * A selectable export/import category. `id` is the JSON file name (`<id>.json`) inside the ZIP.
     *
     * [onByDefault] is the app's own answer to "does this item start ticked?" — stated, never
     * guessed by whoever draws the picker. It seeds both the in-app Export/Import sheet and the
     * fourth field of the 保存復元 `LIST_CATEGORIES` reply. Everything this app exports is small and
     * irreplaceable (rules, keys, endpoints, tags), so nothing is `off`: the flag exists so a
     * category added later inherits a field that is already there.
     */
    enum class Cat(val id: String, @StringRes val labelRes: Int, val onByDefault: Boolean = true) {
        APP_SETTINGS("app_settings", R.string.kojiki_eim_cat_settings),
        APPEARANCE("appearance", R.string.kojiki_eim_cat_appearance),
        SNOOP_TAGS("snoop_tags", R.string.kojiki_eim_cat_tags),
        APP_NOTES("app_notes", R.string.kojiki_eim_cat_notes),
        APP_GROUPS("app_groups", R.string.kojiki_eim_cat_groups),
        FIREWALL_APPS("firewall_apps", R.string.kojiki_eim_cat_fw_apps),
        FIREWALL_DOMAINS("firewall_domains", R.string.kojiki_eim_cat_fw_domains),
        FIREWALL_IPS("firewall_ips", R.string.kojiki_eim_cat_fw_ips),
        WIREGUARD("wireguard", R.string.kojiki_eim_cat_wireguard),
        BLOCKLISTS("blocklists", R.string.kojiki_eim_cat_blocklists),
        DNS("dns", R.string.kojiki_eim_cat_dns),
        PROXIES("proxies", R.string.kojiki_eim_cat_proxies);

        companion object {
            fun byId(id: String): Cat? = entries.firstOrNull { it.id == id }
            fun all(): Set<Cat> = entries.toSet()

            /** The default set: exactly the [onByDefault] categories — what an absent `items` means. */
            fun defaults(): Set<Cat> = entries.filter { it.onByDefault }.toSet()
        }
    }

    /**
     * Thrown out of [export] when the caller's `isCancelled` signal goes up. The caller must delete
     * the partial output — a cancelled export leaves the backup directory exactly as it found it.
     */
    class ExportCancelled : Exception("cancelled")

    // Fork-private prefs stores (must match CustomUiConfig.PREFS / SnoopTagStore.PREFS /
    // KojikiAppNotes.PREFS / KojikiAppGroups.PREFS).
    private const val PREFS_KOJIKI_UI = "kojiki_ui"
    private const val PREFS_SNOOP_TAGS = "snoop_tags"
    private const val PREFS_APP_NOTES = KojikiAppNotes.PREFS
    private const val PREFS_APP_GROUPS = KojikiAppGroups.PREFS

    // Ephemeral / device-local keys never worth exporting from the main app prefs: runtime state,
    // version codes, one-time/onboarding flags, download/blocklist timestamps, auth tokens.
    private val APP_SETTINGS_EXCLUDE = setOf(
        "enabled", "is_first_time_launch", "app_rule_intent_token", "automation_export_enabled",
        "automation_require_token",
        "app_version",
        "app_update_last_check", "remote_block_list_count", "local_block_list_count",
        "remote_block_list_downloaded_time", "local_block_list_downloaded_time",
        "local_blocklist_update_ts", "remote_blocklist_update_ts", "prev_trace_timestamp",
        "biometric_auth_time", "prev_data_usage_check", "show_whats_new_chip", "new_settings",
        "new_settings_seen", "app_update_time_ts", "go_logger_level",
        "custom_downloader_last_generated_id", "android_download_manager_ids", "firebase_user_token",
        "firebase_user_token_timestamp", "last_reported_tombstone_file",
        "device_registration_timestamp", "guided_tour_completed", "guided_tour_version"
    )

    // ---------------------------------------------------------------------------------------------
    // EXPORT
    // ---------------------------------------------------------------------------------------------

    /**
     * Write a ZIP of the selected categories to [out]. Returns a short human summary.
     *
     * The headless export core: the Export/Import panel and the automation receiver
     * ([com.celzero.bravedns.receiver.StateExportReceiver]) are two thin callers of this — no export
     * logic is duplicated anywhere. [onProgress] is called as `(done, total, categoryLabel)` after
     * each category is written, so a caller can report real counts (never a percentage).
     *
     * [isCancelled] is polled at every entry boundary — never mid-write — and unwinds the run with
     * [ExportCancelled]. The caller owns the signal (and the partial file it must then delete);
     * callers that cannot be cancelled simply pass nothing.
     */
    suspend fun export(
        context: Context,
        cats: Set<Cat>,
        out: OutputStream,
        onProgress: ((Int, Int, String) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): String {
        var count = 0
        // Deterministic order (declaration order), so progress reads the same on every run.
        val ordered = cats.sortedBy { it.ordinal }
        val total = ordered.size
        ZipOutputStream(out).use { zip ->
            val manifest = JSONObject()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("app", context.packageName)
                .put("createdTs", System.currentTimeMillis())
                .put("categories", JSONArray(ordered.map { it.id }))
            writeEntry(zip, "manifest.json", manifest.toString(2))

            for (cat in ordered) {
                // A cancel unwinds here, at an entry boundary: never mid-write(), never by killing
                // a thread or the process (contract §CANCEL_EXPORT).
                if (isCancelled?.invoke() == true) throw ExportCancelled()
                val json = when (cat) {
                    Cat.APP_SETTINGS ->
                        exportPrefs(PreferenceManager.getDefaultSharedPreferences(context), APP_SETTINGS_EXCLUDE)
                    Cat.APPEARANCE ->
                        exportPrefs(context.getSharedPreferences(PREFS_KOJIKI_UI, Context.MODE_PRIVATE), emptySet())
                    Cat.SNOOP_TAGS ->
                        exportPrefs(context.getSharedPreferences(PREFS_SNOOP_TAGS, Context.MODE_PRIVATE), emptySet())
                    // Both stores key on package name, so they carry across devices/reinstalls as-is
                    // — except for the synthetic "no_package_<uid>" rows, whose key is a uid in
                    // package clothing and can name a different thing on the target device. Those
                    // carry their original label alongside, so the import can flag them (see
                    // withNonAppLabels / markImportedNonApp).
                    Cat.APP_NOTES ->
                        withNonAppLabels(
                            exportPrefs(context.getSharedPreferences(PREFS_APP_NOTES, Context.MODE_PRIVATE), emptySet()))
                    Cat.APP_GROUPS ->
                        withNonAppLabels(
                            exportPrefs(context.getSharedPreferences(PREFS_APP_GROUPS, Context.MODE_PRIVATE), emptySet()))
                    Cat.FIREWALL_APPS -> exportFwApps()
                    Cat.FIREWALL_DOMAINS -> gson.toJson(customDomainRepo.getAllCustomDomains())
                    Cat.FIREWALL_IPS -> gson.toJson(customIpRepo.getIpRules())
                    Cat.WIREGUARD -> exportWireGuard(context)
                    Cat.BLOCKLISTS -> exportBlocklists()
                    Cat.DNS -> exportDns()
                    Cat.PROXIES -> exportProxies()
                }
                writeEntry(zip, "${cat.id}.json", json)
                if (cat == Cat.APPEARANCE) exportFonts(context, zip)
                count++
                onProgress?.invoke(count, total, context.getString(cat.labelRes))
            }
        }
        return "$count categor${if (count == 1) "y" else "ies"}"
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray())
        zip.closeEntry()
    }

    // ---- non-app ("no_package_<uid>") keys ---------------------------------------------------------
    // A synthetic row's key encodes a uid, not a package, so a note or group membership attached to
    // it is only meaningful on the device it was made on: uid 0 is root everywhere, but an
    // unattributable app uid can be anything after a restore. Rather than drop them (they are the
    // rows most worth annotating — "do not block, DNS dies"), carry them WITH the label they had, and
    // mark them on the way in so they are reviewed rather than trusted.

    /** Key under which the non-app labels ride along in a category's JSON. [importPrefs] ignores it —
     *  its value carries no `t`/`v` pair — so older/newer readers simply skip it. */
    private const val LABELS_KEY = "__kojiki_nonapp_labels"

    /** Add `packageName -> label` for every synthetic row, so an import can say what it used to be. */
    private suspend fun withNonAppLabels(json: String): String {
        val labels = JSONObject()
        for (a in appInfoRepo.getAppInfo()) {
            if (Utilities.isNonApp(a.packageName)) labels.put(a.packageName, a.appName)
        }
        if (labels.length() == 0) return json
        return JSONObject(json).put(LABELS_KEY, labels).toString(2)
    }

    private fun nonAppLabels(json: String): JSONObject =
        runCatching { JSONObject(json).optJSONObject(LABELS_KEY) }.getOrNull() ?: JSONObject()

    /**
     * Flag every synthetic row that arrived with a note or a group, so it is checked rather than
     * trusted: the note gets a leading line naming what the key was on the source device. A row that
     * only carried group membership gets a note created for it — otherwise the import would be
     * silent, and a uid quietly pointing at the wrong app is the whole hazard here.
     *
     * Idempotent: an already-marked note is left alone, so importing twice never stacks markers.
     */
    private fun markImportedNonApp(context: Context, json: String, keys: Collection<String>) {
        val labels = nonAppLabels(json)
        for (key in keys) {
            if (!Utilities.isNonApp(key)) continue
            val existing = KojikiAppNotes.getNote(context, key).orEmpty()
            if (existing.startsWith(IMPORT_MARK)) continue
            val label = labels.optString(key).ifEmpty { key }
            val uid = key.removePrefix(AppInfoRepository.NO_PACKAGE_PREFIX)
            val mark = context.getString(R.string.kojiki_note_imported_nonapp, label, uid)
            KojikiAppNotes.setNote(
                context, key, if (existing.isEmpty()) mark else "$mark\n$existing")
        }
    }

    /** Leading marker of an imported synthetic-row note — also the idempotency check. */
    private const val IMPORT_MARK = "⚠"

    private fun exportPrefs(sp: SharedPreferences, exclude: Set<String>): String {
        val obj = JSONObject()
        for ((k, v) in sp.all) {
            if (k in exclude) continue
            val e = JSONObject()
            when (v) {
                is Boolean -> { e.put("t", "b"); e.put("v", v) }
                is Int -> { e.put("t", "i"); e.put("v", v) }
                is Long -> { e.put("t", "l"); e.put("v", v) }
                is Float -> { e.put("t", "f"); e.put("v", v.toDouble()) }
                is String -> { e.put("t", "s"); e.put("v", v) }
                is Set<*> -> { e.put("t", "ss"); e.put("v", JSONArray(v.map { it.toString() })) }
                else -> continue
            }
            obj.put(k, e)
        }
        return obj.toString(2)
    }

    private suspend fun exportFwApps(): String {
        val arr = JSONArray()
        for (a in appInfoRepo.getAppInfo()) {
            arr.put(
                JSONObject()
                    .put("uid", a.uid)
                    .put("packageName", a.packageName)
                    .put("firewallStatus", a.firewallStatus)
                    .put("connectionStatus", a.connectionStatus)
                    .put("screenOffAllowed", a.screenOffAllowed)
                    .put("backgroundAllowed", a.backgroundAllowed)
                    .put("isProxyExcluded", a.isProxyExcluded)
            )
        }
        return arr.toString(2)
    }

    private suspend fun exportWireGuard(context: Context): String {
        val arr = JSONArray()
        for (m in WireguardManager.getAllMappings()) {
            val conf = try {
                EncryptedFileManager.read(context, File(m.configPath))
            } catch (e: Exception) {
                Logger.w(LOG_TAG_BACKUP_RESTORE, "kojiki export: wg ${m.id} read failed: ${e.message}")
                ""
            }
            if (conf.isBlank()) continue
            val mappings = proxyAppMappingRepo.getAppsForProxy(ProxyManager.ID_WG_BASE + m.id)
            arr.put(
                JSONObject()
                    .put("name", m.name)
                    .put("conf", conf)
                    .put("isLockdown", m.isLockdown)
                    .put("isCatchAll", m.isCatchAll)
                    .put("oneWireGuard", m.oneWireGuard)
                    .put("useOnlyOnMetered", m.useOnlyOnMetered)
                    .put("isActive", m.isActive)
                    // Bind apps BY PACKAGE NAME (uid is install-specific). boundAppUids kept for old imports.
                    .put("boundApps", JSONArray(mappings.map { it.packageName }.filter { it.isNotBlank() }.distinct()))
                    .put("boundAppUids", JSONArray(mappings.map { it.uid }.distinct()))
            )
        }
        return arr.toString(2)
    }

    // Export the on-device blocklist SELECTION only — never the multi-MB data files (those re-download
    // on the target device). Each selected LOCAL list is carried as {value, vname, group, subg} so it
    // is both human-readable ("which lists did I have?") and re-selectable. The local `stamp` is what
    // firestack actually enforces and is PORTABLE across blocklist versions (it encodes stable per-list
    // value-flags), so it round-trips the exact blocking without the files. Remote blocklists are
    // enforced server-side (RethinkDNS+ URL), so only their selected ids are carried.
    private suspend fun exportBlocklists(): String {
        val localArr = JSONArray()
        for (t in localTagRepo.fileTags()) {
            if (!t.isSelected) continue
            localArr.put(
                JSONObject()
                    .put("value", t.value)
                    .put("vname", t.vname)
                    .put("group", t.group)
                    .put("subg", t.subg)
            )
        }
        return JSONObject()
            .put("local", localArr)
            .put("remote", JSONArray(remoteTagRepo.getSelectedTags()))
            .put("localStamp", persistentState.localBlocklistStamp)
            .put("localCount", persistentState.numberOfLocalBlocklists)
            .put("localEnabled", persistentState.blocklistEnabled)
            .toString(2)
    }

    private fun exportFonts(context: Context, zip: ZipOutputStream) {
        CustomUi.fontsDir(context).listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            zip.putNextEntry(ZipEntry("fonts/${f.name}"))
            zip.write(f.readBytes())
            zip.closeEntry()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // IMPORT
    // ---------------------------------------------------------------------------------------------

    /** Categories present in a ZIP (from its manifest, falling back to the `<id>.json` files found). */
    fun categoriesIn(zip: ByteArray): Set<Cat> {
        val files = readZip(zip)
        files["manifest.json"]?.let { mf ->
            val cats = runCatching { JSONObject(mf.decodeToString()).optJSONArray("categories") }.getOrNull()
            if (cats != null) {
                val set = (0 until cats.length()).mapNotNull { Cat.byId(cats.optString(it)) }.toSet()
                if (set.isNotEmpty()) return set
            }
        }
        return Cat.entries.filter { files.containsKey("${it.id}.json") }.toSet()
    }

    /**
     * True if the on-device (local) blocklist is downloaded on THIS device — i.e. an imported blocklist
     * selection can actually be applied/enforced. (The export carries only the selection + stamp; the
     * multi-MB data must already be present.)
     */
    fun localBlocklistsReady(context: Context): Boolean {
        val ts = persistentState.localBlocklistTimestamp
        return ts > 0 && Utilities.hasLocalBlocklists(context, ts)
    }

    /** True if [zip]'s blocklists category carries a non-empty LOCAL blocklist selection. */
    fun hasLocalBlocklistSelection(zip: ByteArray): Boolean {
        val data = readZip(zip)["${Cat.BLOCKLISTS.id}.json"] ?: return false
        return runCatching {
            (JSONObject(data.decodeToString()).optJSONArray("local")?.length() ?: 0) > 0
        }.getOrDefault(false)
    }

    /**
     * Force every prefs file this app writes to disk, synchronously.
     *
     * [importPrefs] commits its own edits, but the import also drives `PersistentState` (Krate,
     * which uses `apply()`) and [com.celzero.bravedns.service.KojikiPendingFw]. A `commit()` on the
     * same `SharedPreferences` instance blocks on the write lock until any queued `apply()` has
     * landed, so an empty commit per file is a flush.
     *
     * Called before the automation data door replies OK to an import, because the caller SIGKILLs
     * this process on that reply and anything still queued dies with it.
     */
    fun flushPrefs(context: Context) {
        val files = listOf(
            PREFS_KOJIKI_UI, PREFS_SNOOP_TAGS, PREFS_APP_NOTES, PREFS_APP_GROUPS,
            "kojiki_pending_fw"
        )
        runCatching { PreferenceManager.getDefaultSharedPreferences(context).edit().commit() }
        files.forEach { f ->
            runCatching { context.getSharedPreferences(f, Context.MODE_PRIVATE).edit().commit() }
        }
    }

    /** Apply the selected categories from a ZIP. Missing files are skipped. Returns a human summary. */
    suspend fun import(context: Context, zip: ByteArray, cats: Set<Cat>): String {
        val files = readZip(zip)
        wgImportDetail = ""
        val parts = mutableListOf<String>()
        for (cat in cats) {
            val data = files["${cat.id}.json"] ?: continue
            val json = data.decodeToString()
            val n = try {
                when (cat) {
                    Cat.APP_SETTINGS ->
                        importPrefs(PreferenceManager.getDefaultSharedPreferences(context), json, APP_SETTINGS_EXCLUDE)
                    Cat.APPEARANCE -> {
                        val c = importPrefs(context.getSharedPreferences(PREFS_KOJIKI_UI, Context.MODE_PRIVATE), json, emptySet())
                        importFonts(context, files)
                        c
                    }
                    Cat.SNOOP_TAGS ->
                        importPrefs(context.getSharedPreferences(PREFS_SNOOP_TAGS, Context.MODE_PRIVATE), json, emptySet())
                    Cat.APP_NOTES -> {
                        val n = importPrefs(
                            context.getSharedPreferences(PREFS_APP_NOTES, Context.MODE_PRIVATE), json, emptySet())
                        markImportedNonApp(context, json, JSONObject(json).keys().asSequence().toList())
                        n
                    }
                    Cat.APP_GROUPS -> {
                        val n = importPrefs(
                            context.getSharedPreferences(PREFS_APP_GROUPS, Context.MODE_PRIVATE), json, emptySet())
                        KojikiAppGroups.invalidateCache()
                        // Membership alone would arrive silently, so mark the synthetic rows it names.
                        val members =
                            KojikiAppGroups.groups(context)
                                .flatMap { KojikiAppGroups.members(context, it) }
                                .distinct()
                        markImportedNonApp(context, json, members)
                        n
                    }
                    Cat.FIREWALL_APPS -> importFwApps(context, json)
                    Cat.FIREWALL_DOMAINS -> importDomains(json)
                    Cat.FIREWALL_IPS -> importIps(json)
                    Cat.WIREGUARD -> importWireGuard(json)
                    Cat.BLOCKLISTS -> importBlocklists(context, json)
                    Cat.DNS -> importDns(json)
                    Cat.PROXIES -> importProxies(json)
                }
            } catch (e: Exception) {
                Logger.w(LOG_TAG_BACKUP_RESTORE, "kojiki import ${cat.id} failed: ${e.message}", e)
                -1
            }
            if (n >= 0) {
                val line = "${context.getString(cat.labelRes)}: $n"
                parts.add(if (cat == Cat.WIREGUARD && wgImportDetail.isNotEmpty()) "$line — $wgImportDetail" else line)
            }
        }
        // App settings carry the chosen DNS by name only; actually SELECT that endpoint so DNS doesn't
        // fall back to the default (RethinkDNS) after restart.
        if (Cat.APP_SETTINGS in cats) {
            // Read connected_dns_name from the EXPORT json (the live pref gets clobbered mid-import).
            val dnsName = files["app_settings.json"]?.let {
                runCatching {
                    JSONObject(it.decodeToString()).optJSONObject("connected_dns_name")?.optString("v")
                }.getOrNull()
            }
            val dns = try { applyDnsSelection(dnsName) } catch (e: Exception) { "error: ${e.message}" }
            parts.add("DNS → $dns")
        }
        // Caches backing the fork prefs/fonts were swapped underneath; drop them so the import shows.
        SnoopTagStore.invalidateCache()
        KojikiAppGroups.invalidateCache()
        CustomUi.invalidateCaches()
        return if (parts.isEmpty()) "nothing imported" else parts.joinToString("\n")
    }

    private fun importPrefs(sp: SharedPreferences, json: String, exclude: Set<String>): Int {
        val obj = JSONObject(json)
        val ed = sp.edit() // merge — never clear, so unrelated/device-local keys survive
        var n = 0
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k in exclude) continue
            val e = obj.optJSONObject(k) ?: continue
            when (e.optString("t")) {
                "b" -> ed.putBoolean(k, e.optBoolean("v"))
                "i" -> ed.putInt(k, e.optInt("v"))
                "l" -> ed.putLong(k, e.optLong("v"))
                "f" -> ed.putFloat(k, e.optDouble("v").toFloat())
                "s" -> ed.putString(k, e.optString("v"))
                "ss" -> {
                    val arr = e.optJSONArray("v") ?: JSONArray()
                    val set = HashSet<String>()
                    for (i in 0 until arr.length()) set.add(arr.optString(i))
                    ed.putStringSet(k, set)
                }
                else -> continue
            }
            n++
        }
        // commit(), NOT apply(). 応用管理 force-stops this app with SIGKILL the instant the data
        // door answers OK to an import, so an apply() still in flight is simply lost — the restore
        // reports success and the data is silently gone. It never shows up in a hand-run import,
        // because that is followed by a normal lifecycle that flushes. (Found across the family,
        // 2026-09-04.) The cost is a synchronous write on a background thread, which is nothing.
        ed.commit()
        return n
    }

    private suspend fun importFwApps(context: Context, json: String): Int {
        val arr = JSONArray(json)
        var n = 0
        // Match by PACKAGE NAME — uid is install-specific (changes across devices/reinstalls). Installed
        // apps apply now (by their current uid); rules for not-yet-installed apps are parked and applied
        // when the package is installed (RefreshDatabase.insertApp -> KojikiPendingFw.applyTo).
        val installed = appInfoRepo.getAppInfo().associateBy { it.packageName }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pkg = o.optString("packageName")
            if (pkg.isBlank()) continue
            val app = installed[pkg]
            if (app != null) {
                app.firewallStatus = o.optInt("firewallStatus", app.firewallStatus)
                app.connectionStatus = o.optInt("connectionStatus", app.connectionStatus)
                app.screenOffAllowed = o.optBoolean("screenOffAllowed", app.screenOffAllowed)
                app.backgroundAllowed = o.optBoolean("backgroundAllowed", app.backgroundAllowed)
                app.isProxyExcluded = o.optBoolean("isProxyExcluded", app.isProxyExcluded)
                appInfoRepo.insert(app) // @Insert REPLACE — updates by (uid, packageName), keeps usage stats
            } else {
                KojikiPendingFw.put(
                    context, pkg,
                    o.optInt("firewallStatus", FirewallManager.FirewallStatus.NONE.id),
                    o.optInt("connectionStatus", FirewallManager.ConnectionStatus.ALLOW.id),
                    o.optBoolean("screenOffAllowed", false),
                    o.optBoolean("backgroundAllowed", false),
                    o.optBoolean("isProxyExcluded", false)
                )
            }
            n++
        }
        return n
    }

    private suspend fun importDomains(json: String): Int {
        val rules = gson.fromJson(json, Array<CustomDomain>::class.java) ?: return 0
        for (r in rules) customDomainRepo.insert(r)
        return rules.size
    }

    private suspend fun importIps(json: String): Int {
        val rules = gson.fromJson(json, Array<CustomIp>::class.java) ?: return 0
        for (r in rules) customIpRepo.insert(r)
        return rules.size
    }

    private suspend fun importWireGuard(json: String): Int {
        val arr = JSONArray(json)
        // Replace-all: clear EVERY existing WG tunnel + its app bindings first, then add exactly what's
        // in the backup. So the WG set after import is precisely the backup's — no duplicates, and no
        // leftover tunnels that aren't in the export.
        WireguardManager.getAllMappings().forEach { runCatching { WireguardManager.deleteConfig(it.id) } }
        val byPkg = appInfoRepo.getAppInfo().associateBy { it.packageName } // package -> current AppInfo
        var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val conf = o.optString("conf")
            if (conf.isBlank()) continue
            val name = o.optString("name", "wg")
            val config = try {
                Config.parse(conf.byteInputStream())
            } catch (e: Exception) {
                Logger.w(LOG_TAG_BACKUP_RESTORE, "kojiki import: wg parse failed for $name: ${e.message}")
                continue
            }
            WireguardManager.addConfig(config, name) ?: continue
            val id = wgConfigRepo.getLastAddedConfigId()
            val lock = o.optBoolean("isLockdown")
            val proxyId = ProxyManager.ID_WG_BASE + id
            // Activate first (if it was active). enableConfig REWRITES the WgConfigFiles row from the
            // in-memory cache, so the repo-only flag setters get clobbered — set flags AFTER it, via the
            // cache-aware WireguardManager methods (same ones the UI's lockdown toggle uses).
            if (o.optBoolean("isActive")) {
                runCatching { WireguardManager.getConfigFilesById(id)?.let { WireguardManager.enableConfig(it) } }
            }
            if (lock) runCatching { WireguardManager.updateLockdownConfig(id, true) }
            if (o.optBoolean("isCatchAll")) runCatching { WireguardManager.updateCatchAllConfig(id, true) }
            if (o.optBoolean("oneWireGuard")) runCatching { WireguardManager.updateOneWireGuardConfig(id, true) }
            if (o.optBoolean("useOnlyOnMetered")) runCatching { wgConfigRepo.updateMobileConfig(id, true) }
            // Bind apps BY PACKAGE NAME via ProxyManager.addProxyToApp — the proper path (DB + cache +
            // engine). The raw repo update doesn't actually route the app. Resolve to the current uid;
            // prefer boundApps (package names), fall back to boundAppUids for old exports.
            val pkgs = o.optJSONArray("boundApps")
            if (pkgs != null && pkgs.length() > 0) {
                for (j in 0 until pkgs.length()) {
                    val app = byPkg[pkgs.optString(j)] ?: continue
                    ProxyManager.addProxyToApp(app.uid, app.packageName, proxyId, name)
                }
            } else {
                val uids = o.optJSONArray("boundAppUids")
                if (uids != null) for (j in 0 until uids.length()) {
                    val uid = uids.optInt(j, Int.MIN_VALUE)
                    if (uid == Int.MIN_VALUE) continue
                    val app = appInfoRepo.getAppInfoByUid(uid) ?: continue
                    ProxyManager.addProxyToApp(uid, app.packageName, proxyId, name)
                }
            }
            // Report the REAL bound count (not just attempts) so the dialog reflects what actually bound.
            val bound = runCatching { ProxyManager.getAppsCountForProxy(proxyId) }.getOrDefault(0)
            wgImportDetail = "$name (lockdown=$lock, $bound app${if (bound == 1) "" else "s"})"
            n++
        }
        return n
    }

    /** Select the DoH endpoint named in the imported `connected_dns_name` pref, so the restored DNS is
     *  actually applied (not left on the default RethinkDNS endpoint). Built-in endpoints (e.g. Quad9)
     *  already exist on a fresh install, so matching by URL finds them. */
    /** Select the DoH named in the EXPORT's connected_dns_name (passed in, NOT re-read from the live
     *  prefs — the running VPN rewrites that pref to the currently-active DNS during the import, which
     *  clobbered the imported value). Returns a short status surfaced in the import dialog (the app's
     *  Logger gates INFO). */
    private suspend fun applyDnsSelection(name: String?): String {
        if (name.isNullOrBlank()) return "no DNS name in export"
        val url = name.substringAfter(",", "").trim() // "Display Name,https://url/..." -> the URL
        val defaults = dohRepo.getAllDefaultDoHEndpoints()
        val doh = defaults.firstOrNull { it.dohURL == url }
            ?: defaults.firstOrNull { name.substringBefore(",").trim() == it.dohName }
            ?: return "no DoH match for '$url' (${defaults.size} built-in)"
        // Select it directly in the DB — reliable, and the restart re-derives the active DNS from this.
        dohRepo.removeConnectionStatus()
        doh.isSelected = true
        dohRepo.update(doh)
        // Best-effort live engine switch (may throw mid-import if the tunnel isn't up — the DB above is
        // what the restart reads).
        runCatching { appConfig.handleDoHChanges(doh) }
        return "selected ${doh.dohName}"
    }

    private suspend fun importBlocklists(context: Context, json: String): Int {
        val o = JSONObject(json)
        var n = 0

        // --- local selection ---
        // Accept both v2 ({value, vname, group, subg}) and v1 (bare int) array elements.
        val localValues = mutableSetOf<Int>()
        o.optJSONArray("local")?.let { a ->
            for (i in 0 until a.length()) {
                val v = a.optJSONObject(i)?.optInt("value", 0) ?: a.optInt(i, 0)
                if (v != 0) localValues.add(v)
            }
        }
        if (localValues.isNotEmpty()) {
            // Mark the rows selected (for the settings UI). No-op if the catalog isn't downloaded yet;
            // the stamp below still enforces the exact selection.
            localTagRepo.updateTags(localValues, 1)
            n += localValues.size
        }
        // The stamp is what firestack enforces and is PORTABLE across blocklist versions (stable
        // per-list value-flags), so restore it directly — no catalog download required for enforcement.
        val stamp = o.optString("localStamp")
        if (stamp.isNotBlank()) persistentState.localBlocklistStamp = stamp
        if (o.has("localCount")) persistentState.numberOfLocalBlocklists = o.optInt("localCount")
        // Enable on-device blocking only if the device ALREADY has the blocklist data downloaded — never
        // enable something we can't enforce (setRDNSLocal would just fail + reset). If absent, the stamp
        // + selection are saved and it activates once the user downloads the on-device blocklist.
        val enabled = o.optBoolean("localEnabled", false)
        val ts = persistentState.localBlocklistTimestamp
        if (enabled && ts > 0 && Utilities.hasLocalBlocklists(context, ts)) {
            persistentState.blocklistEnabled = true
        }

        // --- remote selection (enforced server-side via the RethinkDNS+ URL; ids only) ---
        o.optJSONArray("remote")?.let { a ->
            val set = (0 until a.length())
                .map { a.optJSONObject(it)?.optInt("value", 0) ?: a.optInt(it, 0) }
                .filter { it != 0 }.toSet()
            if (set.isNotEmpty()) { remoteTagRepo.updateTags(set, 1); n += set.size }
        }
        return n
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun importFonts(context: Context, files: Map<String, ByteArray>) {
        val dir = CustomUi.fontsDir(context)
        for ((name, bytes) in files) {
            if (!name.startsWith("fonts/")) continue
            val safe = File(name).name // basename only — no path traversal
            if (safe.isBlank()) continue
            try {
                File(dir, safe).writeBytes(bytes)
            } catch (e: Exception) {
                // skip a bad font; the rest of the import still applies
            }
        }
    }

    // --- DNS custom endpoints + proxies (Stage 2) ---
    // The endpoint repos don't expose getAll, so go via the DAOs (AppDatabase). Only CUSTOM (user-added)
    // endpoints are exported — built-ins are re-seeded by the app. DnsCrypt relays + Rethink+ endpoints
    // are skipped (niche, no clean getAll). Import dedupes by name and resets the autoincrement id so
    // each becomes a fresh row (no id collisions, no re-import duplicates).

    private fun exportDns(): String {
        val o = JSONObject()
        o.put("doh", JSONArray(gson.toJson(db.dohEndpointsDAO().getAll().filter { it.isCustom })))
        o.put("dot", JSONArray(gson.toJson(db.dotEndpointDao().getAll().filter { it.isCustom })))
        o.put("dnscrypt", JSONArray(gson.toJson(db.dnsCryptEndpointDAO().getAll().filter { it.isCustom })))
        o.put("dnsproxy", JSONArray(gson.toJson(db.dnsProxyEndpointDAO().getAll().filter { it.isCustom })))
        o.put("odoh", JSONArray(gson.toJson(db.odohEndpointDao().getAll().filter { it.isCustom })))
        return o.toString(2)
    }

    private fun importDns(json: String): Int {
        val o = JSONObject(json)
        var n = 0
        gson.fromJson(o.optJSONArray("doh")?.toString() ?: "[]", Array<DoHEndpoint>::class.java)?.let { arr ->
            val have = db.dohEndpointsDAO().getAll().filter { it.isCustom }.map { it.dohName }.toHashSet()
            for (e in arr) { if (e.dohName in have) continue; e.id = 0; db.dohEndpointsDAO().insertReplace(e); n++ }
        }
        gson.fromJson(o.optJSONArray("dot")?.toString() ?: "[]", Array<DoTEndpoint>::class.java)?.let { arr ->
            val have = db.dotEndpointDao().getAll().filter { it.isCustom }.map { it.name }.toHashSet()
            for (e in arr) { if (e.name in have) continue; e.id = 0; db.dotEndpointDao().insertReplace(e); n++ }
        }
        gson.fromJson(o.optJSONArray("dnscrypt")?.toString() ?: "[]", Array<DnsCryptEndpoint>::class.java)?.let { arr ->
            val have = db.dnsCryptEndpointDAO().getAll().filter { it.isCustom }.map { it.dnsCryptName }.toHashSet()
            for (e in arr) { if (e.dnsCryptName in have) continue; e.id = 0; db.dnsCryptEndpointDAO().insert(e); n++ }
        }
        gson.fromJson(o.optJSONArray("dnsproxy")?.toString() ?: "[]", Array<DnsProxyEndpoint>::class.java)?.let { arr ->
            val have = db.dnsProxyEndpointDAO().getAll().filter { it.isCustom }.map { it.proxyName }.toHashSet()
            for (e in arr) { if (e.proxyName in have) continue; e.id = 0; db.dnsProxyEndpointDAO().insertWithReplace(e); n++ }
        }
        gson.fromJson(o.optJSONArray("odoh")?.toString() ?: "[]", Array<ODoHEndpoint>::class.java)?.let { arr ->
            val have = db.odohEndpointDao().getAll().filter { it.isCustom }.map { it.name }.toHashSet()
            for (e in arr) { if (e.name in have) continue; e.id = 0; db.odohEndpointDao().insertReplace(e); n++ }
        }
        return n
    }

    private fun exportProxies(): String =
        JSONArray(gson.toJson(db.proxyEndpointDAO().getAll().filter { it.isCustom })).toString(2)

    private fun importProxies(json: String): Int {
        val arr = gson.fromJson(json, Array<ProxyEndpoint>::class.java) ?: return 0
        val have = db.proxyEndpointDAO().getAll().filter { it.isCustom }.map { it.proxyName }.toHashSet()
        var n = 0
        for (e in arr) { if (e.proxyName in have) continue; e.id = 0; db.proxyEndpointDAO().insert(e); n++ }
        return n
    }

    /** Read every ZIP entry into memory keyed by entry name. */
    private fun readZip(zip: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(zip.inputStream()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val buf = ByteArrayOutputStream()
                    zis.copyTo(buf)
                    out[e.name] = buf.toByteArray()
                }
                e = zis.nextEntry
            }
        }
        return out
    }
}
