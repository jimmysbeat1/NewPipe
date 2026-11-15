/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.recommend.ui

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/** Shows one fixed view as a list item; used for headers in a ConcatAdapter. */
class SingleViewAdapter(private val view: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        (view.parent as? ViewGroup)?.removeView(view)
        view.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return object : RecyclerView.ViewHolder(view) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit

    override fun getItemCount() = 1

    override fun getItemViewType(position: Int) = VIEW_TYPE

    companion object {
        const val VIEW_TYPE = 9001
    }
}
