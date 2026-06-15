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
package com.celzero.bravedns.adapter

import Logger
import Logger.LOG_TAG_UI
import android.content.Intent
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.customui.CustomUi
import com.celzero.bravedns.customui.CustomUiConfig
import com.celzero.bravedns.customui.SnoopTagUi
import com.celzero.bravedns.database.SnoopEvent
import com.celzero.bravedns.database.SnoopEventRepository
import com.celzero.bravedns.databinding.ListItemSnoopBinding
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.SnoopClassifier
import com.celzero.bravedns.service.SnoopTagStore
import com.celzero.bravedns.ui.activity.AppInfoActivity
import com.celzero.bravedns.ui.bottomsheet.CustomDomainRulesBtmSheet
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getDefaultIcon
import com.celzero.bravedns.util.Utilities.getIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Fork (白い熊 考直) — Snooping panel feature.
class SnoopEventAdapter(
    private val activity: FragmentActivity,
    private val repository: SnoopEventRepository,
    // tap an app's icon → host filters the list to this app (search box + viewmodel live there)
    private val onFilterApp: (SnoopEvent) -> Unit
) : PagingDataAdapter<SnoopEvent, SnoopEventAdapter.SnoopViewHolder>(DIFF_CALLBACK) {

    companion object {
        private const val TAG = "SnoopAdapter"

        private const val COLOR_HIGH = 0xFFFF1744.toInt()
        private const val COLOR_MEDIUM = 0xFFFFA000.toInt()
        private const val COLOR_LOW = 0xFF9E9E9E.toInt()
        private const val COLOR_BLOCKED = 0xFF2B8E18.toInt()
        private const val COLOR_ALLOWED = 0xFFFFA000.toInt()

        // severity-pill defaults (off Custom theme): matches the old bg_snoop_badge
        private const val DEFAULT_PILL_RADIUS_DP = 6
        private const val PILL_PAD_H_DP = 6
        private const val PILL_PAD_V_DP = 2

        // popup menu item ids
        private const val MI_BLOCK_APP = 1
        private const val MI_BLOCK_ALL = 2
        private const val MI_TRUST_APP = 3
        private const val MI_ADVANCED = 4
        private const val MI_DISMISS = 5
        private const val MI_OPEN_APP = 6
        private const val MI_TAGS = 7

        // tag-chip long-press menu item ids
        private const val MI_TAG_EDIT = 1
        private const val MI_TAG_REMOVE_HERE = 2

        private const val TAG_CHIP_RADIUS_DP = 8

        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<SnoopEvent>() {
                override fun areItemsTheSame(prev: SnoopEvent, curr: SnoopEvent) =
                    prev.uid == curr.uid && prev.domain == curr.domain

                override fun areContentsTheSame(prev: SnoopEvent, curr: SnoopEvent) =
                    prev.lastSeen == curr.lastSeen &&
                        prev.count == curr.count &&
                        prev.severity == curr.severity &&
                        prev.lastBlocked == curr.lastBlocked &&
                        prev.dismissed == curr.dismissed
            }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SnoopViewHolder {
        val binding =
            ListItemSnoopBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SnoopViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SnoopViewHolder, position: Int) {
        val event = getItem(position) ?: return
        holder.bind(event)
    }

    inner class SnoopViewHolder(private val b: ListItemSnoopBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(event: SnoopEvent) {
            b.snoopDomain.text = event.domain
            b.snoopAppName.text =
                event.appName.ifEmpty {
                    activity.getString(R.string.network_log_app_name_unknown)
                }
            b.snoopMeta.text = metaText(event)
            displayIcon(event)
            displaySeverity(event)
            displayState(event)
            displayTags(event)
            // 白い熊 考直 UI: style the row at bind time so every row is consistent (the global
            // runtime tree-walk races with async paging — see CustomUi.applySnoopRow).
            CustomUi.applySnoopRow(activity, b)
            // Tap the row → action menu. Tap the app icon → filter the list to this app;
            // long-press the icon → open the app's page. Both icon gestures fall back to the
            // menu / no-op when there's no real app for this uid.
            b.snoopRow.setOnClickListener { showActions(it, event) }
            b.snoopAppIcon.setOnClickListener {
                if (canOpenApp(event.uid)) onFilterApp(event) else showActions(b.snoopRow, event)
            }
            b.snoopAppIcon.setOnLongClickListener {
                if (canOpenApp(event.uid)) {
                    openApp(event.uid)
                    true
                } else {
                    false
                }
            }
        }

        private fun metaText(event: SnoopEvent): String {
            val rel =
                DateUtils.getRelativeTimeSpanString(
                    event.lastSeen,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                )
            return activity.getString(R.string.snoop_meta, rel, event.count, event.category)
        }

        private fun displayIcon(event: SnoopEvent) {
            val drawable =
                if (event.packageName.isEmpty() ||
                    event.packageName == Constants.EMPTY_PACKAGE_NAME
                ) {
                    getDefaultIcon(activity)
                } else {
                    getIcon(activity, event.packageName, event.appName)
                }
            Glide.with(activity).load(drawable).error(getDefaultIcon(activity)).into(b.snoopAppIcon)
            // 白い熊 考直 UI: apply the configured snoop-icon size + roundness (no-op off the Custom theme).
            CustomUi.applySnoopIcon(activity, b.snoopAppIcon)
        }

        private fun displaySeverity(event: SnoopEvent) {
            val (label, color) =
                when (event.severity) {
                    SnoopClassifier.SEV_HIGH ->
                        activity.getString(R.string.snoop_sev_high) to COLOR_HIGH
                    SnoopClassifier.SEV_MEDIUM ->
                        activity.getString(R.string.snoop_sev_medium) to COLOR_MEDIUM
                    else -> activity.getString(R.string.snoop_sev_low) to COLOR_LOW
                }
            b.snoopSeverityBadge.text = label
            b.snoopSeverityBar.setBackgroundColor(color)
            // pill background: severity-coloured fill + configurable radius / border / min-width
            val density = activity.resources.displayMetrics.density
            val cfg = if (CustomUi.customThemeActive) CustomUiConfig(activity) else null
            b.snoopSeverityBadge.background =
                CustomUi.snoopPillBackground(
                    activity,
                    color,
                    cfg?.snoopPillRadius ?: DEFAULT_PILL_RADIUS_DP,
                    cfg?.snoopPillBorderWidth ?: 0,
                    cfg?.snoopPillBorderColor ?: 0
                )
            val padH = (PILL_PAD_H_DP * density).toInt()
            val padV = (PILL_PAD_V_DP * density).toInt()
            b.snoopSeverityBadge.setPadding(padH, padV, padH, padV)
            b.snoopSeverityBadge.minimumWidth = ((cfg?.snoopPillWidth ?: 0) * density).toInt()
        }

        private fun displayState(event: SnoopEvent) {
            val blocked = event.lastBlocked
            b.snoopState.text =
                activity.getString(
                    if (blocked) R.string.snoop_state_blocked else R.string.snoop_state_allowed
                )
            // Under the Custom theme the colour comes from the 白い熊 考直 UI (per state); otherwise
            // the built-in green/amber.
            val color =
                if (CustomUi.customThemeActive) {
                    val cfg = CustomUiConfig(activity)
                    if (blocked) cfg.snoopStateBlockedColor else cfg.snoopStateAllowedColor
                } else {
                    if (blocked) COLOR_BLOCKED else COLOR_ALLOWED
                }
            b.snoopState.setTextColor(color)
        }

        private fun showActions(anchor: View, event: SnoopEvent) {
            val items =
                mutableListOf(
                    CustomUi.MenuItem(MI_BLOCK_APP, activity.getString(R.string.snoop_action_block_app)),
                    CustomUi.MenuItem(MI_BLOCK_ALL, activity.getString(R.string.snoop_action_block_all)),
                    CustomUi.MenuItem(MI_TRUST_APP, activity.getString(R.string.snoop_action_trust_app)),
                    CustomUi.MenuItem(MI_ADVANCED, activity.getString(R.string.snoop_action_advanced)),
                    CustomUi.MenuItem(MI_TAGS, activity.getString(R.string.snoop_action_tags)),
                    CustomUi.MenuItem(MI_DISMISS, activity.getString(R.string.snoop_action_dismiss))
                )
            if (canOpenApp(event.uid)) {
                items.add(CustomUi.MenuItem(MI_OPEN_APP, activity.getString(R.string.snoop_action_open_app)))
            }
            CustomUi.showMenu(anchor, items) { id ->
                when (id) {
                    MI_BLOCK_APP -> blockDomain(event, event.uid)
                    MI_BLOCK_ALL -> blockDomain(event, UID_EVERYBODY)
                    MI_TRUST_APP -> trustDomain(event)
                    MI_ADVANCED -> openAdvanced(event)
                    MI_TAGS -> showTagDialog(event)
                    MI_DISMISS -> dismiss(event)
                    MI_OPEN_APP -> openApp(event.uid)
                }
            }
        }

        // --- Fork (白い熊 考直): user tags / categories for a domain ---

        // Render the domain's tag chips (outline pills: border + text in the tag's colour, or the
        // configured default yellow when the tag has no colour) into the row's tag row; hide when none.
        private fun displayTags(event: SnoopEvent) {
            val container = b.snoopTags
            container.removeAllViews()
            val tags = SnoopTagStore.tagsFor(activity, event.domain)
            if (tags.isEmpty()) {
                container.visibility = View.GONE
                return
            }
            container.visibility = View.VISIBLE
            val cfg = CustomUiConfig(activity)
            val density = activity.resources.displayMetrics.density
            val ph = (8 * density).toInt()
            val pv = (2 * density).toInt()
            val gap = (4 * density).toInt()
            for (t in tags) {
                val border = t.color ?: cfg.snoopTagBorderColor
                val textColor = t.color ?: cfg.snoopTagTextColor
                val chip =
                    AppCompatTextView(activity).apply {
                        text = t.name
                        setTextColor(textColor)
                        background =
                            CustomUi.snoopPillBackground(
                                activity, 0x00000000, TAG_CHIP_RADIUS_DP, cfg.snoopTagBorderWidth, border
                            )
                        setPadding(ph, pv, ph, pv)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        setOnLongClickListener { showTagChipMenu(this, event, t); true }
                    }
                val lp =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = gap }
                container.addView(chip, lp)
            }
        }

        // Long-press a chip → edit the tag (rename / recolour / delete) or remove it from this domain.
        private fun showTagChipMenu(anchor: View, event: SnoopEvent, tag: SnoopTagStore.Tag) {
            val items =
                listOf(
                    CustomUi.MenuItem(MI_TAG_EDIT, activity.getString(R.string.snoop_tag_edit)),
                    CustomUi.MenuItem(MI_TAG_REMOVE_HERE, activity.getString(R.string.snoop_tag_remove_here))
                )
            CustomUi.showMenu(anchor, items) { id ->
                when (id) {
                    MI_TAG_EDIT -> SnoopTagUi.showEdit(activity, tag) { refresh() }
                    MI_TAG_REMOVE_HERE -> {
                        val cur = SnoopTagStore.tagNamesFor(activity, event.domain).toMutableSet()
                        cur.removeAll { it.equals(tag.name, ignoreCase = true) }
                        SnoopTagStore.setDomainTags(activity, event.domain, cur)
                        refresh()
                    }
                }
            }
        }

        // Multi-select existing tags for this domain (or create a new one, which auto-assigns here).
        private fun showTagDialog(event: SnoopEvent) {
            val all = SnoopTagStore.tags(activity)
            if (all.isEmpty()) {
                createTagFor(event)
                return
            }
            val assigned = SnoopTagStore.tagNamesFor(activity, event.domain).map { it.lowercase() }.toSet()
            val names = all.map { it.name }.toTypedArray()
            val checked = BooleanArray(all.size) { assigned.contains(all[it].name.lowercase()) }
            MaterialAlertDialogBuilder(activity, R.style.App_Dialog_NoDim)
                .setTitle(event.domain)
                .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
                .setNeutralButton(R.string.snoop_tag_new) { _, _ -> createTagFor(event) }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val sel = all.filterIndexed { i, _ -> checked[i] }.map { it.name }
                    SnoopTagStore.setDomainTags(activity, event.domain, sel)
                    refresh()
                }
                .setNegativeButton(R.string.lbl_cancel, null)
                .show()
        }

        // Create a new tag (shared dialog), auto-assign it to this domain, then reopen the picker.
        private fun createTagFor(event: SnoopEvent) {
            SnoopTagUi.showNew(activity) { name ->
                val cur = SnoopTagStore.tagNamesFor(activity, event.domain).toMutableSet()
                cur.add(name)
                SnoopTagStore.setDomainTags(activity, event.domain, cur)
                refresh()
                showTagDialog(event)
            }
        }

        private fun canOpenApp(uid: Int): Boolean {
            return uid > 0 && uid != UID_EVERYBODY && uid != INVALID_UID
        }

        private fun blockDomain(event: SnoopEvent, uid: Int) {
            toast(activity.getString(R.string.snoop_toast_blocked, event.domain))
            io {
                DomainRulesManager.block(
                    event.domain,
                    uid,
                    "",
                    DomainRulesManager.DomainType.DOMAIN
                )
                repository.setBlocked(event.uid, event.domain, true)
                uiCtx { refresh() }
            }
        }

        private fun trustDomain(event: SnoopEvent) {
            toast(activity.getString(R.string.snoop_toast_trusted, event.domain))
            io {
                DomainRulesManager.changeStatus(
                    event.domain,
                    event.uid,
                    "",
                    DomainRulesManager.DomainType.DOMAIN,
                    DomainRulesManager.Status.TRUST
                )
                repository.setBlocked(event.uid, event.domain, false)
                uiCtx { refresh() }
            }
        }

        private fun openAdvanced(event: SnoopEvent) {
            io {
                val cd: CustomDomain =
                    DomainRulesManager.getObj(event.uid, event.domain)
                        ?: DomainRulesManager.makeCustomDomain(event.uid, event.domain)
                uiCtx {
                    try {
                        CustomDomainRulesBtmSheet.newInstance(cd)
                            .show(activity.supportFragmentManager, TAG)
                    } catch (e: Exception) {
                        Logger.w(LOG_TAG_UI, "$TAG err showing rules sheet: ${e.message}")
                    }
                }
            }
        }

        private fun dismiss(event: SnoopEvent) {
            toast(activity.getString(R.string.snoop_toast_dismissed, event.domain))
            io {
                repository.dismiss(event.uid, event.domain)
                uiCtx { refresh() }
            }
        }

        private fun openApp(uid: Int) {
            val intent = Intent(activity, AppInfoActivity::class.java)
            intent.putExtra(AppInfoActivity.INTENT_UID, uid)
            activity.startActivity(intent)
        }

        private fun toast(msg: String) {
            Utilities.showToastUiCentered(activity, msg, android.widget.Toast.LENGTH_SHORT)
        }

        private fun io(f: suspend () -> Unit) {
            activity.lifecycleScope.launch(Dispatchers.IO) { f() }
        }

        private suspend fun uiCtx(f: suspend () -> Unit) {
            withContext(Dispatchers.Main) { f() }
        }
    }
}
