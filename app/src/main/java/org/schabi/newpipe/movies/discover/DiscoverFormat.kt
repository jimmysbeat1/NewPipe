/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.discover

import android.content.Context
import java.util.Locale
import org.schabi.newpipe.R

/** Turns film data from TMDB into the texts the user interface shows. */
object DiscoverFormat {
    private const val POSTER_BASE = "https://image.tmdb.org/t/p/w342"

    fun posterUrl(movie: TmdbMovie): String? = movie.posterPath?.takeIf { it.startsWith("/") }?.let { POSTER_BASE + it }

    fun access(context: Context, access: Access): String {
        return context.getString(
            when (access) {
                Access.FREE -> R.string.discover_access_free
                Access.FREE_WITH_ADS -> R.string.discover_access_ads
                Access.SUBSCRIPTION -> R.string.discover_access_subscription
                Access.RENT -> R.string.discover_access_rent
                Access.BUY -> R.string.discover_access_buy
            }
        )
    }

    /**
     * The label on a poster. A film is only labelled free when the availability data says so;
     * without data it is labelled as not checked, and without offers as not available.
     */
    fun badge(context: Context, availability: Availability?): String {
        val best = availability?.best
        return when {
            availability == null -> context.getString(R.string.discover_access_unknown)
            best == null -> context.getString(R.string.discover_access_none)
            else -> access(context, best)
        }
    }

    /** E.g. "2025 • ★ 7.4". The score is left out while only a few people have rated the film. */
    fun details(movie: TmdbMovie): String {
        val parts = ArrayList<String>(2)
        movie.year?.let { parts.add(it.toString()) }
        if (movie.voteCount >= MINIMUM_VOTES_FOR_SCORE) {
            parts.add("★ " + String.format(Locale.ROOT, "%.1f", movie.voteAverage))
        }
        return parts.joinToString(" • ")
    }

    /** E.g. "Tubi TV, SBS On Demand": who offers the film in the cheapest way. */
    fun providers(availability: Availability?): String {
        val names = availability?.bestProviders().orEmpty()
        return if (names.size > MAX_PROVIDERS) {
            names.take(MAX_PROVIDERS).joinToString(", ") + " +" + (names.size - MAX_PROVIDERS)
        } else {
            names.joinToString(", ")
        }
    }

    /** All offers, one line per way of watching, e.g. "Free with ads: Tubi TV, 7plus". */
    fun offers(context: Context, availability: Availability): String {
        return Access.entries.mapNotNull { level ->
            val names = availability.providers(level)
            if (names.isEmpty()) null else access(context, level) + ": " + names.joinToString(", ")
        }.joinToString("\n")
    }

    fun section(context: Context, section: Section): String {
        return context.getString(
            when (section) {
                Section.NEW_RELEASES -> R.string.discover_section_new
                Section.FREE -> R.string.discover_section_free
                Section.HOLLYWOOD -> R.string.discover_section_hollywood
                Section.ANIME -> R.string.discover_section_anime
                Section.ACTION -> R.string.movies_genre_action
                Section.HORROR -> R.string.movies_genre_horror
                Section.COMEDY -> R.string.movies_genre_comedy
                Section.SCI_FI -> R.string.movies_genre_sci_fi
                Section.WATCHLIST -> R.string.movies_watchlist
                Section.DOWNLOADED -> R.string.discover_section_downloaded
            }
        )
    }

    fun sectionSummary(context: Context, section: Section): String {
        return context.getString(
            when (section) {
                Section.NEW_RELEASES -> R.string.discover_summary_new
                Section.FREE -> R.string.discover_summary_free
                Section.HOLLYWOOD -> R.string.discover_summary_hollywood
                Section.ANIME -> R.string.discover_summary_anime
                Section.WATCHLIST -> R.string.discover_summary_watchlist
                Section.DOWNLOADED -> R.string.discover_summary_downloaded
                else -> R.string.discover_summary_genre
            }
        )
    }

    /** E.g. "2020 and later • Anything watchable • Recommended". */
    fun filters(context: Context, filters: Filters): String {
        val parts = ArrayList<String>(4)
        parts.add(
            context.getString(
                when (filters.years) {
                    Years.LATEST -> R.string.discover_years_latest
                    Years.FROM_2020 -> R.string.discover_years_2020
                    Years.ANY -> R.string.discover_years_any
                }
            )
        )
        parts.add(
            context.getString(
                when (filters.access) {
                    AccessFilter.FREE_ONLY -> R.string.discover_filter_free
                    AccessFilter.FREE_AND_SUBSCRIPTION -> R.string.discover_filter_subscription
                    AccessFilter.ANYTHING_WATCHABLE -> R.string.discover_filter_any
                }
            )
        )
        parts.add(
            context.getString(
                when (filters.sort) {
                    SortOrder.RECOMMENDED -> R.string.discover_sort_recommended
                    SortOrder.POPULAR -> R.string.discover_sort_popular
                    SortOrder.NEWEST -> R.string.discover_sort_newest
                    SortOrder.TOP_RATED -> R.string.discover_sort_rating
                }
            )
        )
        if (filters.minimumRating > 0) {
            parts.add(context.getString(R.string.discover_rating_at_least, filters.minimumRating))
        }
        return parts.joinToString(" • ")
    }

    fun problem(context: Context, problem: TmdbProblem): String {
        return context.getString(
            when (problem) {
                TmdbProblem.NO_KEY -> R.string.discover_problem_no_key
                TmdbProblem.INVALID_KEY -> R.string.discover_problem_invalid_key
                TmdbProblem.RATE_LIMITED -> R.string.discover_problem_rate_limited
                TmdbProblem.NETWORK -> R.string.discover_problem_network
                TmdbProblem.NOT_FOUND, TmdbProblem.SERVICE -> R.string.discover_problem_service
            }
        )
    }

    private const val MINIMUM_VOTES_FOR_SCORE = 10
    private const val MAX_PROVIDERS = 2
}
