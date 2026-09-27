/*
 * Copyright 2026 RethinkDNS and its authors
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

import android.annotation.SuppressLint
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.customui.KojikiQuickFilters
import com.google.android.material.chip.Chip
import kotlin.math.abs

/**
 * Fork (白い熊 考直): the apps view's quick-filter pill row.
 *
 * A `RecyclerView` rather than upstream's `ChipGroup`, for two reasons: the row is now
 * heterogeneous (see [KojikiQuickFilters]), so the single-selection a `ChipGroup` enforces is wrong;
 * and reordering wants `ItemTouchHelper`, which needs a `RecyclerView` underneath it.
 *
 * ### Three gestures on one pill
 * Tap applies the filter, **hold-and-move drags it to a new position**, and **hold-and-release**
 * opens its menu. Holding is the start of both of the latter two, so neither
 * `isLongPressDragEnabled` (which would begin a drag the instant the menu wanted to open) nor a
 * separate "reorder mode" is used: [PillTouch] arms on the long-press timeout and then watches what
 * the finger does — past the touch slop it becomes a drag, released in place it becomes the menu.
 *
 * Arming also has to take the gesture away from the row, which scrolls horizontally and would
 * otherwise read the drag as a scroll and cancel the child's touch — hence the
 * `requestDisallowInterceptTouchEvent` on arm. A *plain* horizontal swipe never arms, so the row
 * still scrolls normally.
 */
class KojikiQuickFilterAdapter(
    private val context: Context,
    private val onApply: (String) -> Unit,
    private val onMenu: (String) -> Unit,
    private val checked: (String) -> Boolean
) : RecyclerView.Adapter<KojikiQuickFilterAdapter.VH>() {

    private val keys = mutableListOf<String>()

    /** Set by the activity once the helper exists; a hold-and-move asks it to take over. */
    var itemTouchHelper: ItemTouchHelper? = null

    fun submit(newKeys: List<String>) {
        keys.clear()
        keys.addAll(newKeys)
        notifyDataSetChanged()
    }

    /** The order as it stands now — read after a drag settles, to persist it. */
    fun currentOrder(): List<String> = keys.toList()

    override fun getItemCount(): Int = keys.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val chip =
            LayoutInflater.from(context).inflate(R.layout.item_chip_filter, parent, false) as Chip
        return VH(chip)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(keys[position])

    fun move(from: Int, to: Int) {
        if (from !in keys.indices || to !in keys.indices) return
        keys.add(to, keys.removeAt(from))
        notifyItemMoved(from, to)
    }

    inner class VH(private val chip: Chip) : RecyclerView.ViewHolder(chip) {

        @SuppressLint("ClickableViewAccessibility")
        fun bind(key: String) {
            chip.text = KojikiQuickFilters.labelOf(context, key)
            // The chip is checkable, but the bar owns what "checked" means — the filter state, not
            // the view's own toggle — so the listener is cleared before the state is set and the
            // click is handled as a plain click. Otherwise binding a recycled row fires the filter.
            chip.setOnCheckedChangeListener(null)
            chip.isCheckable = true
            chip.isChecked = checked(key)
            chip.alpha = 1f

            val touch = PillTouch(this, key)
            chip.setOnTouchListener(touch)
            chip.setOnClickListener {
                // keep the visual state owned by the filters, not by the tap
                chip.isChecked = checked(key)
                // a hold that ended in the menu (or a drag) must not also apply the filter
                if (touch.consumeSuppressedClick()) return@setOnClickListener
                onApply(key)
            }
            // The framework would fire its own long-click at the same moment PillTouch arms; take
            // it out of the running so the two cannot both respond to one hold.
            chip.setOnLongClickListener { true }
        }
    }

    /**
     * Turns one hold into either a drag or a menu, depending on whether the finger moves.
     *
     * Returns false throughout so the chip's own click handling still runs for a plain tap; the
     * view is clickable, so its `onTouchEvent` keeps the gesture alive and this listener keeps
     * being consulted for the moves and the release.
     */
    private inner class PillTouch(
        private val holder: VH,
        private val key: String
    ) : android.view.View.OnTouchListener {

        private var armed = false
        private var dragging = false
        private var suppressClick = false
        private var downX = 0f
        private var downY = 0f
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var arm: Runnable? = null

        fun consumeSuppressedClick(): Boolean {
            val s = suppressClick
            suppressClick = false
            return s
        }

        override fun onTouch(v: android.view.View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    armed = false
                    dragging = false
                    suppressClick = false
                    val r = Runnable {
                        armed = true
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        // the row scrolls horizontally; without this it reads the coming drag as a
                        // scroll, intercepts, and cancels the touch we are in the middle of
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    arm = r
                    v.postDelayed(r, ViewConfiguration.getLongPressTimeout().toLong())
                }

                MotionEvent.ACTION_MOVE -> {
                    val moved =
                        abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop
                    if (!moved) return false
                    if (armed && !dragging) {
                        dragging = true
                        suppressClick = true
                        itemTouchHelper?.startDrag(holder)
                    } else if (!armed) {
                        // a plain swipe: let the row scroll, and do not arm mid-gesture
                        cancelArm(v)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    cancelArm(v)
                    if (armed && !dragging) {
                        // held in place and let go: the menu
                        suppressClick = true
                        onMenu(key)
                    }
                    armed = false
                }

                MotionEvent.ACTION_CANCEL -> {
                    cancelArm(v)
                    armed = false
                }
            }
            return false
        }

        private fun cancelArm(v: android.view.View) {
            arm?.let { v.removeCallbacks(it) }
            arm = null
            v.parent?.requestDisallowInterceptTouchEvent(false)
        }
    }

    /**
     * Horizontal drag. Long-press drag is OFF — [PillTouch] decides when a hold has become a drag
     * and starts it — and the settled order is handed back on release.
     */
    class DragCallback(
        private val adapter: KojikiQuickFilterAdapter,
        private val onSettled: (List<String>) -> Unit
    ) : ItemTouchHelper.SimpleCallback(ItemTouchHelper.START or ItemTouchHelper.END, 0) {

        override fun isLongPressDragEnabled(): Boolean = false

        override fun isItemViewSwipeEnabled(): Boolean = false

        override fun onMove(
            rv: RecyclerView,
            vh: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            adapter.move(vh.bindingAdapterPosition, target.bindingAdapterPosition)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            onSettled(adapter.currentOrder())
        }
    }
}
