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

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.android.material.color.KojikiColorLoaderBridge
import java.util.WeakHashMap

/** One colour role: see [KojikiRoleTable]. */
class KojikiRole(
    val key: String,
    val resId: Int,
    val area: KojikiArea,
    val label: String,
    val default: (KojikiRoles.Scope) -> Int
)

/**
 * Fork (白い熊 考直): reading and writing the colour roles.
 *
 * A role's value is the stored one (prefs `kojiki_ui`, key `kj_role_<key>`, so it travels in the
 * Appearance export with the rest of the page) or else its default from [KojikiRoleTable], which
 * is derived from the three primaries or from another role. Values are resolved once per
 * [generation] and kept, because the run-time restyling pass asks for them on every layout.
 */
object KojikiRoles {
    private const val PREFS = "kojiki_ui"
    private const val PREFIX = "kj_role_"

    private val byKey: Map<String, KojikiRole> by lazy { KojikiRoleTable.ALL.associateBy { it.key } }

    @Volatile private var generation = 1
    @Volatile private var resolved: Map<String, Int>? = null

    fun generation(): Int = generation

    /** Call after any colour of the page changed — a role or a primary. */
    fun changed() {
        resolved = null
        generation++
    }

    /** Resolves one pass of the table: the primaries read once, every role memoised. */
    class Scope(context: Context) {
        private val cfg = CustomUiConfig(context)
        private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private val memo = HashMap<String, Int>()
        val ac: Int = cfg.accentColor
        val bg: Int = cfg.backgroundColor
        val tx: Int = cfg.textColor
        val note: Int = cfg.noteColor
        val surfaceCfg: Int = cfg.surfaceColor
        val switchCfg: Int = cfg.switchColor

        fun r(key: String): Int {
            memo[key]?.let { return it }
            val role = byKey[key] ?: return ac
            val stored = PREFIX + key
            val v = if (prefs.contains(stored)) prefs.getInt(stored, 0) else role.default(this)
            memo[key] = v
            return v
        }

        fun default(key: String): Int = byKey[key]?.default?.invoke(this) ?: ac
    }

    private fun all(context: Context): Map<String, Int> {
        resolved?.let { return it }
        val s = Scope(context)
        val m = HashMap<String, Int>(KojikiRoleTable.ALL.size * 2)
        for (role in KojikiRoleTable.ALL) m[role.key] = s.r(role.key)
        resolved = m
        return m
    }

    fun color(context: Context, key: String): Int = all(context)[key] ?: Color.MAGENTA

    fun defaultColor(context: Context, key: String): Int = Scope(context).default(key)

    fun isSet(context: Context, key: String): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .contains(PREFIX + key)

    fun set(context: Context, key: String, color: Int) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(PREFIX + key, color).apply()
        changed()
    }

    /** Back to the default: the role follows the primaries again. */
    fun reset(context: Context, key: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(PREFIX + key).apply()
        changed()
    }

    fun resetAll(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = prefs.edit()
        for (k in prefs.all.keys) if (k.startsWith(PREFIX)) e.remove(k)
        e.apply()
        changed()
    }

    fun inArea(area: KojikiArea): List<KojikiRole> = KojikiRoleTable.ALL.filter { it.area == area }

    /** Colour resource id -> ARGB, for the loader. */
    fun resourceColors(context: Context): Map<Int, Int> {
        val values = all(context)
        val m = HashMap<Int, Int>()
        for (role in KojikiRoleTable.ALL) if (role.resId != 0) m[role.resId] = values[role.key] ?: continue
        for ((resId, key) in KojikiRoleTable.DIRECT) m[resId] = values[key] ?: continue
        return m
    }

    /** [f] of the way from [a] to [b], opaque. */
    fun mix(a: Int, b: Int, f: Float): Int {
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * f + 0.5f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** [c] at opacity [f]. */
    fun al(c: Int, f: Float): Int =
        (c and 0x00FFFFFF) or (((f * 255f + 0.5f).toInt().coerceIn(0, 255)) shl 24)
}

/**
 * Fork (白い熊 考直): lays the roles over the app's colour resources, so that a layout, a drawable
 * or a theme attribute that names `@color/kj_…` (or one of upstream's colours listed in
 * [KojikiRoleTable.DIRECT]) resolves to the role's current value. Upstream's layouts are therefore
 * not edited for colour at all. Needs Android 11; below it the roles reach only what code paints.
 *
 * Applied to the application's resources at start and to each activity's before it is created;
 * an activity whose resources carry an older generation is recreated when it comes back.
 */
object KojikiPalette {
    private const val TAG = "KojikiPalette"

    private class Applied(val loader: ResourcesLoader, val generation: Int)

    private val applied = WeakHashMap<Resources, Applied>()
    private val builtAt = WeakHashMap<Activity, Int>()

    private var isCustom: () -> Boolean = { false }

    fun install(app: Application, custom: () -> Boolean) {
        isCustom = custom
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        apply(app)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, state: Bundle?) {
                apply(activity)
                builtAt[activity] = KojikiRoles.generation()
            }

            override fun onActivityResumed(activity: Activity) {
                val at = builtAt[activity] ?: return
                if (at != KojikiRoles.generation() && custom()) {
                    builtAt[activity] = KojikiRoles.generation()
                    activity.recreate()
                }
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, out: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun custom(): Boolean = try { isCustom() } catch (_: Throwable) { false }

    @Synchronized
    fun apply(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        applyTo(context)
        val app = context.applicationContext
        if (app != null && app !== context) applyTo(app)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun applyTo(context: Context) {
        val resources = context.resources
        val current = applied[resources]
        // Under upstream's own themes nothing is laid over the resources.
        if (!custom()) {
            if (current != null) {
                resources.removeLoaders(current.loader)
                applied.remove(resources)
            }
            return
        }
        val generation = KojikiRoles.generation()
        if (current != null && current.generation == generation) return
        try {
            val loader =
                KojikiColorLoaderBridge.create(context, KojikiRoles.resourceColors(context)) ?: return
            if (current != null) resources.removeLoaders(current.loader)
            resources.addLoaders(loader)
            applied[resources] = Applied(loader, generation)
        } catch (e: Throwable) {
            Log.e(TAG, "could not lay the colour roles over the resources", e)
        }
    }
}
