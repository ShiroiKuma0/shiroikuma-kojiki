/*
Copyright 2020 RethinkDNS developers

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.celzero.bravedns.database

import android.database.Cursor
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.celzero.bravedns.data.DataUsage

@Dao
interface AppInfoDAO {

    @Update fun update(appInfo: AppInfo): Int

    @Query(
        "update AppInfo set firewallStatus = :firewallStatus, connectionStatus = :connectionStatus, modifiedTs = :modifiedTs where uid = :uid"
    )
    fun updateFirewallStatusByUid(uid: Int, firewallStatus: Int, connectionStatus: Int, modifiedTs: Long)

    @Query("update AppInfo set tempAllowEnabled = :enabled, tempAllowExpiryTime = :expiryTime, modifiedTs = :modifiedTs where uid = :uid")
    fun updateTempAllowByUid(uid: Int, enabled: Boolean, expiryTime: Long, modifiedTs: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insert(appInfo: AppInfo): Long

    @Query("update AppInfo set uid = :newUid, tombstoneTs = 0, modifiedTs = :modifiedTs where uid = :oldUid and packageName = :pkg")
    fun updateUid(oldUid: Int, pkg: String, newUid: Int, modifiedTs: Long): Int

    @Query("select * from AppInfo where uid = :uid and packageName = :pkg")
    fun isUidPkgExist(uid: Int, pkg: String): AppInfo?

    @Query("select * from AppInfo where uid = :uid limit 1")
    suspend fun getAppInfoByUid(uid: Int): AppInfo?

    @Delete fun delete(appInfo: AppInfo)

    @Query("delete from AppInfo where packageName in (:packageNames)")
    fun deleteByPackageName(packageNames: List<String>)

    @Query("delete from AppInfo where uid = :uid and packageName = :packageName")
    fun deletePackage(uid: Int, packageName: String)

    @Query("update AppInfo set uid = :newUid, tombstoneTs = :tombstoneTs, modifiedTs = :modifiedTs where uid = :uid and packageName = :packageName")
    fun tombstoneApp(newUid: Int, uid: Int, packageName: String, tombstoneTs: Long, modifiedTs: Long)

    @Query("update AppInfo set uid = :newUid, tombstoneTs = :tombstoneTs, modifiedTs = :modifiedTs where uid = :oldUid")
    fun tombstoneApp(oldUid: Int, newUid: Int, tombstoneTs: Long, modifiedTs: Long)

    // clear stale values before updating
    @Transaction
    fun tombstoneAppWithPkg(newUid: Int, uid: Int, packageName: String, tombstoneTs: Long, modifiedTs: Long) {
        deletePackage(newUid, packageName)
        tombstoneApp(newUid, uid, packageName, tombstoneTs, modifiedTs)
    }

    // clear stale values before updating
    @Transaction
    fun tombstoneAppByUid(oldUid: Int, newUid: Int, tombstoneTs: Long, modifiedTs: Long) {
        deleteByUid(newUid)
        tombstoneApp(oldUid, newUid, tombstoneTs, modifiedTs)
    }

    @Query("select * from AppInfo order by appCategory, uid") fun getAllAppDetails(): List<AppInfo>

    @Query(
        "select * from AppInfo where isSystemApp = 1 and (appName like :search or uid like :search or packageName like :search) and (firewallStatus in (:firewall) or isProxyExcluded in (:isProxyExcluded)) and connectionStatus in (:connectionStatus) order by lower(appName)"
    )
    fun getSystemApps(
        search: String,
        firewall: Set<Int>,
        connectionStatus: Set<Int>,
        isProxyExcluded: Set<Int>
    ): PagingSource<Int, AppInfo>

    @Query(
        "select * from AppInfo where isSystemApp = 1 and (appName like :search or uid like :search or packageName like :search) and appCategory in (:filter) and (firewallStatus in (:firewall)  or isProxyExcluded in (:isProxyExcluded)) and connectionStatus in (:connectionStatus) order by lower(appName)"
    )
    fun getSystemApps(
        search: String,
        filter: Set<String>,
        firewall: Set<Int>,
        connectionStatus: Set<Int>,
        isProxyExcluded: Set<Int>
    ): PagingSource<Int, AppInfo>

    @Query(
        "select * from AppInfo where isSystemApp = 0 and (appName like :search or uid like :search or packageName like :search) and (firewallStatus in (:firewall) or isProxyExcluded in (:isProxyExcluded)) and connectionStatus in (:connectionStatus) order by lower(appName)"
    )
    fun getInstalledApps(
        search: String,
        firewall: Set<Int>,
        connectionStatus: Set<Int>,
        isProxyExcluded: Set<Int>
    ): PagingSource<Int, AppInfo>

    @Query(
        "select * from AppInfo where isSystemApp = 0 and (appName like :search or uid like :search or packageName like :search) and appCategory in (:filter) and (firewallStatus in (:firewall) or isProxyExcluded in (:isProxyExcluded)) and connectionStatus in (:connectionStatus) order by lower(appName)"
    )
    fun getInstalledApps(
        search: String,
        filter: Set<String>,
        firewall: Set<Int>,
        connectionStatus: Set<Int>,
        isProxyExcluded: Set<Int>
    ): PagingSource<Int, AppInfo>

    @Query(
        "select * from AppInfo where (appName like :search or uid like :search or packageName like :search) and (firewallStatus in (:firewall) or isProxyExcluded in (:isProxyExcluded)) and connectionStatus in (:connectionStatus) order by lower(appName)"
    )
    fun getAppInfos(
        search: String,
        firewall: Set<Int>,
        connectionStatus: Set<Int>,
        isProxyExcluded: Set<Int>
    ): PagingSource<Int, AppInfo>

    @Query(
        "select * from AppInfo where (appName like :search or uid like :search or packageName like :search) and appCategory in (:filter)  and (firewallStatus in (:firewall) or isProxyExcluded in (:isProxyExcluded)) and connectionStatus in (:connectionStatus) order by lower(appName)"
    )
    fun getAppInfos(
        search: String,
        filter: Set<String>,
        firewall: Set<Int>,
        connectionStatus: Set<Int>,
        isProxyExcluded: Set<Int>
    ): PagingSource<Int, AppInfo>

    @Query(
        "select * from AppInfo where (appName like :search or uid like :search or packageName like :search) and appCategory in (:cat) and isSystemApp in (:appType) and firewallStatus in (:firewall) and connectionStatus in (:connectionStatus) order by lower(appName)"
    )
    fun getFilteredApps(
        search: String,
        cat: Set<String>,
        firewall: Set<Int>,
        appType: Set<Int>,
        connectionStatus: Set<Int>
    ): List<AppInfo>

    @Query(
        "select * from AppInfo where (appName like :search or uid like :search or packageName like :search) and isSystemApp in (:appType) and firewallStatus in (:firewall) and connectionStatus in (:connectionStatus) order by lower(appName)"
    )
    fun getFilteredApps(
        search: String,
        firewall: Set<Int>,
        appType: Set<Int>,
        connectionStatus: Set<Int>
    ): List<AppInfo>

    /**
     * Fork (白い熊 考直): the apps view's single paged query, with a **selectable sort order**.
     *
     * Upstream splits the paged list into six methods (all / installed / system × with and without a
     * category filter), each hard-ordered by `lower(appName)`. Sorting by anything else has to happen
     * in SQL — a page stream cannot be re-sorted in Kotlin, since each page is fetched independently —
     * so this collapses all six into one query whose ORDER BY is driven by two bound parameters:
     *
     * - [sortKey]: 0 = app name · 1 = package id · 2 = uid · 3 = data used (up + down)
     * - [descending]: 0 = ascending, 1 = descending
     *
     * The CASE terms are the standard SQLite idiom for a parameterised ORDER BY: exactly one term is
     * non-NULL for a given (sortKey, descending) pair, and the rest evaluate to NULL for every row —
     * equal, therefore no-ops. `lower(appName)` closes the list as the tie-breaker, which is what
     * keeps rows sharing a uid in a stable, readable order.
     *
     * [noCategory] collapses upstream's with/without-category split: pass 1 to ignore [cat] entirely.
     * (An empty `in ()` is valid SQLite and always false — upstream already relies on that for
     * `isProxyExcluded`.)
     *
     * **On rebase:** the WHERE below mirrors upstream's `getAppInfos` / `getInstalledApps` /
     * `getSystemApps` predicate. If upstream changes theirs, change this one to match — nothing else
     * reads those methods now.
     */
    @Query(
        "select * from AppInfo where (appName like :search or uid like :search or packageName like :search) " +
            "and (:noCategory = 1 or appCategory in (:cat)) " +
            "and isSystemApp in (:appType) " +
            "and (firewallStatus in (:firewall) or isProxyExcluded in (:isProxyExcluded)) " +
            "and connectionStatus in (:connectionStatus) " +
            "order by " +
            "case when :sortKey = 0 and :descending = 0 then lower(appName) end asc, " +
            "case when :sortKey = 0 and :descending = 1 then lower(appName) end desc, " +
            "case when :sortKey = 1 and :descending = 0 then lower(packageName) end asc, " +
            "case when :sortKey = 1 and :descending = 1 then lower(packageName) end desc, " +
            "case when :sortKey = 2 and :descending = 0 then uid end asc, " +
            "case when :sortKey = 2 and :descending = 1 then uid end desc, " +
            "case when :sortKey = 3 and :descending = 0 then uploadBytes + downloadBytes end asc, " +
            "case when :sortKey = 3 and :descending = 1 then uploadBytes + downloadBytes end desc, " +
            "lower(appName)"
    )
    fun getSortedApps(
        search: String,
        cat: Set<String>,
        noCategory: Int,
        appType: Set<Int>,
        firewall: Set<Int>,
        connectionStatus: Set<Int>,
        isProxyExcluded: Set<Int>,
        sortKey: Int,
        descending: Int
    ): PagingSource<Int, AppInfo>

    @Query(
        "update AppInfo set firewallStatus = :firewall, connectionStatus = :connectionStatus where :clause"
    )
    fun cpUpdate(firewall: Int, connectionStatus: Int, clause: String): Int

    @Query("select * from AppInfo order by appCategory, uid") fun getAllAppDetailsCursor(): Cursor

    @Query("delete from AppInfo where uid = :uid") fun deleteByUid(uid: Int): Int

    @Query("delete from AppInfo") fun deleteAll()

    @Query(
        "select uid as uid, downloadBytes as downloadBytes, uploadBytes as uploadBytes from AppInfo where uid = :uid"
    )
    fun getDataUsageByUid(uid: Int): DataUsage?

    @Query(
        "update AppInfo set  uploadBytes = :uploadBytes, downloadBytes = :downloadBytes where uid = :uid"
    )
    fun updateDataUsageByUid(uid: Int, uploadBytes: Long, downloadBytes: Long)

    @Query("update AppInfo set isProxyExcluded = :isProxyExcluded, modifiedTs = :modifiedTs where uid = :uid")
    fun updateProxyExcluded(uid: Int, isProxyExcluded: Boolean, modifiedTs: Long)

    @Query("select uid from AppInfo where packageName = :packageName")
    fun getAppInfoUidForPackageName(packageName: String): Int

    @Query("update AppInfo set firewallStatus = 7, connectionStatus = 3, isProxyExcluded = 1 where packageName = 'com.celzero.bravedns'")
    fun exemptRethinkApp()

    @Query("select * from AppInfo where tempAllowEnabled = 1 and tempAllowExpiryTime > 0")
    suspend fun getTempAllowedApps(): List<AppInfo>

    @Query("select * from AppInfo where tempAllowEnabled = 1 and tempAllowExpiryTime > :now ORDER BY tempAllowExpiryTime DESC")
    fun getTempAllowedAppsPaged(now: Long): PagingSource<Int, AppInfo>

    @Query("update AppInfo set tempAllowEnabled = 0, tempAllowExpiryTime = 0, modifiedTs = :modifiedTs where uid = :uid")
    fun clearTempAllowByUid(uid: Int, modifiedTs: Long)

    @Query(
        "update AppInfo set tempAllowEnabled = 0, tempAllowExpiryTime = 0, modifiedTs = :modifiedTs " +
            "where uid = :uid and tempAllowEnabled = 1 and tempAllowExpiryTime = :expectedExpiry"
    )
    fun clearTempAllowByUidIfExpiry(uid: Int, expectedExpiry: Long, modifiedTs: Long): Int

    @Query("select MIN(tempAllowExpiryTime) from AppInfo where tempAllowEnabled = 1 and tempAllowExpiryTime > :now")
    fun getNearestTempAllowExpiry(now: Long): Long?

    @Query(
        "update AppInfo set tempAllowEnabled = 0, tempAllowExpiryTime = 0, modifiedTs = :modifiedTs " +
            "where tempAllowEnabled = 1 and tempAllowExpiryTime > 0 and tempAllowExpiryTime <= :now"
    )
    fun clearAllExpiredTempAllows(now: Long, modifiedTs: Long): Int
}
