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
import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.customui.CustomUi
import com.celzero.bravedns.database.SnoopEvent
import com.celzero.bravedns.database.SnoopEventRepository
import com.celzero.bravedns.databinding.ListItemSnoopBinding
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.SnoopClassifier
import com.celzero.bravedns.ui.activity.AppInfoActivity
import com.celzero.bravedns.ui.bottomsheet.CustomDomainRulesBtmSheet
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
    private val repository: SnoopEventRepository
) : PagingDataAdapter<SnoopEvent, SnoopEventAdapter.SnoopViewHolder>(DIFF_CALLBACK) {

    companion object {
        private const val TAG = "SnoopAdapter"

        private const val COLOR_HIGH = 0xFFFF1744.toInt()
        private const val COLOR_MEDIUM = 0xFFFFA000.toInt()
        private const val COLOR_LOW = 0xFF9E9E9E.toInt()
        private const val COLOR_BLOCKED = 0xFF2B8E18.toInt()
        private const val COLOR_ALLOWED = 0xFFFFA000.toInt()

        // popup menu item ids
        private const val MI_BLOCK_APP = 1
        private const val MI_BLOCK_ALL = 2
        private const val MI_TRUST_APP = 3
        private const val MI_ADVANCED = 4
        private const val MI_DISMISS = 5
        private const val MI_OPEN_APP = 6

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
            // Tap the row → action menu; tap the app icon → open the app's page directly
            // (falls back to the menu when there's no openable app for this uid).
            b.snoopRow.setOnClickListener { showActions(it, event) }
            b.snoopAppIcon.setOnClickListener {
                if (canOpenApp(event.uid)) openApp(event.uid) else showActions(b.snoopRow, event)
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
            b.snoopSeverityBadge.backgroundTintList = ColorStateList.valueOf(color)
            b.snoopSeverityBar.setBackgroundColor(color)
        }

        private fun displayState(event: SnoopEvent) {
            if (event.lastBlocked) {
                b.snoopState.text = activity.getString(R.string.snoop_state_blocked)
                b.snoopState.setTextColor(COLOR_BLOCKED)
            } else {
                b.snoopState.text = activity.getString(R.string.snoop_state_allowed)
                b.snoopState.setTextColor(COLOR_ALLOWED)
            }
        }

        private fun showActions(anchor: View, event: SnoopEvent) {
            val popup = PopupMenu(activity, anchor)
            val m = popup.menu
            m.add(0, MI_BLOCK_APP, 0, activity.getString(R.string.snoop_action_block_app))
            m.add(0, MI_BLOCK_ALL, 1, activity.getString(R.string.snoop_action_block_all))
            m.add(0, MI_TRUST_APP, 2, activity.getString(R.string.snoop_action_trust_app))
            m.add(0, MI_ADVANCED, 3, activity.getString(R.string.snoop_action_advanced))
            m.add(0, MI_DISMISS, 4, activity.getString(R.string.snoop_action_dismiss))
            if (canOpenApp(event.uid)) {
                m.add(0, MI_OPEN_APP, 5, activity.getString(R.string.snoop_action_open_app))
            }
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MI_BLOCK_APP -> blockDomain(event, event.uid)
                    MI_BLOCK_ALL -> blockDomain(event, UID_EVERYBODY)
                    MI_TRUST_APP -> trustDomain(event)
                    MI_ADVANCED -> openAdvanced(event)
                    MI_DISMISS -> dismiss(event)
                    MI_OPEN_APP -> openApp(event.uid)
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            popup.show()
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
                        CustomDomainRulesBtmSheet(cd)
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
