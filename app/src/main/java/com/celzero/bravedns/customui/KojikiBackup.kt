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
import android.util.Base64
import com.celzero.bravedns.service.SnoopTagStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Fork (白い熊 考直): granular export/import of the fork's OWN settings — the 白い熊 考直 UI theme/font
 * (the `kojiki_ui` prefs, see [CustomUiConfig]) and the Snooping-panel tags/assignments (the
 * `snoop_tags` prefs, see [SnoopTagStore]) — plus any imported font files. Bundled into a single JSON
 * blob, deliberately separate from RethinkDNS's own (DNS/firewall/WireGuard) backup, so the user can
 * carry just their kojiki customisations across a clean reinstall.
 *
 * Values are type-tagged (i/l/f/b/s/ss) so the SharedPreferences round-trip is exact; unknown keys are
 * preserved as-is, so future kojiki settings export/import without touching this file.
 */
object KojikiBackup {

    private const val FORMAT = "kojiki-settings"
    private const val VERSION = 1

    // SharedPreferences files owned by the fork. Names MUST match CustomUiConfig.PREFS ("kojiki_ui")
    // and SnoopTagStore.PREFS ("snoop_tags"); both are stable, fork-private stores.
    private val PREF_FILES = listOf("kojiki_ui", "snoop_tags")

    /** Build the export JSON (pretty-printed). */
    fun export(context: Context): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("app", context.packageName)
        val prefs = JSONObject()
        for (name in PREF_FILES) prefs.put(name, exportPrefs(context, name))
        root.put("prefs", prefs)
        root.put("fonts", exportFonts(context))
        return root.toString(2)
    }

    /**
     * Restore from an export JSON. Replaces the kojiki prefs wholesale and writes back any bundled
     * font files, then drops in-memory caches. Returns a short human summary; throws on a bad file.
     */
    fun import(context: Context, json: String): String {
        val root = JSONObject(json)
        require(root.optString("format") == FORMAT) { "not a 白い熊 考直 settings file" }
        val prefs = root.optJSONObject("prefs") ?: JSONObject()
        var prefCount = 0
        for (name in PREF_FILES) {
            val obj = prefs.optJSONObject(name) ?: continue
            prefCount += importPrefs(context, name, obj)
        }
        val fontCount = importFonts(context, root.optJSONObject("fonts"))
        // The backing prefs/files were swapped under the live caches — drop them so the import applies.
        SnoopTagStore.invalidateCache()
        CustomUi.invalidateCaches()
        return "$prefCount settings" + if (fontCount > 0) " + $fontCount font(s)" else ""
    }

    private fun exportPrefs(context: Context, name: String): JSONObject {
        val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val obj = JSONObject()
        for ((k, v) in sp.all) {
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
        return obj
    }

    private fun importPrefs(context: Context, name: String, obj: JSONObject): Int {
        val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val ed = sp.edit().clear()
        var n = 0
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
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
        ed.apply()
        return n
    }

    private fun exportFonts(context: Context): JSONObject {
        val obj = JSONObject()
        CustomUi.fontsDir(context).listFiles()?.forEach { f ->
            if (f.isFile) obj.put(f.name, Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
        }
        return obj
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun importFonts(context: Context, obj: JSONObject?): Int {
        if (obj == null) return 0
        val dir = CustomUi.fontsDir(context)
        var n = 0
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val safe = File(key).name // basename only — no path traversal
            if (safe.isBlank()) continue
            try {
                File(dir, safe).writeBytes(Base64.decode(obj.optString(key), Base64.NO_WRAP))
                n++
            } catch (e: Exception) {
                // skip a bad font entry; the rest of the import still applies
            }
        }
        return n
    }
}
