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

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import androidx.appcompat.content.res.AppCompatResources
import com.celzero.bravedns.R
import com.celzero.bravedns.service.FirewallManager

/**
 * Fork (白い熊 考直): the WiFi / mobile-data toggle of a bypassed app, drawn *allowed* with the
 * bypass's own glyph as a badge in the corner.
 *
 * Upstream greys both toggles out under either bypass, which reads as "off" — the opposite of the
 * truth: choosing a bypass stores both as allowed (`ConnectionStatus.ALLOW`), and the app does reach
 * both networks. So the toggle keeps its lit icon and carries the badge of the button that is on —
 * Bypass Universal's or Bypass DNS & Firewall's — and the row says at a glance which bypass it is.
 *
 * The badge sits on a disc of the background colour so it reads clearly over the icon's strokes, in
 * the corner each icon leaves empty: bottom-right of the WiFi arcs, top-left of the data triangle.
 * Under the Custom theme the layers are tinted one by one — icon in the "allowed" colour, badge in
 * the bypass's own colour from the 白い熊 考直 UI page (falling back to "allowed") — so callers must
 * **not** put a view-level colour filter over the result: that would paint the disc too.
 */
object KojikiToggleBadge {

    private const val BADGE_FRACTION = 0.58f
    private const val DISC_FRACTION = 0.72f

    /** Whether [status] is one of the two bypasses, i.e. whether its toggles get a badge. */
    fun isBypass(status: FirewallManager.FirewallStatus): Boolean =
        status == FirewallManager.FirewallStatus.BYPASS_UNIVERSAL ||
            status == FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL

    /**
     * The status a WiFi / mobile-data toggle leaves behind. Upstream drops every status to NONE
     * there, so a bypassed app could not be blocked on one network without losing its bypass. A
     * bypass is **kept**: the firewall tests the app's own WiFi / data block before either bypass
     * (`TunFirewallManager.appBlocked`), so "bypassed on WiFi, blocked on mobile data" is a state
     * the engine has always honoured — only the toggles could not reach it. Exclude and Isolate
     * still drop to NONE: neither can be combined with a per-network block.
     */
    fun keptStatus(status: FirewallManager.FirewallStatus): FirewallManager.FirewallStatus =
        if (isBypass(status)) status else FirewallManager.FirewallStatus.NONE

    /** Under a bypass, WiFi is blocked by the app's own toggle. */
    fun wifiOff(status: FirewallManager.FirewallStatus, conn: FirewallManager.ConnectionStatus) =
        isBypass(status) &&
            (conn == FirewallManager.ConnectionStatus.UNMETERED ||
                conn == FirewallManager.ConnectionStatus.BOTH)

    /** Under a bypass, mobile data is blocked by the app's own toggle. */
    fun dataOff(status: FirewallManager.FirewallStatus, conn: FirewallManager.ConnectionStatus) =
        isBypass(status) &&
            (conn == FirewallManager.ConnectionStatus.METERED ||
                conn == FirewallManager.ConnectionStatus.BOTH)

    /**
     * Whether [status]'s toggles are drawn lit with a badge: the two bypasses, and Exclude — an
     * excluded app's data passes just as freely (outside the tunnel), so greying its toggles said
     * the opposite of the truth for it too.
     */
    fun isBadged(status: FirewallManager.FirewallStatus): Boolean =
        isBypass(status) || status == FirewallManager.FirewallStatus.EXCLUDE

    /**
     * The Roaming toggle. It carries no badge: it is the one control that means the same under
     * every status, so it is simply lit or struck through.
     */
    fun roaming(context: Context, blocked: Boolean): Drawable? {
        val d =
            AppCompatResources.getDrawable(
                    context,
                    if (blocked) R.drawable.ic_kojiki_roaming_off
                    else R.drawable.ic_kojiki_roaming_on)
                ?.mutate() ?: return null
        if (CustomUi.customThemeActive) {
            val cfg = CustomUiConfig(context)
            val c = if (blocked) cfg.fwDeniedColor else cfg.fwAllowedColor
            if (c != 0) d.setTint(c)
        }
        return d
    }

    fun wifi(context: Context, status: FirewallManager.FirewallStatus): Drawable? =
        build(context, R.drawable.ic_firewall_wifi_on, status, Gravity.BOTTOM or Gravity.END)

    fun data(context: Context, status: FirewallManager.FirewallStatus): Drawable? =
        build(context, R.drawable.ic_firewall_data_on, status, Gravity.TOP or Gravity.START)

    private fun build(
        context: Context,
        baseRes: Int,
        status: FirewallManager.FirewallStatus,
        corner: Int
    ): Drawable? {
        val base = AppCompatResources.getDrawable(context, baseRes)?.mutate() ?: return null
        val badgeRes =
            when (status) {
                FirewallManager.FirewallStatus.BYPASS_UNIVERSAL -> R.drawable.ic_firewall_bypass_on
                FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL ->
                    R.drawable.ic_bypass_dns_firewall_on
                FirewallManager.FirewallStatus.EXCLUDE -> R.drawable.ic_firewall_exclude_on
                else -> return base
            }
        val badge = AppCompatResources.getDrawable(context, badgeRes)?.mutate() ?: return base

        if (CustomUi.customThemeActive) {
            val cfg = CustomUiConfig(context)
            val allowed = cfg.fwAllowedColor
            val own =
                when (status) {
                    FirewallManager.FirewallStatus.BYPASS_UNIVERSAL -> cfg.fwBypassUnivColor
                    FirewallManager.FirewallStatus.EXCLUDE -> cfg.fwExcludedColor
                    else -> cfg.fwBypassDnsColor
                }
            if (allowed != 0) base.setTint(allowed)
            val badgeColor = if (own != 0) own else allowed
            if (badgeColor != 0) badge.setTint(badgeColor)
        }

        val size = base.intrinsicWidth.takeIf { it > 0 } ?: return base
        val discSize = (size * DISC_FRACTION).toInt()
        val badgeSize = (size * BADGE_FRACTION).toInt()
        val pad = (discSize - badgeSize) / 2
        val disc =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(backgroundColor(context))
            }

        return LayerDrawable(arrayOf(base, disc, badge)).apply {
            setLayerSize(1, discSize, discSize)
            setLayerGravity(1, corner)
            setLayerSize(2, badgeSize, badgeSize)
            setLayerGravity(2, corner)
            // centre the badge on its disc, whichever corner both sit in
            setLayerInset(2, pad, pad, pad, pad)
        }
    }

    private fun backgroundColor(context: Context): Int {
        if (CustomUi.customThemeActive) return CustomUiConfig(context).backgroundColor
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(android.R.attr.colorBackground, tv, true)) tv.data
        else 0xFF000000.toInt()
    }
}
