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

import android.graphics.drawable.Drawable
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import com.celzero.bravedns.R

/**
 * Fork (白い熊 考直): the leading icon of the rows upstream left without one (black-yellow skill,
 * line F25: every row of a list, a sheet and a settings screen carries an icon coloured by what it
 * does).
 *
 * Upstream's layouts are **not** edited — that would conflict on every rebase. The row is found by
 * the entry name of its id ([ROWS]); the icon is drawn in the row's overlay, in start padding added
 * once for it. Called from the [CustomUi] pass, so Custom theme only.
 *
 * After a rebase: a row id that vanished costs nothing (it is simply never met); a new icon-less
 * row gets a line here.
 */
object KojikiRowIcons {

    private class Spec(val icon: Int, val role: String)

    private val ROWS: Map<String, Spec> = mapOf(
        // universal firewall
        "firewall_screen_ll" to Spec(R.drawable.ic_device_lock, "icon_block"),
        "firewall_background_ll" to Spec(R.drawable.ic_pause, "icon_block"),
        "firewall_unknown_connection_ll" to Spec(R.drawable.ic_unknown_app, "icon_block"),
        "firewall_udp_connection_ll" to Spec(R.drawable.ic_udp, "icon_block"),
        "firewall_disallow_dns_bypass_ll" to Spec(R.drawable.ic_prevent_dns_leaks, "icon_block"),
        "firewall_block_new_app_ll" to Spec(R.drawable.ic_app_info, "icon_block"),
        "firewall_check_ipv4_ll" to Spec(R.drawable.ic_ip_address, "icon_block"),
        "firewall_block_metered_ll" to Spec(R.drawable.ic_univ_metered, "icon_block"),
        "firewall_block_http_ll" to Spec(R.drawable.ic_http, "icon_block"),
        "firewall_univ_lockdown_ll" to Spec(R.drawable.ic_global_lockdown, "icon_block"),
        // WireGuard / RPN tunnel settings
        "hop_rl" to Spec(R.drawable.ic_hop, "icon_connect"),
        "lockdown_rl" to Spec(R.drawable.ic_global_lockdown, "icon_block"),
        "catch_all_rl" to Spec(R.drawable.ic_network, "icon_connect"),
        "use_mobile_rl" to Spec(R.drawable.ic_meter_mobile_only, "icon_view"),
        "ssid_filter_rl" to Spec(R.drawable.ic_firewall_wifi_on, "icon_view"),
        // proxies
        "tcp_proxy_heading_rl" to Spec(R.drawable.ic_proxy, "icon_connect"),
        "warp_heading_rl" to Spec(R.drawable.ic_network_tunnel, "icon_connect"),
        "dialog_proxy_udp_header" to Spec(R.drawable.ic_udp, "icon_connect"),
        "dialog_proxy_exclude_apps_header" to Spec(R.drawable.ic_firewall_exclude_on, "icon_contact"),
        "dialog_dns_proxy_exclude_apps_header" to Spec(R.drawable.ic_firewall_exclude_on, "icon_contact"),
        // sheets
        "tasker_wg_rl" to Spec(R.drawable.ic_tasker, "icon_connect"),
        "brbs_proc_info_layout" to Spec(R.drawable.ic_proc, "icon_view"),
        "brbs_select_all_layout" to Spec(R.drawable.ic_tick, "icon_view"),
        "bs_conn_rule_row_ip" to Spec(R.drawable.ic_ip_address, "icon_block"),
        "bs_conn_rule_row_domain" to Spec(R.drawable.ic_website, "icon_block"),
        "bsdl_rule_row_app" to Spec(R.drawable.ic_app_info, "icon_contact"),
        "bsdl_rule_row_global" to Spec(R.drawable.ic_website, "icon_view"),
        "lockdown_check_disable_row" to Spec(R.drawable.ic_global_lockdown, "icon_block"),
    )

    /** What a row carries once it has its icon. */
    private class Held(var drawable: Drawable?, var generation: Int)

    private val NONE = Spec(0, "")
    private val byId = SparseArray<Spec>()

    private fun specOf(v: View): Spec? {
        val id = v.id
        if (id == View.NO_ID) return null
        var spec = byId[id]
        if (spec == null) {
            val name = try { v.resources.getResourceEntryName(id) } catch (_: Exception) { "" }
            spec = ROWS[name] ?: NONE
            byId.put(id, spec)
        }
        return if (spec === NONE) null else spec
    }

    private const val ICON_DP = 20f
    private const val ROOM_DP = 30f

    /** Give [row] its icon if it is one of [ROWS]; cheap for every other view. */
    fun apply(row: ViewGroup) {
        val spec = specOf(row) ?: return
        val generation = KojikiRoles.generation()
        val held = row.getTag(R.id.kojiki_row_icon) as? Held
        if (held != null && held.generation == generation) return

        val density = row.resources.displayMetrics.density
        val size = (ICON_DP * density + 0.5f).toInt()
        val room = (ROOM_DP * density + 0.5f).toInt()
        val drawable = KojikiPills.icon(row.context, spec.icon, spec.role, ICON_DP.toInt())

        if (held != null) {
            // a colour changed: swap the drawable, keep the padding and the listener
            held.drawable?.let { row.overlay.remove(it) }
            held.drawable = drawable
            held.generation = generation
        } else {
            val h = Held(drawable, generation)
            row.setTag(R.id.kojiki_row_icon, h)
            val base = row.paddingStart
            row.setPaddingRelative(base + room, row.paddingTop, row.paddingEnd, row.paddingBottom)
            row.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val d = h.drawable ?: return@addOnLayoutChangeListener
                val rtl = v.layoutDirection == View.LAYOUT_DIRECTION_RTL
                val gap = (4 * density + 0.5f).toInt()
                val x = if (rtl) v.width - base - gap - size else base + gap
                val y = (v.height - size) / 2
                d.setBounds(x, y, x + size, y + size)
                v.invalidate()
            }
        }
        if (drawable != null) {
            row.overlay.add(drawable)
            row.requestLayout()
        }
    }
}
