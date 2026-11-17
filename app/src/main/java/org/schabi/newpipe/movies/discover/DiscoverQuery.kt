/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.discover

import java.time.LocalDate
import kotlinx.serialization.Serializable

/** The sections of the Movies tab. */
enum class Section {
    NEW_RELEASES,
    FREE,
    HOLLYWOOD,
    ANIME,
    ACTION,
    HORROR,
    COMEDY,
    SCI_FI,
    WATCHLIST,
    DOWNLOADED;

    /** Whether the films of the section come from TMDB (the others are stored on the device). */
    val online: Boolean
        get() = this != WATCHLIST && this != DOWNLOADED
}

/** Which release years are shown. */
enum class Years {
    /** The last three calendar years, e.g. 2024 to 2026. */
    LATEST,
    FROM_2020,
    ANY
}

/** Which films are shown, by how they can be watched in the chosen country. */
enum class AccessFilter {
    /** Free and free with ads. */
    FREE_ONLY,

    /** Free, free with ads, and included in a subscription. */
    FREE_AND_SUBSCRIPTION,

    /** Everything that can be watched somewhere, including films that must be rented or bought. */
    ANYTHING_WATCHABLE;

    fun accepts(access: Access?): Boolean {
        return when (this) {
            FREE_ONLY -> access?.isFree == true
            FREE_AND_SUBSCRIPTION -> access != null && access <= Access.SUBSCRIPTION
            ANYTHING_WATCHABLE -> access != null
        }
    }

    /** The value of TMDB's `with_watch_monetization_types` parameter. */
    val monetizationTypes: String
        get() = when (this) {
            FREE_ONLY -> "free|ads"
            FREE_AND_SUBSCRIPTION -> "free|ads|flatrate"
            ANYTHING_WATCHABLE -> "free|ads|flatrate|rent|buy"
        }
}

enum class SortOrder {
    /** What suits the section: the newest first in "New releases", the most popular elsewhere. */
    RECOMMENDED,
    POPULAR,
    NEWEST,
    TOP_RATED
}

/** The viewing preferences of the user. They are stored on the device only. */
@Serializable
data class Filters(
    val years: Years = Years.FROM_2020,
    val access: AccessFilter = AccessFilter.ANYTHING_WATCHABLE,
    val sort: SortOrder = SortOrder.RECOMMENDED,
    val minimumRating: Int = 0
) {
    val isDefault: Boolean
        get() = this == Filters()
}

/**
 * Turns a section and the filters into a request to TMDB's "discover" endpoint.
 *
 * Every request asks for films that can be watched in [region] in one of the accepted ways.
 * TMDB's answer does not say how each film can be watched, so the result is only a list of
 * candidates: [DiscoverRepository] looks up every film and labels it with what it finds.
 */
object DiscoverQuery {
    const val REGION = "AU"
    const val LANGUAGE = "en-AU"

    /** Feature films only: shorter works are not what this section is about. */
    private const val MINIMUM_RUNTIME_MINUTES = 60

    /** How many months back "New releases" reaches. */
    const val NEW_RELEASE_MONTHS = 12L

    /** How many calendar years [Years.LATEST] covers, including the current one. */
    private const val LATEST_YEARS = 3

    fun build(section: Section, filters: Filters, today: LocalDate, region: String = REGION): Map<String, String> {
        require(section.online) { "$section is not loaded from TMDB" }
        val parameters = LinkedHashMap<String, String>()
        parameters["language"] = LANGUAGE
        parameters["region"] = region
        parameters["include_adult"] = "false"
        parameters["include_video"] = "false"
        parameters["with_runtime.gte"] = MINIMUM_RUNTIME_MINUTES.toString()

        // only films that can be watched in the country, in a way the user accepts
        val access = if (section == Section.FREE) AccessFilter.FREE_ONLY else filters.access
        parameters["watch_region"] = region
        parameters["with_watch_monetization_types"] = access.monetizationTypes

        // nothing that has not been released yet
        parameters["primary_release_date.lte"] = today.toString()
        firstReleaseDate(section, filters.years, today)?.let {
            parameters["primary_release_date.gte"] = it.toString()
        }

        val sort = effectiveSort(section, filters.sort)
        parameters["sort_by"] = when (sort) {
            SortOrder.NEWEST -> "primary_release_date.desc"
            SortOrder.TOP_RATED -> "vote_average.desc"
            else -> "popularity.desc"
        }
        // films hardly anyone has rated are obscure, and their scores mean little
        parameters["vote_count.gte"] = minimumVotes(section, sort).toString()
        if (filters.minimumRating > 0) {
            parameters["vote_average.gte"] = filters.minimumRating.toString()
        }

        when (section) {
            Section.HOLLYWOOD -> {
                parameters["with_origin_country"] = "US"
                parameters["with_original_language"] = "en"
            }

            Section.ANIME -> {
                parameters["with_genres"] = TmdbGenres.ANIMATION.toString()
                parameters["with_original_language"] = "ja"
            }

            Section.ACTION -> parameters["with_genres"] = TmdbGenres.ACTION.toString()

            Section.HORROR -> parameters["with_genres"] = TmdbGenres.HORROR.toString()

            Section.COMEDY -> parameters["with_genres"] = TmdbGenres.COMEDY.toString()

            Section.SCI_FI -> parameters["with_genres"] = TmdbGenres.SCIENCE_FICTION.toString()

            else -> Unit
        }
        return parameters
    }

    fun effectiveSort(section: Section, sort: SortOrder): SortOrder {
        return when {
            sort != SortOrder.RECOMMENDED -> sort
            section == Section.NEW_RELEASES -> SortOrder.NEWEST
            else -> SortOrder.POPULAR
        }
    }

    /** The earliest release date the section shows, or `null` for no limit. */
    fun firstReleaseDate(section: Section, years: Years, today: LocalDate): LocalDate? {
        if (section == Section.NEW_RELEASES) {
            return today.minusMonths(NEW_RELEASE_MONTHS)
        }
        return when (years) {
            Years.LATEST -> LocalDate.of(today.year - (LATEST_YEARS - 1), 1, 1)
            Years.FROM_2020 -> LocalDate.of(2020, 1, 1)
            Years.ANY -> null
        }
    }

    private fun minimumVotes(section: Section, sort: SortOrder): Int {
        return when {
            sort == SortOrder.TOP_RATED -> 200
            section == Section.HOLLYWOOD -> 100
            // free catalogues and anime have fewer widely rated titles
            section == Section.FREE || section == Section.ANIME -> 10
            else -> 25
        }
    }

    /** Whether a film found by a search passes the filters (searches cannot be filtered at TMDB). */
    fun matches(movie: TmdbMovie, filters: Filters, today: LocalDate): Boolean {
        val year = movie.year
        val first = firstReleaseDate(Section.FREE, filters.years, today)
        if (first != null && (year == null || year < first.year)) {
            return false
        }
        return filters.minimumRating <= 0 || movie.voteAverage >= filters.minimumRating
    }
}
