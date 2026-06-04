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
package com.celzero.bravedns.database

import androidx.room.Entity
import androidx.room.Index

// Fork (白い熊 考直) — Snooping panel feature.
// A persistent, deduped record of a suspected telemetry/snooping DNS lookup.
// One row per (uid, domain): it outlives DnsLogs pruning, which is the whole
// retention point — the DnsLog table is a separate, frequently-pruned DB.
@Entity(
    tableName = "SnoopEvent",
    primaryKeys = ["uid", "domain"],
    indices =
        [
            Index(value = arrayOf("severity"), unique = false),
            Index(value = arrayOf("lastSeen"), unique = false),
            Index(value = arrayOf("dismissed"), unique = false),
        ]
)
class SnoopEvent {
    var uid: Int = 0
    var appName: String = ""
    var packageName: String = ""
    var domain: String = ""
    var firstSeen: Long = 0L
    var lastSeen: Long = 0L
    var count: Int = 0
    // severity: see SnoopClassifier.SEV_LOW / SEV_MEDIUM / SEV_HIGH
    var severity: Int = 0
    // a coarse category for the matched signal (tracker / telemetry / suspected …)
    var category: String = ""
    // whether the last observed resolution for this domain was blocked
    var lastBlocked: Boolean = false
    // user dismissed this row; stays hidden in the panel and persists
    var dismissed: Boolean = false

    override fun equals(other: Any?): Boolean {
        if (other !is SnoopEvent) return false
        if (uid != other.uid) return false
        if (domain != other.domain) return false
        return true
    }

    override fun hashCode(): Int {
        var result = uid.hashCode()
        result = 31 * result + domain.hashCode()
        return result
    }

    constructor()

    constructor(
        uid: Int,
        appName: String,
        packageName: String,
        domain: String,
        firstSeen: Long,
        lastSeen: Long,
        count: Int,
        severity: Int,
        category: String,
        lastBlocked: Boolean,
        dismissed: Boolean
    ) {
        this.uid = uid
        this.appName = appName
        this.packageName = packageName
        this.domain = domain
        this.firstSeen = firstSeen
        this.lastSeen = lastSeen
        this.count = count
        this.severity = severity
        this.category = category
        this.lastBlocked = lastBlocked
        this.dismissed = dismissed
    }
}
