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
package com.celzero.bravedns.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.liveData
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.celzero.bravedns.database.SnoopEvent
import com.celzero.bravedns.database.SnoopEventDAO
import com.celzero.bravedns.service.SnoopClassifier
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Constants.Companion.LIVEDATA_PAGE_SIZE
import com.celzero.bravedns.util.Constants.Companion.MAX_LOGS

// Fork (白い熊 考直) — Snooping panel feature.
class SnoopViewModel(private val dao: SnoopEventDAO) : ViewModel() {

    enum class Sort {
        NEWEST,
        OLDEST,
        MOST_SEEN,
        SEVERITY,
        APP,
        DOMAIN
    }

    enum class SeverityFilter {
        ALL,
        HIGH,
        MEDIUM,
        LOW
    }

    enum class StateFilter {
        ALL,
        BLOCKED,
        ALLOWED
    }

    private val trigger: MutableLiveData<Unit> = MutableLiveData()
    private var sort = Sort.NEWEST
    private var severity = SeverityFilter.ALL
    private var state = StateFilter.ALL
    private var search = ""
    // tap-an-app-icon filter: restrict the list to a single app's uid (INVALID_UID = no filter)
    private var appUid = INVALID_UID

    private val pagingConfig =
        PagingConfig(
            enablePlaceholders = true,
            prefetchDistance = 3,
            initialLoadSize = LIVEDATA_PAGE_SIZE * 2,
            maxSize = LIVEDATA_PAGE_SIZE * 3,
            pageSize = LIVEDATA_PAGE_SIZE * 2,
            jumpThreshold = 5
        )

    init {
        trigger.value = Unit
    }

    val events: LiveData<PagingData<SnoopEvent>> = trigger.switchMap { fetch() }

    private fun fetch(): LiveData<PagingData<SnoopEvent>> {
        return Pager(pagingConfig) { dao.rawEvents(buildQuery()) }
            .liveData
            .cachedIn(viewModelScope)
    }

    // Builds "SELECT * FROM SnoopEvent WHERE … ORDER BY … LIMIT …". The search term is a bound arg;
    // every other fragment is chosen from a fixed allowlist (enum-driven), so this is injection-safe.
    private fun buildQuery(): SupportSQLiteQuery {
        val where = StringBuilder("dismissed = 0")
        val args = mutableListOf<Any>()

        when (severity) {
            SeverityFilter.HIGH -> { where.append(" AND severity = ?"); args.add(SnoopClassifier.SEV_HIGH) }
            SeverityFilter.MEDIUM -> { where.append(" AND severity = ?"); args.add(SnoopClassifier.SEV_MEDIUM) }
            SeverityFilter.LOW -> { where.append(" AND severity = ?"); args.add(SnoopClassifier.SEV_LOW) }
            SeverityFilter.ALL -> {}
        }

        when (state) {
            StateFilter.BLOCKED -> where.append(" AND lastBlocked = 1")
            StateFilter.ALLOWED -> where.append(" AND lastBlocked = 0")
            StateFilter.ALL -> {}
        }

        if (search.isNotBlank()) {
            where.append(" AND (domain LIKE ? OR appName LIKE ?)")
            val like = "%${search.trim()}%"
            args.add(like)
            args.add(like)
        }

        if (appUid != INVALID_UID) {
            where.append(" AND uid = ?")
            args.add(appUid)
        }

        val order =
            when (sort) {
                Sort.NEWEST -> "lastSeen DESC"
                Sort.OLDEST -> "lastSeen ASC"
                Sort.MOST_SEEN -> "count DESC, lastSeen DESC"
                Sort.SEVERITY -> "severity DESC, lastSeen DESC"
                Sort.APP -> "appName COLLATE NOCASE ASC, lastSeen DESC"
                Sort.DOMAIN -> "domain COLLATE NOCASE ASC, lastSeen DESC"
            }

        val sql = "SELECT * FROM SnoopEvent WHERE $where ORDER BY $order LIMIT $MAX_LOGS"
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    fun setSort(s: Sort) {
        if (sort == s) return
        sort = s
        trigger.value = Unit
    }

    fun setSeverity(f: SeverityFilter) {
        if (severity == f) return
        severity = f
        trigger.value = Unit
    }

    fun setState(f: StateFilter) {
        if (state == f) return
        state = f
        trigger.value = Unit
    }

    fun setSearch(q: String) {
        val n = if (q.isBlank()) "" else q
        if (n == search) return
        search = n
        trigger.value = Unit
    }

    // Tap an app's icon: drop any active search + severity/state filters and show every
    // (non-dismissed) snoop entry for this one app's uid. Always re-triggers.
    fun filterByApp(uid: Int) {
        appUid = uid
        severity = SeverityFilter.ALL
        state = StateFilter.ALL
        search = ""
        trigger.value = Unit
    }

    fun clearAppFilter() {
        if (appUid == INVALID_UID) return
        appUid = INVALID_UID
        trigger.value = Unit
    }

    fun currentSort() = sort

    fun currentSeverity() = severity

    fun currentState() = state
}
