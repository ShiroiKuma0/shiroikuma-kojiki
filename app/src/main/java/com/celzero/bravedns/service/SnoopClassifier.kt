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

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_DNS
import android.content.Context
import com.celzero.bravedns.database.DnsLog
import com.celzero.bravedns.database.SnoopEvent
import com.celzero.bravedns.database.SnoopEventRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Locale

// Fork (白い熊 考直) — Snooping panel feature.
// Classifies a single DnsLog as a suspected telemetry/snooping lookup and, on a
// positive, upserts a persistent SnoopEvent. Hooked at the DnsLog insert path
// (DnsLogTracker.insertBatch) so classification is per-insert, never a table scan.
object SnoopClassifier : KoinComponent {

    private const val TAG = "SnoopClassifier"
    private const val MATCHERS_ASSET = "snoop_matchers.txt"

    const val SEV_LOW = 1
    const val SEV_MEDIUM = 2
    const val SEV_HIGH = 3

    const val CAT_TRACKER = "tracker"
    const val CAT_TELEMETRY = "telemetry"
    const val CAT_CRASH = "crash"
    const val CAT_ANALYTICS = "analytics"
    const val CAT_TRACKING = "tracking"
    const val CAT_SUSPECTED = "suspected"

    private val repository by inject<SnoopEventRepository>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // keyword regex (spec): strong hints in the hostname itself
    private val hostRegex =
        Regex(
            "metric|telemetry|analytic|tracking|crashlytic|sentry|beacon|collector|datacollect",
            RegexOption.IGNORE_CASE
        )

    // bundled glob matchers, loaded once from assets
    @Volatile private var matchers: List<Matcher>? = null

    data class Verdict(val severity: Int, val category: String, val reason: String)

    private class Matcher(val raw: String, private val regex: Regex) {
        // a plain (wildcard-free) base for cheap domain-or-subdomain matching
        private val base: String = raw.removePrefix("*.")
        private val baseIsPlain: Boolean = !base.contains('*')

        fun matches(domain: String): Boolean {
            if (regex.matches(domain)) return true
            if (baseIsPlain) {
                return domain == base || domain.endsWith(".$base")
            }
            return false
        }
    }

    private fun loadMatchers(context: Context): List<Matcher> {
        val cached = matchers
        if (cached != null) return cached
        synchronized(this) {
            val again = matchers
            if (again != null) return again
            val parsed = mutableListOf<Matcher>()
            try {
                context.assets.open(MATCHERS_ASSET).bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val raw = line.trim().lowercase(Locale.ROOT)
                        if (raw.isEmpty() || raw.startsWith("#")) return@forEach
                        parsed.add(Matcher(raw, toRegex(raw)))
                    }
                }
            } catch (e: Exception) {
                Logger.w(LOG_TAG_DNS, "$TAG: failed reading $MATCHERS_ASSET: ${e.message}")
            }
            matchers = parsed
            Logger.i(LOG_TAG_DNS, "$TAG: loaded ${parsed.size} snoop matchers")
            return parsed
        }
    }

    // glob -> anchored regex; '*' matches any run of characters
    private fun toRegex(raw: String): Regex {
        val core = raw.split("*").joinToString(".*") { Regex.escape(it) }
        return Regex("^$core$", RegexOption.IGNORE_CASE)
    }

    fun classify(context: Context, domain: String, blockLists: String): Verdict? {
        // strong positive: on-device blocklist tagged it (tracker/privacy categories)
        if (blockLists.isNotEmpty()) {
            return Verdict(SEV_HIGH, CAT_TRACKER, "on-device blocklist")
        }
        // known telemetry endpoint from the bundled matcher list
        if (loadMatchers(context).any { it.matches(domain) }) {
            return Verdict(SEV_MEDIUM, CAT_TELEMETRY, "known telemetry endpoint")
        }
        // suspicious hostname by keyword
        if (hostRegex.containsMatchIn(domain)) {
            return Verdict(SEV_LOW, categoryFor(domain), "suspicious hostname")
        }
        return null
    }

    private fun categoryFor(domain: String): String {
        return when {
            domain.contains("crashlytic") ||
                domain.contains("sentry") ||
                domain.contains("bugsnag") -> CAT_CRASH
            domain.contains("telemetry") ||
                domain.contains("metric") ||
                domain.contains("analytic") -> CAT_ANALYTICS
            domain.contains("tracking") ||
                domain.contains("beacon") ||
                domain.contains("collector") ||
                domain.contains("datacollect") -> CAT_TRACKING
            else -> CAT_SUSPECTED
        }
    }

    // Called per DnsLog at insert time. Cheap, non-blocking: all work (asset read,
    // regex, DB upsert) is deferred to an IO coroutine.
    fun record(context: Context, dnsLog: DnsLog) {
        val domain = dnsLog.queryStr.dropLastWhile { it == '.' }.lowercase(Locale.ROOT)
        if (domain.isEmpty()) return

        val uid = dnsLog.uid
        val appName = dnsLog.appName
        val packageName = dnsLog.packageName
        val blockLists = dnsLog.blockLists
        val blocked = dnsLog.isBlocked
        val time = if (dnsLog.time > 0) dnsLog.time else System.currentTimeMillis()

        scope.launch {
            try {
                val verdict = classify(context, domain, blockLists) ?: return@launch
                val event =
                    SnoopEvent(
                        uid = uid,
                        appName = appName,
                        packageName = packageName,
                        domain = domain,
                        firstSeen = time,
                        lastSeen = time,
                        count = 1,
                        severity = verdict.severity,
                        category = verdict.category,
                        lastBlocked = blocked,
                        dismissed = false
                    )
                repository.upsert(event)
            } catch (e: Exception) {
                Logger.w(LOG_TAG_DNS, "$TAG: record err for $domain: ${e.message}")
            }
        }
    }
}
