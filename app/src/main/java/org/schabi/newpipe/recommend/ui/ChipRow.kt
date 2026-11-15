/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.recommend.ui

import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import org.schabi.newpipe.R

/** Fills a horizontal [LinearLayout] with selectable, pill-shaped filter chips. */
object ChipRow {
    class Chip<T>(val value: T, val label: String)

    fun <T> bind(
        container: LinearLayout,
        chips: List<Chip<T>>,
        selected: T,
        onSelect: (T) -> Unit,
        onLongPress: ((T) -> Boolean)? = null
    ) {
        val inflater = LayoutInflater.from(container.context)
        // reuse the existing views so that the row does not flicker on every update
        while (container.childCount > chips.size) {
            container.removeViewAt(container.childCount - 1)
        }
        while (container.childCount < chips.size) {
            inflater.inflate(R.layout.item_filter_chip, container, true)
        }
        chips.forEachIndexed { index, chip ->
            val view = container.getChildAt(index) as TextView
            view.text = chip.label
            view.isSelected = chip.value == selected
            view.setOnClickListener { onSelect(chip.value) }
            if (onLongPress != null) {
                view.setOnLongClickListener { onLongPress(chip.value) }
            } else {
                view.setOnLongClickListener(null)
                view.isLongClickable = false
            }
        }
    }
}
