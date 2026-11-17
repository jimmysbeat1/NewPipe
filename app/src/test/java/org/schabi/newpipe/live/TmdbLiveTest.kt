/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.live

import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.schabi.newpipe.movies.discover.AccessFilter
import org.schabi.newpipe.movies.discover.DiscoverQuery
import org.schabi.newpipe.movies.discover.DiscoverRepository
import org.schabi.newpipe.movies.discover.Filters
import org.schabi.newpipe.movies.discover.ListedMovie
import org.schabi.newpipe.movies.discover.Section
import org.schabi.newpipe.movies.discover.TmdbClient
import org.schabi.newpipe.movies.discover.TmdbCredential
import org.schabi.newpipe.movies.discover.TmdbNetwork
import org.schabi.newpipe.recommend.data.PersonalStore

/**
 * End-to-end tests of the movie discovery against the real TMDB API. They need network access
 * and a TMDB key, and depend on what TMDB and JustWatch know on the day, so they only run when
 * the key is provided in the environment:
 *
 * `PIPEHOME_TMDB_KEY=... ./gradlew :app:testDebugUnitTest --tests "*TmdbLiveTest*"`
 *
 * The key is never printed. With `PIPEHOME_LIVE_REPORT` set to a file name, a summary of what
 * was found is written there.
 */
class TmdbLiveTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var credential: TmdbCredential? = null
    private lateinit var client: TmdbClient
    private lateinit var repository: DiscoverRepository
    private val today = LocalDate.now()

    @Before
    fun setUp() {
        credential = TmdbCredential.parse(System.getenv("PIPEHOME_TMDB_KEY"))
        Assume.assumeTrue("PIPEHOME_TMDB_KEY is not set to a TMDB key", credential != null)
        client = TmdbClient(TmdbNetwork.http) { credential }
        repository = DiscoverRepository(
            PersonalStore(folder.newFolder("personalisation")),
            client,
            executor = Executors.newFixedThreadPool(4)
        )
    }

    private fun report(line: String) {
        println("LIVE $line")
        System.getenv("PIPEHOME_LIVE_REPORT")?.takeIf { it.isNotBlank() }?.let { File(it).appendText(line + "\n") }
    }

    private fun describe(listed: ListedMovie): String {
        val availability = listed.availability
        return "${listed.movie.id} | ${listed.movie.title} (${listed.movie.year}) | " +
            "score ${listed.movie.voteAverage} of ${listed.movie.voteCount} | " +
            "${availability?.best} | ${availability?.bestProviders()}"
    }

    @Test
    fun theKeyIsAccepted() {
        client.validate(credential!!)
    }

    @Test
    fun everySectionListsRecentFilmsThatCanBeWatchedInAustralia() {
        for (section in Section.entries.filter { it.online }) {
            val batch = repository.load(section, Filters())
            report("SECTION $section: ${batch.movies.size} listed, ${batch.hidden} hidden, next page ${batch.nextPage}")
            batch.movies.take(8).forEach { report("  " + describe(it)) }

            assertTrue("$section lists films", batch.movies.isNotEmpty())
            for (listed in batch.movies) {
                val name = "$section: ${listed.movie.title}"
                assertNotNull(name, listed.availability?.best)
                assertEquals(name, DiscoverQuery.REGION, listed.availability?.region)
                assertTrue(name, (listed.movie.year ?: 0) >= 2020)
                assertFalse(name, listed.movie.releaseDate.orEmpty() > today.toString())
            }
            if (section == Section.NEW_RELEASES) {
                val earliest = today.minusMonths(DiscoverQuery.NEW_RELEASE_MONTHS).toString()
                assertTrue(batch.movies.all { it.movie.releaseDate.orEmpty() >= earliest })
            }
        }
    }

    @Test
    fun freeMoviesAreFreeAccordingToTheirOwnAvailability() {
        val batch = repository.load(Section.FREE, Filters())
        val providers = batch.movies.flatMap { it.availability!!.bestProviders() }.groupingBy { it }.eachCount()
        report("FREE providers: " + providers.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" })
        assertTrue(batch.movies.isNotEmpty())
        assertTrue(batch.movies.all { it.availability!!.isFree })

        val free = repository.load(Section.ACTION, Filters(access = AccessFilter.FREE_ONLY))
        report("ACTION, free only: ${free.movies.size} listed, ${free.hidden} hidden")
        assertTrue(free.movies.all { it.availability!!.isFree })
    }

    @Test
    fun searchAndDetailsWork() {
        val batch = repository.search("Dune", Filters(access = AccessFilter.ANYTHING_WATCHABLE))
        report("SEARCH Dune: ${batch.movies.size} listed, ${batch.hidden} hidden")
        batch.movies.take(5).forEach { report("  " + describe(it)) }
        assertTrue(batch.movies.isNotEmpty())

        val details = repository.details(batch.movies.first().movie.id)
        report(
            "DETAILS ${details.movie.title}: ${details.runtimeMinutes} min, ${details.genres}, " +
                "${details.trailers.size} trailers, ${details.availability.offers.size} offers, link ${details.availability.link}"
        )
        assertTrue(details.runtimeMinutes > 0)
        assertTrue(details.genres.isNotEmpty())
        assertNotNull(details.availability.best)
        assertTrue(details.availability.link.orEmpty().startsWith("https://www.themoviedb.org/"))
    }
}
