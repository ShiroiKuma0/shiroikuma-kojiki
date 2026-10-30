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
                else -> return base
            }
        val badge = AppCompatResources.getDrawable(context, badgeRes)?.mutate() ?: return base

        if (CustomUi.customThemeActive) {
            val cfg = CustomUiConfig(context)
            val allowed = cfg.fwAllowedColor
            val own =
                if (status == FirewallManager.FirewallStatus.BYPASS_UNIVERSAL) cfg.fwBypassUnivColor
                else cfg.fwBypassDnsColor
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
