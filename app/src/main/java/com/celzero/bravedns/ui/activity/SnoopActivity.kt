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
package com.celzero.bravedns.ui.activity

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.SnoopEventAdapter
import com.celzero.bravedns.customui.CustomUi
import com.celzero.bravedns.database.SnoopEvent
import com.celzero.bravedns.database.SnoopEventRepository
import com.celzero.bravedns.databinding.ActivitySnoopBinding
import com.celzero.bravedns.scheduler.SnoopAlertWorker
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.viewmodel.SnoopViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

// Fork (白い熊 考直) — Snooping panel: surfaces suspected telemetry/snooping DNS
// lookups (which app, when, how often) and lets the user block / trust / dismiss.
class SnoopActivity : AppCompatActivity(R.layout.activity_snoop) {

    private val b by viewBinding(ActivitySnoopBinding::bind)
    private val persistentState by inject<PersistentState>()
    private val repository by inject<SnoopEventRepository>()
    private val viewModel by viewModel<SnoopViewModel>()

    private lateinit var adapter: SnoopEventAdapter

    // true while we set the search box text to the app-filter indicator, so its own
    // text-changed callback doesn't turn that label into a free-text search.
    private var suppressSearchListener = false

    companion object {
        // filter-menu item-id ranges so one popup can host two independent radio groups
        private const val STATE_ID_BASE = 100

        private val SORTS =
            listOf(
                SnoopViewModel.Sort.NEWEST to R.string.snoop_sort_newest,
                SnoopViewModel.Sort.OLDEST to R.string.snoop_sort_oldest,
                SnoopViewModel.Sort.MOST_SEEN to R.string.snoop_sort_most_seen,
                SnoopViewModel.Sort.SEVERITY to R.string.snoop_sort_severity,
                SnoopViewModel.Sort.APP to R.string.snoop_sort_app,
                SnoopViewModel.Sort.DOMAIN to R.string.snoop_sort_domain
            )
        private val SEVERITIES =
            listOf(
                SnoopViewModel.SeverityFilter.ALL to R.string.snoop_filter_sev_all,
                SnoopViewModel.SeverityFilter.HIGH to R.string.snoop_filter_sev_high,
                SnoopViewModel.SeverityFilter.MEDIUM to R.string.snoop_filter_sev_medium,
                SnoopViewModel.SeverityFilter.LOW to R.string.snoop_filter_sev_low
            )
        private val STATES =
            listOf(
                SnoopViewModel.StateFilter.ALL to R.string.snoop_filter_state_all,
                SnoopViewModel.StateFilter.BLOCKED to R.string.snoop_filter_state_blocked,
                SnoopViewModel.StateFilter.ALLOWED to R.string.snoop_filter_state_allowed
            )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }

        initRecycler()
        initObservers()
        initClickListeners()

        // Optional daily snoop notification — self-schedule when the panel is first opened
        // so we don't have to touch app-startup wiring. Uses a KEEP policy (idempotent).
        SnoopAlertWorker.schedule(applicationContext)
    }

    override fun onResume() {
        super.onResume()
        // Re-apply any 白い熊 考直 UI changes made while away (pill size, status/menu fonts, icon, …):
        // visible rows otherwise keep the size they were bound with until they next rebind.
        if (::adapter.isInitialized) adapter.refresh()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun initRecycler() {
        adapter = SnoopEventAdapter(this, repository) { event -> filterByApp(event) }
        b.snoopRecycler.layoutManager = LinearLayoutManager(this)
        b.snoopRecycler.adapter = adapter
        // empty state reflects the *current* filter/search, not just the table total
        adapter.addLoadStateListener { states ->
            val empty = states.refresh is LoadState.NotLoading && adapter.itemCount == 0
            b.snoopEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        }
    }

    private fun initObservers() {
        viewModel.events.observe(this) { adapter.submitData(lifecycle, it) }
    }

    private fun initClickListeners() {
        b.snoopSort.text = getString(sortLabel(viewModel.currentSort()))

        b.snoopSearch.setOnQueryTextListener(
            object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    if (suppressSearchListener) return true
                    viewModel.clearAppFilter()
                    viewModel.setSearch(query ?: "")
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    if (suppressSearchListener) return true
                    // user edited/cleared the box → drop any app filter and search normally
                    viewModel.clearAppFilter()
                    viewModel.setSearch(newText ?: "")
                    return true
                }
            }
        )

        b.snoopSort.setOnClickListener { showSortMenu(it) }
        b.snoopFilterIcon.setOnClickListener { showFilterMenu(it) }
        b.snoopDeleteIcon.setOnClickListener { confirmClearAll() }
    }

    // Tap an app's icon → show every snoop entry for that app's uid, clearing any active
    // search and severity/state filters. The app's name fills the search box as the active
    // filter indicator; clearing the box (×) drops the app filter and shows everything again.
    private fun filterByApp(event: SnoopEvent) {
        viewModel.filterByApp(event.uid)
        val name = event.appName.ifEmpty { getString(R.string.network_log_app_name_unknown) }
        suppressSearchListener = true
        b.snoopSearch.setQuery(name, false)
        suppressSearchListener = false
    }

    private fun sortLabel(s: SnoopViewModel.Sort): Int =
        SORTS.first { it.first == s }.second

    private fun showSortMenu(anchor: View) {
        val cur = viewModel.currentSort()
        val items = SORTS.mapIndexed { i, (s, res) -> CustomUi.MenuItem(i, getString(res), s == cur) }
        CustomUi.showMenu(anchor, items) { id ->
            val s = SORTS[id].first
            viewModel.setSort(s)
            b.snoopSort.text = getString(sortLabel(s))
        }
    }

    private fun showFilterMenu(anchor: View) {
        val curSev = viewModel.currentSeverity()
        val curState = viewModel.currentState()
        val items = mutableListOf<CustomUi.MenuItem>()
        SEVERITIES.forEachIndexed { i, (f, res) ->
            items.add(CustomUi.MenuItem(i, getString(res), f == curSev))
        }
        STATES.forEachIndexed { i, (f, res) ->
            items.add(CustomUi.MenuItem(STATE_ID_BASE + i, getString(res), f == curState))
        }
        CustomUi.showMenu(anchor, items) { id ->
            if (id >= STATE_ID_BASE) viewModel.setState(STATES[id - STATE_ID_BASE].first)
            else viewModel.setSeverity(SEVERITIES[id].first)
        }
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle(R.string.snoop_clear_confirm_title)
            .setMessage(R.string.snoop_clear_confirm_msg)
            .setCancelable(true)
            .setPositiveButton(R.string.lbl_delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    repository.clearAll()
                    lifecycleScope.launch { adapter.refresh() }
                }
            }
            .setNegativeButton(R.string.lbl_cancel) { _, _ -> }
            .create()
            .show()
    }
}
