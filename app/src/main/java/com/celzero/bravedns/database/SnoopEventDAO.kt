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

import androidx.lifecycle.LiveData
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

// Fork (白い熊 考直) — Snooping panel feature.
@Dao
interface SnoopEventDAO {

    // returns rowid (>=0) when inserted, -1 when a row for (uid, domain) already exists
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertIfAbsent(event: SnoopEvent): Long

    // upsert half: bump an existing (uid, domain) row. severity is monotonic (never lowered).
    // dismissed is deliberately left untouched so a dismissed row stays hidden.
    @Query(
        """
        UPDATE SnoopEvent
        SET count = count + 1,
            lastSeen = :ts,
            lastBlocked = :blocked,
            severity = CASE WHEN :severity > severity THEN :severity ELSE severity END,
            category = :category,
            appName = :appName,
            packageName = :packageName
        WHERE uid = :uid AND domain = :domain
        """
    )
    fun bump(
        uid: Int,
        domain: String,
        ts: Long,
        blocked: Boolean,
        severity: Int,
        category: String,
        appName: String,
        packageName: String
    ): Int

    // ---- panel queries (PagingSource), dismissed rows hidden ----

    // grouped by app: rows cluster by app name, then strongest/most-recent first
    @Query(
        "SELECT * FROM SnoopEvent WHERE dismissed = 0 ORDER BY appName COLLATE NOCASE ASC, severity DESC, lastSeen DESC"
    )
    fun byApp(): PagingSource<Int, SnoopEvent>

    @Query(
        "SELECT * FROM SnoopEvent WHERE dismissed = 0 AND (domain LIKE :q OR appName LIKE :q) ORDER BY appName COLLATE NOCASE ASC, severity DESC, lastSeen DESC"
    )
    fun byAppFiltered(q: String): PagingSource<Int, SnoopEvent>

    // grouped by domain: most-recent / strongest first across all apps
    @Query(
        "SELECT * FROM SnoopEvent WHERE dismissed = 0 ORDER BY severity DESC, lastSeen DESC"
    )
    fun byDomain(): PagingSource<Int, SnoopEvent>

    @Query(
        "SELECT * FROM SnoopEvent WHERE dismissed = 0 AND (domain LIKE :q OR appName LIKE :q) ORDER BY severity DESC, lastSeen DESC"
    )
    fun byDomainFiltered(q: String): PagingSource<Int, SnoopEvent>

    // ---- mutations from the panel ----

    @Query("UPDATE SnoopEvent SET dismissed = 1 WHERE uid = :uid AND domain = :domain")
    fun dismiss(uid: Int, domain: String)

    @Query("UPDATE SnoopEvent SET lastBlocked = :blocked WHERE uid = :uid AND domain = :domain")
    fun setBlocked(uid: Int, domain: String, blocked: Boolean)

    @Query("DELETE FROM SnoopEvent") fun clearAll()

    // ---- counters ----

    @Query("SELECT COUNT(*) FROM SnoopEvent WHERE dismissed = 0") fun liveCount(): LiveData<Long>

    // for the notification worker: new high-severity hits since a timestamp
    @Query(
        "SELECT COUNT(*) FROM SnoopEvent WHERE dismissed = 0 AND severity >= :minSeverity AND lastSeen > :since"
    )
    fun countNewBySeverity(minSeverity: Int, since: Long): Int
}
