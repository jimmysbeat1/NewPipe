/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.movies.Movie
import org.schabi.newpipe.movies.library.Film
import org.schabi.newpipe.movies.library.FilmLicence
import org.schabi.newpipe.movies.library.FilmSource
import org.schabi.newpipe.movies.library.LicenceKind

class LocalMatchesTest {
    private fun tmdb(title: String, year: Int?, original: String? = null): TmdbMovie {
        return TmdbMovie(1, title, originalTitle = original, releaseDate = year?.let { "$it-06-01" })
    }

    private fun film(title: String, year: Int?): Film {
        return Film(
            source = FilmSource.INTERNET_ARCHIVE,
            id = title,
            title = title,
            year = year,
            durationSeconds = 5400,
            pageUrl = "https://archive.org/details/x",
            licence = FilmLicence(LicenceKind.CC_BY, "CC BY 4.0")
        )
    }

    private fun youtube(title: String, year: Int?, trusted: Boolean = true, officialOnly: Boolean = false): Movie {
        return Movie(
            serviceId = 0,
            url = "https://www.youtube.com/watch?v=abc",
            title = title,
            originalTitle = title,
            channel = "Distributor",
            channelUrl = null,
            durationSeconds = 5400,
            year = year,
            trusted = trusted,
            officialOnly = officialOnly
        )
    }

    @Test
    fun titlesAreComparedWithoutCasePunctuationAccentsAndArticles() {
        assertEquals("amelie", LocalMatches.normalize("Amélie"))
        assertEquals("night of the living dead", LocalMatches.normalize("The Night of the Living Dead!"))
        assertEquals("fast and furious", LocalMatches.normalize("Fast & Furious"))
        assertEquals("spider man no way home", LocalMatches.normalize("Spider-Man: No Way Home"))
        assertEquals("", LocalMatches.normalize("?!"))
    }

    @Test
    fun aFilmMatchesOnlyWithTheSameTitleAndAReleaseYearAtMostOneApart() {
        assertTrue(LocalMatches.sameFilm("Sita Sings the Blues", 2008, "sita sings the blues", 2009))
        assertFalse(LocalMatches.sameFilm("Sita Sings the Blues", 2008, "Sita Sings the Blues", 2011))
        assertFalse(LocalMatches.sameFilm("Sita Sings the Blues", 2008, "Sita Sings the Blues 2", 2008))
        // without a year on both sides a remake cannot be told from the original
        assertFalse(LocalMatches.sameFilm("Nosferatu", null, "Nosferatu", 1922))
        assertFalse(LocalMatches.sameFilm("Nosferatu", 2024, "Nosferatu", null))
        assertFalse(LocalMatches.sameFilm("?!", 2020, "...", 2020))
    }

    @Test
    fun aRemakeIsNotOfferedAsTheOpenLicenceOriginal() {
        val library = listOf(film("Nosferatu", 1922), film("Big Buck Bunny", 2008))
        assertNull(LocalMatches.film(tmdb("Nosferatu", 2024), library))
        assertEquals("Nosferatu", LocalMatches.film(tmdb("Nosferatu", 1922), library)?.title)
        assertEquals("Big Buck Bunny", LocalMatches.film(tmdb("Peach", 2008, original = "Big Buck Bunny"), library)?.title)
        assertNull(LocalMatches.film(tmdb("Elephants Dream", 2006), library))
    }

    @Test
    fun onlyFilmsOfKnownDistributorsThatTheAppMayPlayAreOfferedFromYoutube() {
        val movie = tmdb("Charade", 1963)
        assertEquals("Charade", LocalMatches.youtube(movie, listOf(youtube("Charade", 1963)))?.title)
        assertNull(LocalMatches.youtube(movie, listOf(youtube("Charade", 1963, trusted = false))))
        assertNull(LocalMatches.youtube(movie, listOf(youtube("Charade", 1963, officialOnly = true))))
        assertNull(LocalMatches.youtube(movie, listOf(youtube("Charade", null))))
    }

    @Test
    fun providersAreReachedThroughTheWatchPageUnlessTheirSearchAddressIsKnown() {
        val movie = TmdbMovie(693134, "Dune: Part Two", releaseDate = "2024-02-27")
        val listed = Availability("AU", link = "https://www.themoviedb.org/movie/693134-dune-part-two/watch?locale=AU")

        assertEquals(listed.link, ProviderLinks.watchPage(movie.id, listed, "AU"))
        assertEquals("https://www.themoviedb.org/movie/693134/watch?locale=AU", ProviderLinks.watchPage(movie.id, null, "AU"))
        assertEquals("https://www.themoviedb.org/movie/693134", ProviderLinks.tmdbPage(movie.id))

        assertEquals(
            "https://www.sbs.com.au/ondemand/search/Dune%3A%20Part%20Two",
            ProviderLinks.providerSearch("SBS On Demand", movie)
        )
        assertEquals(
            "https://www.youtube.com/results?search_query=Dune%3A+Part+Two+2024",
            ProviderLinks.providerSearch("YouTube", movie)
        )
        assertTrue(ProviderLinks.isYoutube("YouTube Free"))
        // no guessed addresses: these are reached through the watch page
        for (provider in listOf("Tubi TV", "Plex", "Netflix", "Apple TV", "7plus")) {
            assertNull(provider, ProviderLinks.providerSearch(provider, movie))
            assertFalse(ProviderLinks.isYoutube(provider))
        }
    }
}
