/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.discover

import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import org.schabi.newpipe.movies.Movie
import org.schabi.newpipe.movies.library.Film

/**
 * Finds a film from TMDB among the films the app can play itself: the open-licence film library
 * and the films distributors publish on their YouTube channels.
 *
 * The match is deliberately strict. Titles must be equal apart from case, punctuation, accents
 * and a leading article, and both sides must state a release year that differs by at most one.
 * A remake or an unrelated film with the same title is therefore not offered as the film the
 * user is looking at.
 */
object LocalMatches {
    private val MARKS = Regex("\\p{M}+")
    private val NOT_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
    private val ARTICLE = Regex("^(the|a|an) ")

    fun normalize(title: String): String {
        val plain = Normalizer.normalize(title, Normalizer.Form.NFD).replace(MARKS, "")
        return plain.lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(NOT_ALPHANUMERIC, " ")
            .trim()
            .replace(ARTICLE, "")
    }

    fun sameFilm(title: String, year: Int?, otherTitle: String, otherYear: Int?): Boolean {
        if (year == null || otherYear == null || abs(year - otherYear) > 1) {
            return false
        }
        val normalized = normalize(title)
        return normalized.isNotEmpty() && normalized == normalize(otherTitle)
    }

    private fun titles(movie: TmdbMovie): List<String> = listOfNotNull(movie.title, movie.originalTitle).distinct()

    /** The film in the open-licence library, which can be streamed and downloaded in the app. */
    fun film(movie: TmdbMovie, films: Collection<Film>): Film? {
        return films.firstOrNull { film -> titles(movie).any { sameFilm(it, movie.year, film.title, film.year) } }
    }

    /** The film on the YouTube channel of a known distributor, which the app can stream. */
    fun youtube(movie: TmdbMovie, catalog: Collection<Movie>): Movie? {
        return catalog.firstOrNull { candidate ->
            candidate.trusted && !candidate.officialOnly &&
                titles(movie).any { sameFilm(it, movie.year, candidate.title, candidate.year) }
        }
    }
}

/**
 * Where the app sends the user to watch a film it cannot play itself.
 *
 * TMDB does not publish links into the providers. It publishes, per film and country, a page
 * that lists the offers with the links JustWatch supplies; that page is the default
 * destination. Only for providers whose public search address is known, the search of the
 * provider is opened directly.
 */
object ProviderLinks {
    const val TMDB_KEY_PAGE = "https://www.themoviedb.org/settings/api"
    const val TMDB_HOME = "https://www.themoviedb.org/"
    const val JUSTWATCH_HOME = "https://www.justwatch.com/au"

    fun tmdbPage(movieId: Int): String = "https://www.themoviedb.org/movie/$movieId"

    /** The page that lists the offers for the film, with links to the providers. */
    fun watchPage(movieId: Int, availability: Availability?, region: String): String {
        return availability?.link ?: (tmdbPage(movieId) + "/watch?locale=" + region)
    }

    /** The search of the provider for the film, if the provider has a known search address. */
    fun providerSearch(provider: String, movie: TmdbMovie): String? {
        val name = provider.lowercase(Locale.ROOT)
        return when {
            name.startsWith("sbs") ->
                "https://www.sbs.com.au/ondemand/search/" + URLEncoder.encode(movie.title, "UTF-8").replace("+", "%20")

            name.startsWith("youtube") ->
                "https://www.youtube.com/results?search_query=" + URLEncoder.encode(movie.title + " " + (movie.year ?: ""), "UTF-8")

            else -> null
        }
    }

    fun isYoutube(provider: String): Boolean = provider.lowercase(Locale.ROOT).startsWith("youtube")
}
