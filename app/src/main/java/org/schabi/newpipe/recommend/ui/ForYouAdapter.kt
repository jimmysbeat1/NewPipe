/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.recommend.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.time.Instant
import java.time.ZoneOffset
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.ListForYouItemBinding
import org.schabi.newpipe.recommend.engine.Reason
import org.schabi.newpipe.recommend.engine.Recommendation
import org.schabi.newpipe.util.Localization
import org.schabi.newpipe.util.image.CoilHelper

class ForYouAdapter(
    private val onClick: (Recommendation) -> Unit,
    private val onMenu: (Recommendation, View) -> Unit
) : ListAdapter<Recommendation, ForYouAdapter.Holder>(DIFF) {

    class Holder(val binding: ListForYouItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ListForYouItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.itemThumbnailView.clipToOutline = true
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val recommendation = getItem(position)
        val candidate = recommendation.candidate
        val context = holder.itemView.context
        with(holder.binding) {
            itemVideoTitleView.text = candidate.title
            itemDetailsView.text = details(context, recommendation)
            itemReasonView.text = reasonText(context, recommendation)

            itemDurationView.isVisible = candidate.durationSeconds > 0
            if (candidate.durationSeconds > 0) {
                itemDurationView.text = Localization.getDurationString(candidate.durationSeconds)
            }
            CoilHelper.loadThumbnail(itemThumbnailView, candidate.thumbnailUrl)

            root.setOnClickListener { onClick(recommendation) }
            root.setOnLongClickListener {
                onMenu(recommendation, itemMenuButton)
                true
            }
            itemMenuButton.setOnClickListener { onMenu(recommendation, it) }
        }
    }

    private fun details(context: Context, recommendation: Recommendation): String {
        val candidate = recommendation.candidate
        val parts = ArrayList<String>(3)
        if (candidate.uploader.isNotBlank()) {
            parts.add(candidate.uploader)
        }
        if (candidate.viewCount >= 0) {
            parts.add(Localization.shortViewCount(context, candidate.viewCount))
        }
        candidate.uploadEpochMillis?.let {
            parts.add(Localization.relativeTime(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC)))
        }
        return parts.joinToString(" • ")
    }

    companion object {
        fun reasonText(context: Context, recommendation: Recommendation): String {
            val detail = recommendation.reasonDetail
            return when (recommendation.reason) {
                Reason.SUBSCRIPTION -> context.getString(R.string.for_you_reason_subscription)

                Reason.TOPIC -> if (detail != null) {
                    context.getString(R.string.for_you_reason_topic, detail)
                } else {
                    context.getString(R.string.for_you_reason_fresh)
                }

                Reason.CHANNEL -> context.getString(R.string.for_you_reason_channel)

                Reason.RELATED -> if (detail != null) {
                    context.getString(R.string.for_you_reason_related, detail)
                } else {
                    context.getString(R.string.for_you_reason_fresh)
                }

                Reason.TRENDING -> context.getString(R.string.for_you_reason_trending)

                Reason.FRESH -> context.getString(R.string.for_you_reason_fresh)
            }
        }

        private val DIFF = object : DiffUtil.ItemCallback<Recommendation>() {
            override fun areItemsTheSame(old: Recommendation, new: Recommendation): Boolean {
                return old.candidate.url == new.candidate.url
            }

            override fun areContentsTheSame(old: Recommendation, new: Recommendation): Boolean {
                return old.candidate == new.candidate &&
                    old.reason == new.reason && old.reasonDetail == new.reasonDetail
            }
        }
    }
}
