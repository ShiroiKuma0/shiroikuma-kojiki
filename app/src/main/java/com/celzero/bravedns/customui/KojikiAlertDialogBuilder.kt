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
package com.celzero.bravedns.customui

import android.content.Context
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Fork (白い熊 考直): every dialog in the app, wearing the fork's bordered box.
 *
 * A drop-in subclass of [MaterialAlertDialogBuilder] — each call site changes by one identifier and
 * keeps its whole builder chain (`setItems`, `setMultiChoiceItems`, `setView`, adapters, listeners),
 * so the sweep across upstream's ~144 dialogs stays mechanical and rebase-friendly. That matters:
 * hand-porting each of those dialogs to a bespoke builder would be a large, conflict-prone diff over
 * files we do not own.
 *
 * The border is applied by [CustomUi.themeAlertSurface] when the dialog's decor attaches to the
 * window. The hook is an **attach listener, not `setOnShowListener`**: a call site is free to set its
 * own show-listener (several do), which would silently replace ours — nothing replaces the attach
 * listener. Off the Custom theme this is inert and the dialog looks exactly as upstream drew it.
 *
 * Fork-authored dialogs that need full control of their layout use [KojikiDialog] instead; this is
 * for everything that already exists.
 */
class KojikiAlertDialogBuilder : MaterialAlertDialogBuilder {

    constructor(context: Context) : super(context)

    constructor(context: Context, overrideThemeResId: Int) : super(context, overrideThemeResId)

    override fun create(): AlertDialog {
        val dialog = super.create()
        dialog.window?.decorView?.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    // The decor is attached but the alert's panels are laid out a frame later on
                    // some paths, so paint now and once more after layout settles.
                    CustomUi.themeAlertSurface(dialog)
                    v.post { CustomUi.themeAlertSurface(dialog) }
                }

                override fun onViewDetachedFromWindow(v: View) {}
            })
        return dialog
    }
}
