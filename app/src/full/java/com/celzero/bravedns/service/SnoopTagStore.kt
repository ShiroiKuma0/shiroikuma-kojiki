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
package com.celzero.bravedns.service

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Fork (白い熊 考直): user-defined tags / categories for Snooping-panel domains.
 *
 * Lets the user label a domain (e.g. "WhatsApp", "Aurora / microG") so that when it shows up again
 * in the panel — already allowed — they recognise why and don't re-block it. Purely informational:
 * tags never change block/allow behaviour.
 *
 * Stored in SharedPreferences as two JSON blobs (a tag list + a domain→tag-names map) so it's fully
 * additive — no Room schema/migration. Tags are keyed by name (case-insensitive); a tag applies to a
 * domain globally (all apps), matching the user's mental model ("g.whatsapp.net is a WhatsApp domain").
 */
object SnoopTagStore {

    /** color == null → render with the default chip style (set in the 白い熊 考直 UI). */
    data class Tag(val name: String, val color: Int?)

    private const val PREFS = "snoop_tags"
    private const val KEY_TAGS = "tags"     // JSON: Array<Tag>
    private const val KEY_ASSIGN = "assign" // JSON: Map<domain, List<tagName>>

    // Seed palette for new tags (the picker opens on the next one; the user can change it).
    private val PALETTE = intArrayOf(
        0xFFFFEB3B.toInt(), 0xFF4CAF50.toInt(), 0xFF2196F3.toInt(), 0xFFE91E63.toInt(),
        0xFFFF9800.toInt(), 0xFF9C27B0.toInt(), 0xFF00BCD4.toInt(), 0xFFCDDC39.toInt()
    )

    private val gson = Gson()
    private var tagsCache: MutableList<Tag>? = null
    private var assignCache: MutableMap<String, MutableList<String>>? = null

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun tags(c: Context): List<Tag> {
        tagsCache?.let { return it }
        val json = prefs(c).getString(KEY_TAGS, null)
        val list =
            if (json.isNullOrBlank()) mutableListOf()
            else
                try {
                    gson.fromJson(json, Array<Tag>::class.java)?.toMutableList() ?: mutableListOf()
                } catch (e: Exception) {
                    mutableListOf()
                }
        tagsCache = list
        return list
    }

    /** A suggested colour for a brand-new tag (cycles the palette). */
    fun suggestedColor(c: Context): Int = PALETTE[tags(c).size % PALETTE.size]

    @Synchronized
    fun addOrUpdateTag(c: Context, tag: Tag) {
        val list = tags(c).toMutableList()
        val i = list.indexOfFirst { it.name.equals(tag.name, ignoreCase = true) }
        if (i >= 0) list[i] = tag else list.add(tag)
        tagsCache = list
        prefs(c).edit().putString(KEY_TAGS, gson.toJson(list)).apply()
    }

    /** Rename and/or recolour an existing tag. A rename rewrites the tag name in every assignment. */
    @Synchronized
    fun updateTag(c: Context, oldName: String, newName: String, color: Int?) {
        val list = tags(c).toMutableList()
        val idx = list.indexOfFirst { it.name.equals(oldName, ignoreCase = true) }
        val tag = Tag(newName, color)
        if (idx >= 0) list[idx] = tag else list.add(tag)
        tagsCache = list
        val editor = prefs(c).edit().putString(KEY_TAGS, gson.toJson(list))
        if (!oldName.equals(newName, ignoreCase = true)) {
            val a = assignments(c)
            a.values.forEach { v ->
                val i = v.indexOfFirst { it.equals(oldName, ignoreCase = true) }
                if (i >= 0) v[i] = newName
            }
            assignCache = a
            editor.putString(KEY_ASSIGN, gson.toJson(a))
        }
        editor.apply()
    }

    /** Delete a tag everywhere — drop it from the tag list and from every domain it was assigned to. */
    @Synchronized
    fun deleteTag(c: Context, name: String) {
        val list = tags(c).toMutableList()
        list.removeAll { it.name.equals(name, ignoreCase = true) }
        tagsCache = list
        val a = assignments(c)
        a.values.forEach { v -> v.removeAll { it.equals(name, ignoreCase = true) } }
        a.entries.removeAll { it.value.isEmpty() }
        assignCache = a
        prefs(c).edit()
            .putString(KEY_TAGS, gson.toJson(list))
            .putString(KEY_ASSIGN, gson.toJson(a))
            .apply()
    }

    @Synchronized
    private fun assignments(c: Context): MutableMap<String, MutableList<String>> {
        assignCache?.let { return it }
        val json = prefs(c).getString(KEY_ASSIGN, null)
        val map: MutableMap<String, MutableList<String>> =
            if (json.isNullOrBlank()) mutableMapOf()
            else
                try {
                    val type = object : TypeToken<MutableMap<String, MutableList<String>>>() {}.type
                    gson.fromJson(json, type) ?: mutableMapOf()
                } catch (e: Exception) {
                    mutableMapOf()
                }
        assignCache = map
        return map
    }

    /** Tag names assigned to a domain (raw, may include names whose tag was since deleted). */
    fun tagNamesFor(c: Context, domain: String): List<String> =
        assignments(c)[domain]?.toList() ?: emptyList()

    /** Resolved tags for a domain (only those that still exist), in assignment order. */
    fun tagsFor(c: Context, domain: String): List<Tag> {
        val names = tagNamesFor(c, domain)
        if (names.isEmpty()) return emptyList()
        val byName = tags(c).associateBy { it.name.lowercase() }
        return names.mapNotNull { byName[it.lowercase()] }
    }

    @Synchronized
    fun setDomainTags(c: Context, domain: String, tagNames: Collection<String>) {
        val a = assignments(c)
        if (tagNames.isEmpty()) a.remove(domain) else a[domain] = tagNames.toMutableList()
        assignCache = a
        prefs(c).edit().putString(KEY_ASSIGN, gson.toJson(a)).apply()
    }
}
