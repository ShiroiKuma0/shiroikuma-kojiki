/*
Copyright 2020 RethinkDNS and its authors

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
package com.celzero.bravedns.ui.bottomsheet

import android.content.res.Configuration
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.customui.CustomUi
import com.celzero.bravedns.customui.CustomUiConfig
import com.celzero.bravedns.customui.KojikiAppGroups
import com.celzero.bravedns.databinding.BottomSheetFirewallSortFilterBinding
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.activity.AppListActivity
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.useTransparentNoDimBackground
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class FirewallAppFilterBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetFirewallSortFilterBinding? = null

    private val b
        get() = checkNotNull(_binding)
        { "Binding accessed outside of view lifecycle" }

    private val persistentState by inject<PersistentState>()
    private val filters = AppListActivity.Filters()

    override fun getTheme(): Int =
        Themes.getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetFirewallSortFilterBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.useTransparentNoDimBackground()
        // Fork (白い熊 考直): flatten the Material grey/elevated panel to the configured background —
        // the visible accent border is drawn on the inset content box by applyKojikiTheme.
        dialog?.let { CustomUi.themeBottomSheet(it) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.let { window ->
            Themes.applyBottomSheetSystemBarAppearance(window, isDarkThemeOn(), persistentState.theme)
        }
        initView()
        initClickListeners()
    }

    private fun initView() {
        val f = AppListActivity.filters.value

        // Fork (白い熊 考直): Apply posts *this* Filters over the live one, so anything it does not
        // carry is silently reset. The sort order is owned by KojikiAppSort, so take it from there.
        this.filters.loadSort(requireContext())

        remakeParentFilterChipsUi()
        if (f == null) {
            applyParentFilter(AppListActivity.TopLevelFilter.ALL.id)
            remakeGroupChipsUi()
            return
        } else {
            this.filters.firewallFilter = f.firewallFilter
            this.filters.categoryFilters.addAll(f.categoryFilters)
            this.filters.setGroups(requireContext(), f.groupFilters)
        }

        applyParentFilter(f.topLevelFilter.id)
        setFilter(f.topLevelFilter, f.categoryFilters)
        remakeGroupChipsUi()
    }

    private fun initClickListeners() {
        b.fsApply.setOnClickListener {
            AppListActivity.filters.postValue(filters)
            this.dismiss()
        }

        b.fsClear.setOnClickListener {
            val new = AppListActivity.filters.value
            if (new == null) {
                this.dismiss()
                return@setOnClickListener
            }
            new.categoryFilters.clear()
            new.topLevelFilter = AppListActivity.TopLevelFilter.ALL
            // Fork (白い熊 考直): "clear" means every filter this sheet owns, groups included.
            new.setGroups(requireContext(), emptySet())
            AppListActivity.filters.postValue(new)
            this.dismiss()
        }

        // Fork (白い熊 考直): create / rename / delete the app groups themselves, without leaving the
        // filter sheet. A rename or delete invalidates selections that name the old group — both this
        // sheet's pending selection and the list's live one — and changes the pills on every row.
        b.fsGroupsManage.setOnClickListener {
            KojikiAppGroups.showManageDialog(requireContext()) {
                val alive = KojikiAppGroups.groups(requireContext()).toSet()
                filters.setGroups(requireContext(), filters.groupFilters.filter { alive.contains(it) })
                val live = AppListActivity.filters.value
                if (live != null && live.groupFilters.any { !alive.contains(it) }) {
                    live.setGroups(requireContext(), live.groupFilters.filter { alive.contains(it) })
                    AppListActivity.filters.postValue(live)
                }
                remakeGroupChipsUi()
                (activity as? AppListActivity)?.refreshGroupPills()
            }
        }
    }

    private fun setFilter(
        topLevelFilter: AppListActivity.TopLevelFilter,
        categories: MutableSet<String>
    ) {
        val topView: Chip = b.ffaParentChipGroup.findViewWithTag(topLevelFilter.id) ?: return
        b.ffaParentChipGroup.check(topView.id)
        colorUpChipIcon(topView)

        categories.forEach {
            val childCategory: Chip = b.ffaChipGroup.findViewWithTag(it) ?: return
            b.ffaChipGroup.check(childCategory.id)
        }
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun remakeParentFilterChipsUi() {
        b.ffaParentChipGroup.removeAllViews()

        val all =
            makeParentChip(AppListActivity.TopLevelFilter.ALL.id, getString(R.string.lbl_all), true)
        val allowed =
            makeParentChip(
                AppListActivity.TopLevelFilter.INSTALLED.id,
                getString(R.string.fapps_filter_parent_installed),
                false
            )
        val blocked =
            makeParentChip(
                AppListActivity.TopLevelFilter.SYSTEM.id,
                getString(R.string.fapps_filter_parent_system),
                false
            )
        // Fork (白い熊 考直): the synthetic no_package_<uid> rows — root, SYSTEM and any uid whose
        // traffic no package accounts for. They hide among ~400 apps otherwise.
        val nonApp =
            makeParentChip(
                AppListActivity.TopLevelFilter.NON_APP.id,
                getString(R.string.kojiki_filter_non_app),
                false
            )

        b.ffaParentChipGroup.addView(all)
        b.ffaParentChipGroup.addView(allowed)
        b.ffaParentChipGroup.addView(blocked)
        b.ffaParentChipGroup.addView(nonApp)
    }

    private fun makeParentChip(id: Int, label: String, checked: Boolean): Chip {
        val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, b.root, false) as Chip
        chip.tag = id
        chip.text = label
        chip.isChecked = checked

        chip.setOnCheckedChangeListener { button: CompoundButton, isSelected: Boolean ->
            if (isSelected) {
                applyParentFilter(button.tag)
                colorUpChipIcon(chip)
            } else {
                // no-op
                // no action needed for checkState: false
            }
        }

        return chip
    }

    private fun colorUpChipIcon(chip: Chip) {
        val colorFilter =
            PorterDuffColorFilter(
                ContextCompat.getColor(requireContext(), R.color.primaryText),
                PorterDuff.Mode.SRC_IN
            )
        chip.checkedIcon?.colorFilter = colorFilter
        chip.chipIcon?.colorFilter = colorFilter
    }

    private fun applyParentFilter(tag: Any) {
        when (tag) {
            AppListActivity.TopLevelFilter.ALL.id -> {
                filters.topLevelFilter = AppListActivity.TopLevelFilter.ALL
                io {
                    val categories = FirewallManager.getAllCategories()
                    uiCtx { remakeChildFilterChipsUi(categories) }
                }
            }
            AppListActivity.TopLevelFilter.INSTALLED.id -> {
                filters.topLevelFilter = AppListActivity.TopLevelFilter.INSTALLED
                io {
                    val categories = FirewallManager.getCategoriesForInstalledApps()
                    uiCtx { remakeChildFilterChipsUi(categories) }
                }
            }
            AppListActivity.TopLevelFilter.SYSTEM.id -> {
                filters.topLevelFilter = AppListActivity.TopLevelFilter.SYSTEM
                io {
                    val categories = FirewallManager.getCategoriesForSystemApps()
                    uiCtx { remakeChildFilterChipsUi(categories) }
                }
            }
            AppListActivity.TopLevelFilter.NON_APP.id -> {
                // Non-app rows all sit in one category, so a category sub-filter would only ever
                // narrow this to nothing or to itself — offer none.
                filters.topLevelFilter = AppListActivity.TopLevelFilter.NON_APP
                filters.categoryFilters.clear()
                remakeChildFilterChipsUi(emptyList())
            }
        }
    }

    private fun remakeChildFilterChipsUi(categories: List<String>) {
        b.ffaChipGroup.removeAllViews()
        for (c in categories) {
            if (filters.categoryFilters.contains(c)) {
                // if the category is already selected, check the chip
                b.ffaChipGroup.addView(makeChildChip(c, true))
            } else {
                b.ffaChipGroup.addView(makeChildChip(c, false))
            }
        }
        // Fork (白い熊 考直): these chips arrive asynchronously (after the category query), so the
        // theme pass has to run again here — the one from onViewCreated saw an empty group.
        applyKojikiTheme()
    }

    private fun makeChildChip(title: String, checked: Boolean): Chip {
        val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, b.root, false) as Chip
        chip.text = title
        chip.tag = title
        chip.isChecked = checked
        if (checked) colorUpChipIcon(chip)

        chip.setOnCheckedChangeListener { compoundButton: CompoundButton, isSelected: Boolean ->
            applyChildFilter(compoundButton.tag, isSelected)
            colorUpChipIcon(chip)
        }
        return chip
    }

    // ---- Fork (白い熊 考直): app groups (profiles) ------------------------------------------------

    /** Rebuild the group chips from the stored group list, ticking the pending selection. */
    private fun remakeGroupChipsUi() {
        b.ffaGroupChipGroup.removeAllViews()
        val all = KojikiAppGroups.groups(requireContext())
        b.fsGroupsEmpty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        for (name in all) {
            b.ffaGroupChipGroup.addView(makeGroupChip(name, filters.groupFilters.contains(name)))
        }
        applyKojikiTheme()
    }

    private fun makeGroupChip(name: String, checked: Boolean): Chip {
        val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, b.root, false) as Chip
        chip.text = name
        chip.tag = name
        chip.isChecked = checked
        if (checked) colorUpChipIcon(chip)

        chip.setOnCheckedChangeListener { button: CompoundButton, isSelected: Boolean ->
            val selected = filters.groupFilters.toMutableSet()
            if (isSelected) selected.add(button.tag.toString()) else selected.remove(button.tag.toString())
            filters.setGroups(requireContext(), selected)
            colorUpChipIcon(chip)
        }
        return chip
    }

    /**
     * Fork (白い熊 考直): give the sheet the 白い熊 考直 look — the configured black fill and accent
     * border on the inset content box, and the shared Custom-theme pass over the whole tree (chips,
     * buttons, text, fonts). Run after any chip rebuild, since the pass is a one-shot walk and newly
     * inflated chips would otherwise keep the stock bottom-sheet colours. No-op off the Custom theme.
     */
    private fun applyKojikiTheme() {
        if (!CustomUi.customThemeActive) return
        val ctx = context ?: return
        val cfg = CustomUiConfig(ctx)
        val d = resources.displayMetrics.density
        CustomUi.applyToDialogTree(b.root)
        b.fsContentBox.background = GradientDrawable().apply {
            cornerRadius = 16 * d
            setColor(cfg.backgroundColor)
            setStroke((2f * d).toInt(), cfg.accentColor)
        }
        // Section headings + the manage action lead in the accent colour; the tree pass above paints
        // every TextView the body colour, so these have to be re-set after it, not before.
        b.fsFilterHeading.setTextColor(cfg.accentColor)
        b.fsCategoriesHeading.setTextColor(cfg.accentColor)
        b.fsGroupsHeading.setTextColor(cfg.accentColor)
        b.fsGroupsManage.setTextColor(cfg.accentColor)
    }

    private fun applyChildFilter(tag: Any, show: Boolean) {
        if (show) {
            filters.categoryFilters.add(tag.toString())
        } else {
            filters.categoryFilters.remove(tag.toString())
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
