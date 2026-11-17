/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.discover

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.databinding.ListMovieItemBinding
import org.schabi.newpipe.movies.library.Film
import org.schabi.newpipe.movies.library.FilmFormat
import org.schabi.newpipe.movies.library.FilmPosters

/**
 * Shows films as posters. Every film from TMDB carries a label that says how it can be watched;
 * downloaded films carry the state of their download.
 */
class DiscoverAdapter(
    private val onClick: (Card) -> Unit
) : ListAdapter<DiscoverAdapter.Card, DiscoverAdapter.Holder>(DIFF) {

    sealed class Card {
        abstract val key: String

        /** A film from TMDB. */
        data class Online(val listed: ListedMovie) : Card() {
            override val key: String
                get() = "tmdb:" + listed.movie.id
        }

        /** A film of the open-licence library that was or is being downloaded. */
        data class Downloaded(val film: Film, val status: String?) : Card() {
            override val key: String
                get() = film.key
        }
    }

    class Holder(val binding: ListMovieItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ListMovieItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.posterView.clipToOutline = true
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val card = getItem(position)
        with(holder.binding) {
            val context = root.context
            when (card) {
                is Card.Online -> {
                    val movie = card.listed.movie
                    val badge = DiscoverFormat.badge(context, card.listed.availability)
                    val providers = DiscoverFormat.providers(card.listed.availability)
                    titleView.text = movie.title
                    detailsView.text = DiscoverFormat.details(movie)
                    channelView.text = providers
                    badgeView.text = badge
                    badgeView.isVisible = true
                    FilmPosters.load(posterView, DiscoverFormat.posterUrl(movie))
                    root.contentDescription = listOf(movie.title, badge, providers)
                        .filter { it.isNotEmpty() }
                        .joinToString(", ")
                }

                is Card.Downloaded -> {
                    titleView.text = card.film.title
                    detailsView.text = FilmFormat.details(context, card.film)
                    channelView.text = FilmFormat.origin(card.film)
                    badgeView.text = card.status
                    badgeView.isVisible = card.status != null
                    FilmPosters.load(posterView, card.film.posterUrl)
                    root.contentDescription = card.film.title
                }
            }
            detailsView.isVisible = true
            channelView.isVisible = true
            root.setOnClickListener { onClick(card) }
            root.setOnLongClickListener(null)
            root.isLongClickable = false
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Card>() {
            override fun areItemsTheSame(old: Card, new: Card) = old.key == new.key

            override fun areContentsTheSame(old: Card, new: Card) = old == new
        }
    }
}
