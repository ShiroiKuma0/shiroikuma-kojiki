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
import android.util.AttributeSet
import android.view.View
import androidx.annotation.Keep
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.AppCompatRadioButton
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.theme.MaterialComponentsViewInflater

/**
 * Fork (白い熊 考直): the Custom theme's `viewInflaterClass`. Every button, switch, radio button and
 * check box inflated anywhere under that theme — a screen, a list row, a sheet, a dialog's own
 * layout — passes through here and takes the black-yellow shape at once, also where the run-time
 * pass of [CustomUi] never walks (a dialog's content, a view inflated into a popup).
 *
 * A dialog's own button bar is left to [KojikiDialog.stylePill], which knows which of the three is
 * the main one.
 */
@Keep
class KojikiViewInflater : MaterialComponentsViewInflater() {

    override fun createButton(context: Context, attrs: AttributeSet): AppCompatButton =
        super.createButton(context, attrs).also { style(it) }

    override fun createRadioButton(context: Context, attrs: AttributeSet): AppCompatRadioButton =
        super.createRadioButton(context, attrs).also { KojikiPills.mark(it) }

    override fun createCheckBox(context: Context, attrs: AttributeSet): AppCompatCheckBox =
        super.createCheckBox(context, attrs).also { KojikiPills.mark(it) }

    override fun createView(context: Context, name: String, attrs: AttributeSet): View? =
        when (name) {
            MATERIAL_SWITCH -> MaterialSwitch(context, attrs).also { KojikiPills.switch(it) }
            MATERIAL_BUTTON -> MaterialButton(context, attrs).also { style(it) }
            MATERIAL_RADIO -> MaterialRadioButton(context, attrs).also { KojikiPills.mark(it) }
            MATERIAL_CHECK -> MaterialCheckBox(context, attrs).also { KojikiPills.mark(it) }
            else -> super.createView(context, name, attrs)
        }

    private fun style(v: View) {
        if (v !is MaterialButton) return
        if (v.id == android.R.id.button1 || v.id == android.R.id.button2 || v.id == android.R.id.button3) return
        if (v.tag == CustomUi.NO_RESTYLE_TAG) return
        if (v.isCheckable) KojikiPills.segment(v) else KojikiPills.button(v)
    }

    private companion object {
        const val MATERIAL_SWITCH = "com.google.android.material.materialswitch.MaterialSwitch"
        const val MATERIAL_BUTTON = "com.google.android.material.button.MaterialButton"
        const val MATERIAL_RADIO = "com.google.android.material.radiobutton.MaterialRadioButton"
        const val MATERIAL_CHECK = "com.google.android.material.checkbox.MaterialCheckBox"
    }
}
