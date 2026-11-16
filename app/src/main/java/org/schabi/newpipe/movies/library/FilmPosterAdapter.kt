/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.ListMovieItemBinding
import org.schabi.newpipe.util.image.CoilHelper

/** Shows the films of the library as posters, with the state of their download. */
class FilmPosterAdapter(
    private val onClick: (Film) -> Unit
) : ListAdapter<FilmPosterAdapter.Card, FilmPosterAdapter.Holder>(DIFF) {

    data class Card(val film: Film, val status: String?)

    class Holder(val binding: ListMovieItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ListMovieItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.posterView.clipToOutline = true
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val card = getItem(position)
        val film = card.film
        with(holder.binding) {
            titleView.text = film.title
            detailsView.text = FilmFormat.details(root.context, film)
            detailsView.isVisible = true
            channelView.text = FilmFormat.origin(film)
            channelView.isVisible = true
            badgeView.text = card.status
            badgeView.isVisible = card.status != null

            if (film.posterUrl != null) {
                CoilHelper.loadThumbnail(posterView, film.posterUrl)
            } else {
                posterView.setImageResource(R.drawable.placeholder_thumbnail_video)
            }

            root.contentDescription = film.title
            root.setOnClickListener { onClick(film) }
            root.setOnLongClickListener(null)
            root.isLongClickable = false
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Card>() {
            override fun areItemsTheSame(old: Card, new: Card) = old.film.key == new.film.key

            override fun areContentsTheSame(old: Card, new: Card) = old == new
        }
    }
}
