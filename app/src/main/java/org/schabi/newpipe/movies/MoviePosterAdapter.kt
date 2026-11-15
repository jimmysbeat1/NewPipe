/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.ListMovieItemBinding
import org.schabi.newpipe.util.image.CoilHelper

/**
 * Shows films as posters.
 *
 * @param fixedWidthDp the width of an item in a horizontal row, or `null` to fill a grid cell
 */
class MoviePosterAdapter(
    private val fixedWidthDp: Int?,
    private val onClick: (Movie) -> Unit,
    private val onMenu: (Movie, View) -> Unit
) : ListAdapter<Movie, MoviePosterAdapter.Holder>(DIFF) {

    class Holder(val binding: ListMovieItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ListMovieItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.posterView.clipToOutline = true
        if (fixedWidthDp != null) {
            val density = parent.resources.displayMetrics.density
            binding.root.layoutParams = binding.root.layoutParams.apply {
                width = (fixedWidthDp * density).toInt()
            }
        }
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val movie = getItem(position)
        val context = holder.itemView.context
        with(holder.binding) {
            titleView.text = movie.title
            detailsView.text = details(context, movie)
            detailsView.isVisible = detailsView.text.isNotEmpty()
            channelView.text = movie.channel
            channelView.isVisible = movie.channel.isNotBlank()

            badgeView.isVisible = movie.officialOnly || !movie.trusted
            if (movie.officialOnly) {
                badgeView.setText(R.string.movies_badge_official)
            } else if (!movie.trusted) {
                badgeView.setText(R.string.movies_badge_unverified)
            }

            if (movie.thumbnailUrl != null) {
                CoilHelper.loadThumbnail(posterView, movie.thumbnailUrl)
            } else {
                posterView.setImageResource(R.drawable.placeholder_thumbnail_video)
            }

            root.contentDescription = movie.title
            root.setOnClickListener { onClick(movie) }
            root.setOnLongClickListener {
                onMenu(movie, root)
                true
            }
        }
    }

    companion object {
        fun details(context: Context, movie: Movie): String {
            val parts = ArrayList<String>(2)
            movie.year?.let { parts.add(it.toString()) }
            if (movie.durationSeconds > 0) {
                val hours = movie.durationSeconds / 3600
                val minutes = movie.durationSeconds % 3600 / 60
                parts.add(
                    if (hours > 0) {
                        context.getString(R.string.movies_duration_hours, hours, minutes)
                    } else {
                        context.getString(R.string.movies_duration_minutes, minutes)
                    }
                )
            }
            return parts.joinToString(" • ")
        }

        private val DIFF = object : DiffUtil.ItemCallback<Movie>() {
            override fun areItemsTheSame(old: Movie, new: Movie) = old.url == new.url

            override fun areContentsTheSame(old: Movie, new: Movie) = old == new
        }
    }
}
