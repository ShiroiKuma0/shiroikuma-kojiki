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
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.SnoopEventAdapter
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

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun initRecycler() {
        adapter = SnoopEventAdapter(this, repository)
        b.snoopRecycler.layoutManager = LinearLayoutManager(this)
        b.snoopRecycler.adapter = adapter
    }

    private fun initObservers() {
        viewModel.events.observe(this) { adapter.submitData(lifecycle, it) }
        repository.liveCount().observe(this) { count ->
            val empty = (count ?: 0L) <= 0L
            b.snoopEmpty.visibility = if (empty) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun initClickListeners() {
        val search = b.snoopSearch
        search.setOnQueryTextListener(
            object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    viewModel.setFilter(query ?: "")
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    viewModel.setFilter(newText ?: "")
                    return true
                }
            }
        )

        b.snoopGroupToggle.setOnClickListener {
            val next =
                if (viewModel.currentGroupBy() == SnoopViewModel.GroupBy.APP) {
                    SnoopViewModel.GroupBy.DOMAIN
                } else {
                    SnoopViewModel.GroupBy.APP
                }
            viewModel.setGroupBy(next)
            b.snoopGroupToggle.text =
                getString(
                    if (next == SnoopViewModel.GroupBy.APP) R.string.snoop_group_by_app
                    else R.string.snoop_group_by_domain
                )
        }

        b.snoopDeleteIcon.setOnClickListener { confirmClearAll() }
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
