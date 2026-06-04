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
import com.celzero.bravedns.database.SnoopEvent
import com.celzero.bravedns.database.SnoopEventDAO
import com.celzero.bravedns.util.Constants.Companion.LIVEDATA_PAGE_SIZE

// Fork (白い熊 考直) — Snooping panel feature.
class SnoopViewModel(private val dao: SnoopEventDAO) : ViewModel() {

    enum class GroupBy {
        APP,
        DOMAIN
    }

    private val filter: MutableLiveData<String> = MutableLiveData()
    private var groupBy = GroupBy.APP
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
        filter.value = ""
    }

    val events: LiveData<PagingData<SnoopEvent>> = filter.switchMap { fetch(it) }

    private fun fetch(search: String): LiveData<PagingData<SnoopEvent>> {
        return Pager(pagingConfig) {
                if (search.isEmpty()) {
                    if (groupBy == GroupBy.APP) dao.byApp() else dao.byDomain()
                } else {
                    val q = "%$search%"
                    if (groupBy == GroupBy.APP) dao.byAppFiltered(q) else dao.byDomainFiltered(q)
                }
            }
            .liveData
            .cachedIn(viewModelScope)
    }

    fun setFilter(search: String) {
        filter.value = if (search.isBlank()) "" else search
    }

    fun setGroupBy(group: GroupBy) {
        if (groupBy == group) return
        groupBy = group
        // re-trigger the switchMap with the current search term
        filter.value = filter.value
    }

    fun currentGroupBy(): GroupBy = groupBy
}
