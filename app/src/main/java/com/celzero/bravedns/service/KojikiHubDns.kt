/*
 * Fork (白い熊 考直): the hub's resolver is gone — a profile must not name it.
 *
 * The WireGuard hub ran a resolver on 10.9.0.1 until 2026-11-25; the profile for it carries
 * "DNS = 10.9.0.1". With Split DNS off nothing asks that address, so the line is harmless — and
 * the moment Split DNS is switched on, every lookup of a bound app would go to a resolver that
 * no longer exists. The engine refuses a profile without a DNS line, so the line is rewritten
 * to Quad9 (the resolver the fork uses everywhere else) rather than removed.
 *
 * Once per installation (prefs kojiki_migrations, key hub_dns_once_042), on the first load of
 * the profiles, before the engine sees them. A line set by hand afterwards is left alone.
 */
package com.celzero.bravedns.service

import android.content.Context
import com.celzero.bravedns.wireguard.Config
import java.io.ByteArrayInputStream
import java.io.File

object KojikiHubDns {
    private const val PREFS = "kojiki_migrations"
    private const val KEY = "hub_dns_once_042"
    private const val DEAD = "10.9.0.1"
    private const val REPLACEMENT = "9.9.9.9"

    private var changed = 0

    private val dnsLine = Regex("(?im)^([ \\t]*DNS[ \\t]*=[ \\t]*)(.*)$")

    fun pending(ctx: Context): Boolean =
        try {
            !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
        } catch (_: Exception) {
            false
        }

    fun done(ctx: Context) {
        if (changed == 0) KojikiNetDiag.onEvent("wireguard profiles checked: none names $DEAD (one-time, 0.5.8+042)")
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
        } catch (_: Exception) {
        }
    }

    /** The profile text with the dead resolver replaced, or null when it does not name it. */
    fun rewrite(text: String): String? {
        var hit = false
        val out =
            dnsLine.replace(text) { m ->
                val servers = m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (servers.none { it == DEAD }) {
                    m.value
                } else {
                    hit = true
                    val fixed = servers.map { if (it == DEAD) REPLACEMENT else it }.distinct()
                    m.groupValues[1] + fixed.joinToString(", ")
                }
            }
        return if (hit) out else null
    }

    /**
     * Returns the bytes to parse for this profile: the corrected text when the file named the
     * dead resolver, the rewrite parses and was written back; otherwise [bytes] untouched.
     */
    fun fix(name: String, file: File, bytes: ByteArray): ByteArray {
        return try {
            val fixed = rewrite(String(bytes, Charsets.UTF_8)) ?: return bytes
            val out = fixed.toByteArray(Charsets.UTF_8)
            // never write what would not load
            Config.parse(ByteArrayInputStream(out)) ?: return bytes
            // written beside the profile and moved over it, so a crash cannot leave half a file
            val tmp = File(file.parentFile, file.name + ".kojiki-tmp")
            tmp.writeText(fixed, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                KojikiNetDiag.onEvent("wireguard profile $name: dns line left as it is — the file could not be replaced")
                return bytes
            }
            changed++
            KojikiNetDiag.onEvent("wireguard profile $name: dns $DEAD (the hub's resolver, gone) set to $REPLACEMENT (one-time, 0.5.8+042)")
            out
        } catch (e: Exception) {
            KojikiNetDiag.onEvent("wireguard profile $name: dns line left as it is — ${e.message}")
            bytes
        }
    }
}
