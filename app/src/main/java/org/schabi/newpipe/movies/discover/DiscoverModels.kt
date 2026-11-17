/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.discover

import kotlinx.serialization.Serializable

/**
 * How a film can be watched at a provider. The order is the order of preference: what costs the
 * viewer the least comes first.
 *
 * Only [FREE] and [FREE_WITH_ADS] may ever be presented as free. A film that is merely part of a
 * subscription, or that can be rented or bought, is never called free.
 */
enum class Access {
    FREE,
    FREE_WITH_ADS,
    SUBSCRIPTION,
    RENT,
    BUY;

    val isFree: Boolean
        get() = this == FREE || this == FREE_WITH_ADS
}

/** A streaming service or store, as named by the availability data. */
@Serializable
data class Provider(
    val id: Int,
    val name: String
)

@Serializable
data class Offer(
    val access: Access,
    val provider: Provider
)

/**
 * Where a film can be watched in one country. The data is supplied by JustWatch through TMDB.
 *
 * @param region the ISO 3166-1 country code the offers are valid for
 * @param link the page that lists the offers with links to the providers
 * @param fetchedAt when the data was fetched, in milliseconds since the epoch
 */
@Serializable
data class Availability(
    val region: String,
    val offers: List<Offer> = emptyList(),
    val link: String? = null,
    val fetchedAt: Long = 0
) {
    /** The cheapest way to watch the film, or `null` if it is not offered in [region]. */
    val best: Access?
        get() = offers.minOfOrNull { it.access }

    val isFree: Boolean
        get() = best?.isFree == true

    /** The names of the providers that offer the film in the given way, without repetitions. */
    fun providers(access: Access): List<String> {
        return offers.filter { it.access == access }.map { it.provider.name }.distinct()
    }

    /** The providers of the cheapest way to watch the film. */
    fun bestProviders(): List<String> = best?.let(::providers).orEmpty()
}

/**
 * A film as described by TMDB.
 *
 * @param releaseDate the release date as `yyyy-MM-dd`, or `null` if TMDB does not know it
 * @param voteAverage the TMDB user score from 0 to 10; meaningless while [voteCount] is 0
 */
@Serializable
data class TmdbMovie(
    val id: Int,
    val title: String,
    val originalTitle: String? = null,
    val overview: String? = null,
    val releaseDate: String? = null,
    val posterPath: String? = null,
    val genreIds: List<Int> = emptyList(),
    val voteAverage: Double = 0.0,
    val voteCount: Int = 0,
    val popularity: Double = 0.0,
    val originalLanguage: String? = null
) {
    val year: Int?
        get() = releaseDate?.take(4)?.toIntOrNull()?.takeIf { it > 1800 }
}

/** A trailer on YouTube that TMDB lists for a film. */
@Serializable
data class Trailer(
    val key: String,
    val name: String,
    val official: Boolean = false
) {
    val url: String
        get() = "https://www.youtube.com/watch?v=$key"
}

/** Everything the details of a film show. */
@Serializable
data class MovieDetails(
    val movie: TmdbMovie,
    val runtimeMinutes: Int = 0,
    val genres: List<String> = emptyList(),
    val trailers: List<Trailer> = emptyList(),
    val availability: Availability
)

/** A film in a list, together with what is known about where it can be watched. */
@Serializable
data class ListedMovie(
    val movie: TmdbMovie,
    val availability: Availability? = null
)

/** One page of a list at TMDB. */
class TmdbPage(val page: Int, val totalPages: Int, val movies: List<TmdbMovie>)

/** The parts of TMDB's film genres the sections use. */
object TmdbGenres {
    const val ACTION = 28
    const val ANIMATION = 16
    const val COMEDY = 35
    const val HORROR = 27
    const val SCIENCE_FICTION = 878
}
