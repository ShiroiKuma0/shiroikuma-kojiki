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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Fork (白い熊 考直) — Snooping panel feature.
class SnoopEventRepository(private val dao: SnoopEventDAO) {

    // dedup is read-modify-write (bump existing, else insert). Serialize it so two
    // concurrent positives for the same (uid, domain) can't both insert / both lose a count.
    private val mutex = Mutex()

    suspend fun upsert(event: SnoopEvent) {
        mutex.withLock {
            val updated =
                dao.bump(
                    event.uid,
                    event.domain,
                    event.lastSeen,
                    event.lastBlocked,
                    event.severity,
                    event.category,
                    event.appName,
                    event.packageName
                )
            if (updated == 0) {
                dao.insertIfAbsent(event)
            }
        }
    }

    suspend fun dismiss(uid: Int, domain: String) {
        dao.dismiss(uid, domain)
    }

    suspend fun setBlocked(uid: Int, domain: String, blocked: Boolean) {
        dao.setBlocked(uid, domain, blocked)
    }

    suspend fun clearAll() {
        dao.clearAll()
    }

    fun liveCount(): LiveData<Long> {
        return dao.liveCount()
    }

    suspend fun countNewBySeverity(minSeverity: Int, since: Long): Int {
        return dao.countNewBySeverity(minSeverity, since)
    }
}
