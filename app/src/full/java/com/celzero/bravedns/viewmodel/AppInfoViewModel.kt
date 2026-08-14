package com.celzero.bravedns.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.map
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.liveData
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.AppInfoDAO
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.ui.activity.AppListActivity
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class AppInfoViewModel(private val appInfoDAO: AppInfoDAO) : ViewModel() {

    private val filter: MutableLiveData<String> = MutableLiveData()
    private val category: MutableSet<String> = mutableSetOf()
    private var topLevelFilter = AppListActivity.TopLevelFilter.ALL
    private var firewallFilter = AppListActivity.FirewallFilter.ALL
    private var search: String = ""
    private val rethinkUid = android.os.Process.myUid()

    // Fork (白い熊 考直): the app-group (profile) filter. Group membership lives in a fork-private
    // prefs store keyed by package name — not in the AppInfo table — so it is applied as a post-query
    // filter rather than as SQL; that keeps every upstream DAO query untouched across rebases.
    // `active` is tracked apart from the package set on purpose: a group with no members must show an
    // empty list, whereas "no group selected" must show everything.
    private var groupFilterActive = false
    private var groupPackages: Set<String> = emptySet()

    // Fork (白い熊 考直): the "Non-app" top-level filter — the synthetic no_package_<uid> rows. Also a
    // post-query filter: isSystemApp does not identify them (it depends on whether the uid resolves
    // in AndroidUidConfig), and the package-name prefix does.
    private var nonAppOnly = false

    init {
        filter.value = ""
    }

    val appInfo = filter.switchMap { input: String -> getAppInfo(input) }

    private fun setFilterWithDebounce(searchString: String) {
        viewModelScope.launch {
            debounceFilter(searchString)
        }
    }

    private var debounceJob: Job? = null
    private fun debounceFilter(searchString: String) {
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(300.milliseconds) // 300ms debounce delay
            filter.value = searchString
        }
    }

    fun setFilter(filters: AppListActivity.Filters) {
        this.category.clear()
        this.category.addAll(filters.categoryFilters)

        this.firewallFilter = filters.firewallFilter
        this.topLevelFilter = filters.topLevelFilter

        this.groupFilterActive = filters.groupFilters.isNotEmpty()
        this.groupPackages = filters.groupPackages
        this.nonAppOnly = filters.topLevelFilter == AppListActivity.TopLevelFilter.NON_APP

        this.search = filters.searchString
        setFilterWithDebounce(filters.searchString)
    }

    private fun getAppInfo(searchString: String): LiveData<PagingData<AppInfo>> {
        val paged =
            when (topLevelFilter) {
                // get the app info based on the filter
                AppListActivity.TopLevelFilter.ALL -> {
                    allApps(searchString)
                }
                AppListActivity.TopLevelFilter.INSTALLED -> {
                    installedApps(searchString)
                }
                AppListActivity.TopLevelFilter.SYSTEM -> {
                    systemApps(searchString)
                }
                // Non-app rows can be either isSystemApp value, so they come out of the ALL query
                // and are narrowed by the post-filter below.
                AppListActivity.TopLevelFilter.NON_APP -> {
                    allApps(searchString)
                }
            }
        return applyRowFilters(paged)
    }

    /**
     * Fork (白い熊 考直): narrow a page stream to the selected app groups and/or the non-app rows.
     * Applied after [cachedIn] so the cached pages stay filter-agnostic — a new selection re-filters
     * the same cache instead of re-querying.
     */
    private fun applyRowFilters(
        source: LiveData<PagingData<AppInfo>>
    ): LiveData<PagingData<AppInfo>> {
        if (!groupFilterActive && !nonAppOnly) return source
        val pkgs = groupPackages
        val groups = groupFilterActive
        val nonApp = nonAppOnly
        return source.map { pagingData ->
            pagingData.filter { app ->
                (!groups || pkgs.contains(app.packageName)) &&
                    (!nonApp || Utilities.isNonApp(app.packageName))
            }
        }
    }

    /** Fork (白い熊 考直): the same row filters applied to a plain list — the bulk-rule path. */
    private fun applyRowFilters(apps: List<AppInfo>): List<AppInfo> {
        if (!groupFilterActive && !nonAppOnly) return apps
        return apps.filter { app ->
            (!groupFilterActive || groupPackages.contains(app.packageName)) &&
                (!nonAppOnly || Utilities.isNonApp(app.packageName))
        }
    }

    private fun getBypassProxyFilter(): Set<Int> {
        val filter = firewallFilter.getFilter()
        val bypassFilter = setOf(2, 7)
        if (filter == bypassFilter) {
            return setOf(1)
        }
        return setOf() // empty set (as query uses or condition)
    }

    private fun allApps(searchString: String): LiveData<PagingData<AppInfo>> {
        val includeProxyBypass = getBypassProxyFilter()
        return if (category.isEmpty()) {
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    appInfoDAO.getAppInfos(
                        "%$searchString%",
                        firewallFilter.getFilter(),
                        firewallFilter.getConnectionStatusFilter(),
                        includeProxyBypass
                    )
                }
                .liveData
                .cachedIn(viewModelScope)
        } else {
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    appInfoDAO.getAppInfos(
                        "%$searchString%",
                        category,
                        firewallFilter.getFilter(),
                        firewallFilter.getConnectionStatusFilter(),
                        includeProxyBypass
                    )
                }
                .liveData
                .cachedIn(viewModelScope)
        }
    }

    private fun installedApps(search: String): LiveData<PagingData<AppInfo>> {
        val includeProxyBypass = getBypassProxyFilter()
        return if (category.isEmpty()) {
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    appInfoDAO.getInstalledApps(
                        "%$search%",
                        firewallFilter.getFilter(),
                        firewallFilter.getConnectionStatusFilter(),
                        includeProxyBypass
                    )
                }
                .liveData
                .cachedIn(viewModelScope)
        } else {
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    appInfoDAO.getInstalledApps(
                        "%$search%",
                        category,
                        firewallFilter.getFilter(),
                        firewallFilter.getConnectionStatusFilter(),
                        includeProxyBypass
                    )
                }
                .liveData
                .cachedIn(viewModelScope)
        }
    }

    private fun systemApps(search: String): LiveData<PagingData<AppInfo>> {
        val includeProxyBypass = getBypassProxyFilter()
        return if (category.isEmpty()) {
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    appInfoDAO.getSystemApps(
                        "%$search%",
                        firewallFilter.getFilter(),
                        firewallFilter.getConnectionStatusFilter(),
                        includeProxyBypass
                    )
                }
                .liveData
                .cachedIn(viewModelScope)
        } else {
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    appInfoDAO.getSystemApps(
                        "%$search%",
                        category,
                        firewallFilter.getFilter(),
                        firewallFilter.getConnectionStatusFilter(),
                        includeProxyBypass
                    )
                }
                .liveData
                .cachedIn(viewModelScope)
        }
    }

    // apply the firewall rules to the filtered apps
    suspend fun updateUnmeteredStatus(blocked: Boolean) {
        val appList = getFilteredApps()
        appList
            .distinctBy { it.uid }
            .filter { it.uid != rethinkUid }
            .forEach {
                val connStatus = FirewallManager.connectionStatus(it.uid)
                val appStatus = getAppStateForWifi(blocked, connStatus)
                FirewallManager.updateFirewallStatus(it.uid, appStatus.fid, appStatus.cid)
            }
    }

    suspend fun updateMeteredStatus(blocked: Boolean) {
        val appList = getFilteredApps()
        appList
            .distinctBy { it.uid }
            .filter { it.uid != rethinkUid }
            .forEach {
                val connStatus = FirewallManager.connectionStatus(it.uid)
                val appStatus = getAppStateForMobileData(blocked, connStatus)
                FirewallManager.updateFirewallStatus(it.uid, appStatus.fid, appStatus.cid)
            }
    }

    suspend fun updateBypassStatus(bypass: Boolean) {
        val appList = getFilteredApps()
        // update the bypass status for the filtered apps
        // if the app is already in the bypass list, remove it
        // else add it to the bypass list
        val appStatus =
            if (bypass) {
                AppState(
                    FirewallManager.FirewallStatus.BYPASS_UNIVERSAL,
                    FirewallManager.ConnectionStatus.ALLOW
                )
            } else {
                AppState(
                    FirewallManager.FirewallStatus.NONE,
                    FirewallManager.ConnectionStatus.ALLOW
                )
            }
        appList
            .distinctBy { it.uid }
            .filter { it.uid != rethinkUid }
            .forEach { FirewallManager.updateFirewallStatus(it.uid, appStatus.fid, appStatus.cid) }
    }

    suspend fun updateBypassDnsFirewall(bypass: Boolean) {
        val appList = getFilteredApps()
        // update the bypass status for the filtered apps
        // if the app is already in the bypass list, remove it
        // else add it to the bypass list
        val appStatus =
            if (bypass) {
                AppState(
                    FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL,
                    FirewallManager.ConnectionStatus.ALLOW
                )
            } else {
                AppState(
                    FirewallManager.FirewallStatus.NONE,
                    FirewallManager.ConnectionStatus.ALLOW
                )
            }
        appList
            .distinctBy { it.uid }
            .filter { it.uid != rethinkUid }
            .forEach { FirewallManager.updateFirewallStatus(it.uid, appStatus.fid, appStatus.cid) }
    }

    suspend fun updateExcludeStatus(exclude: Boolean) {
        val appList = getFilteredApps()
        // update the exclude status for the filtered apps
        // if the app is already in the exclude list, remove it
        // else add it to the exclude list
        val appStatus =
            if (exclude) {
                AppState(
                    FirewallManager.FirewallStatus.EXCLUDE,
                    FirewallManager.ConnectionStatus.ALLOW
                )
            } else {
                AppState(
                    FirewallManager.FirewallStatus.NONE,
                    FirewallManager.ConnectionStatus.ALLOW
                )
            }
        appList
            .distinctBy { it.uid }
            .filter { it.uid != rethinkUid }
            .forEach { FirewallManager.updateFirewallStatus(it.uid, appStatus.fid, appStatus.cid) }
    }

    suspend fun updateLockdownStatus(lockdown: Boolean) {
        val appList = getFilteredApps()
        // update the lockdown status for the filtered apps
        // if the app is already in the lockdown list, remove it
        // else add it to the lockdown list
        val appStatus =
            if (lockdown) {
                AppState(
                    FirewallManager.FirewallStatus.ISOLATE,
                    FirewallManager.ConnectionStatus.ALLOW
                )
            } else {
                AppState(
                    FirewallManager.FirewallStatus.NONE,
                    FirewallManager.ConnectionStatus.ALLOW
                )
            }

        appList
            .distinctBy { it.uid }
            .filter { it.uid != rethinkUid }
            .forEach { FirewallManager.updateFirewallStatus(it.uid, appStatus.fid, appStatus.cid) }
    }

    private fun getAppStateForWifi(
        blocked: Boolean,
        connStatus: FirewallManager.ConnectionStatus
    ): AppState {
        if (blocked) {
            return when (connStatus) {
                FirewallManager.ConnectionStatus.ALLOW -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.UNMETERED
                    )
                }
                FirewallManager.ConnectionStatus.UNMETERED -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.UNMETERED
                    )
                }
                FirewallManager.ConnectionStatus.METERED -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.BOTH
                    )
                }
                FirewallManager.ConnectionStatus.BOTH -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.BOTH
                    )
                }
            }
        } else {
            return when (connStatus) {
                FirewallManager.ConnectionStatus.ALLOW -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.ALLOW
                    )
                }
                FirewallManager.ConnectionStatus.UNMETERED -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.ALLOW
                    )
                }
                FirewallManager.ConnectionStatus.METERED -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.METERED
                    )
                }
                FirewallManager.ConnectionStatus.BOTH -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.METERED
                    )
                }
            }
        }
    }

    data class AppState(
        val fid: FirewallManager.FirewallStatus,
        val cid: FirewallManager.ConnectionStatus
    )

    private fun getAppStateForMobileData(
        blocked: Boolean,
        connStatus: FirewallManager.ConnectionStatus
    ): AppState {
        if (blocked) {
            return when (connStatus) {
                FirewallManager.ConnectionStatus.ALLOW -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.METERED
                    )
                }
                FirewallManager.ConnectionStatus.UNMETERED -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.BOTH
                    )
                }
                FirewallManager.ConnectionStatus.METERED -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.METERED
                    )
                }
                FirewallManager.ConnectionStatus.BOTH -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.BOTH
                    )
                }
            }
        } else {
            return when (connStatus) {
                FirewallManager.ConnectionStatus.ALLOW -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.ALLOW
                    )
                }
                FirewallManager.ConnectionStatus.UNMETERED -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.UNMETERED
                    )
                }
                FirewallManager.ConnectionStatus.METERED -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.ALLOW
                    )
                }
                FirewallManager.ConnectionStatus.BOTH -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.UNMETERED
                    )
                }
            }
        }
    }

    private fun getFilteredApps(): List<AppInfo> {
        val appType =
            when (topLevelFilter) {
                AppListActivity.TopLevelFilter.ALL -> {
                    setOf(0, 1)
                }
                AppListActivity.TopLevelFilter.INSTALLED -> {
                    setOf(0)
                }
                AppListActivity.TopLevelFilter.SYSTEM -> {
                    setOf(1)
                }
                // Non-app rows carry either isSystemApp value; applyRowFilters below narrows them.
                AppListActivity.TopLevelFilter.NON_APP -> {
                    setOf(0, 1)
                }
            }
        // Fork (白い熊 考直): the bulk-rule toolbar acts on exactly what the list shows, so an active
        // group filter narrows the target set here too — that is what makes "block every app in 仕事"
        // one tap on the group pill plus one on the toolbar.
        val apps =
            if (category.isEmpty()) {
                appInfoDAO.getFilteredApps(
                    "%$search%",
                    firewallFilter.getFilter(),
                    appType,
                    firewallFilter.getConnectionStatusFilter()
                )
            } else {
                appInfoDAO.getFilteredApps(
                    "%$search%",
                    category,
                    firewallFilter.getFilter(),
                    appType,
                    firewallFilter.getConnectionStatusFilter()
                )
            }
        return applyRowFilters(apps)
    }
}
