/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.discover

import java.time.LocalDate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.schabi.newpipe.recommend.data.PersonalStore

class DiscoverRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_800_000_000_000L
    private val tmdb = FakeTmdb()
    private var credential: TmdbCredential? = TmdbCredential.parse(TmdbFixtures.V3_KEY)
    private lateinit var executor: ExecutorService
    private lateinit var store: PersonalStore
    private lateinit var repository: DiscoverRepository

    @Before
    fun setUp() {
        executor = Executors.newFixedThreadPool(3)
        store = PersonalStore(folder.newFolder("personalisation"))
        repository = newRepository()
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    private fun newRepository(): DiscoverRepository {
        return DiscoverRepository(
            store,
            TmdbClient(tmdb) { credential },
            clock = { now },
            today = { LocalDate.of(2026, 11, 17) },
            executor = executor
        )
    }

    /** Registers a film at the fake TMDB with the given offers in Australia. */
    private fun offer(id: Int, vararg offers: Pair<String, String>) {
        val byCategory = offers.groupBy({ it.first }, { it.second })
        tmdb.json("movie/$id/watch/providers", TmdbFixtures.providersJson(id, "AU", byCategory))
    }

    private fun movies(vararg ids: Int): Array<String> {
        return ids.map { TmdbFixtures.movieJson(it, "Film $it") }.toTypedArray()
    }

    private fun providerLookups(): Int = tmdb.paths().count { it.endsWith("/watch/providers") }

    private fun problemOf(block: () -> Unit): TmdbProblem {
        try {
            block()
        } catch (e: TmdbException) {
            return e.problem
        }
        fail("Expected a TmdbException")
        throw AssertionError()
    }

    // ------------------------------------------------------------------ labelling

    @Test
    fun everyListedFilmIsLabelledFromItsOwnAvailabilityLookup() {
        tmdb.json("discover/movie", TmdbFixtures.pageJson(1, 1, *movies(1, 2, 3, 4, 5)))
        offer(1, "free" to "SBS On Demand")
        offer(2, "ads" to "Tubi TV", "flatrate" to "Netflix")
        offer(3, "flatrate" to "Stan")
        offer(4, "rent" to "Apple TV", "buy" to "Apple TV")
        offer(5)

        val batch = repository.load(Section.ACTION, Filters())

        // film 5 cannot be watched in Australia and is therefore not listed at all
        assertEquals(listOf(1, 2, 3, 4), batch.movies.map { it.movie.id })
        assertEquals(1, batch.hidden)
        assertEquals(
            listOf(Access.FREE, Access.FREE_WITH_ADS, Access.SUBSCRIPTION, Access.RENT),
            batch.movies.map { it.availability?.best }
        )
        assertEquals(listOf("Tubi TV"), batch.movies[1].availability!!.bestProviders())
        assertNull(batch.nextPage)
    }

    @Test
    fun freeMoviesNeverContainAFilmThatMustBePaidFor() {
        // TMDB is asked for free films, but what it returns is checked film by film
        tmdb.json("discover/movie", TmdbFixtures.pageJson(1, 1, *movies(1, 2, 3, 4, 5)))
        offer(1, "free" to "Plex")
        offer(2, "ads" to "Tubi TV")
        offer(3, "flatrate" to "Netflix")
        offer(4, "rent" to "Apple TV")
        offer(5, "buy" to "Google Play Movies")

        val batch = repository.load(Section.FREE, Filters(access = AccessFilter.ANYTHING_WATCHABLE))

        assertEquals("free|ads", tmdb.calls.first { it.path == "discover/movie" }.query["with_watch_monetization_types"])
        assertEquals(listOf(1, 2), batch.movies.map { it.movie.id })
        assertTrue(batch.movies.all { it.availability!!.isFree })
        assertEquals(3, batch.hidden)
    }

    @Test
    fun theAccessFilterDecidesWhatIsListed() {
        tmdb.json("discover/movie", TmdbFixtures.pageJson(1, 1, *movies(1, 2, 3)))
        offer(1, "ads" to "Tubi TV")
        offer(2, "flatrate" to "Netflix")
        offer(3, "rent" to "Apple TV")

        fun listed(access: AccessFilter) = repository.load(Section.COMEDY, Filters(access = access)).movies.map { it.movie.id }

        assertEquals(listOf(1), listed(AccessFilter.FREE_ONLY))
        assertEquals(listOf(1, 2), listed(AccessFilter.FREE_AND_SUBSCRIPTION))
        assertEquals(listOf(1, 2, 3), listed(AccessFilter.ANYTHING_WATCHABLE))
    }

    @Test
    fun aFilmWhoseLookupFailedIsNotListed() {
        tmdb.json("discover/movie", TmdbFixtures.pageJson(1, 1, *movies(1, 2)))
        offer(1, "free" to "Plex")
        tmdb.on("movie/2/watch/providers") { TmdbResponse(500, "") }

        val batch = repository.load(Section.HORROR, Filters())
        assertEquals(listOf(1), batch.movies.map { it.movie.id })
    }

    @Test
    fun aRejectedKeyOrAnUnreachableServiceIsReportedInsteadOfAnEmptyList() {
        tmdb.json("discover/movie", TmdbFixtures.pageJson(1, 1, *movies(1, 2)))
        tmdb.on("movie/1/watch/providers") { TmdbResponse(500, "") }
        tmdb.on("movie/2/watch/providers") { TmdbResponse(500, "") }
        assertEquals(TmdbProblem.SERVICE, problemOf { repository.load(Section.HORROR, Filters()) })

        offer(1, "free" to "Plex")
        tmdb.on("movie/2/watch/providers") { TmdbResponse(401, "") }
        assertEquals(TmdbProblem.INVALID_KEY, problemOf { repository.load(Section.HORROR, Filters()) })

        credential = null
        assertEquals(TmdbProblem.NO_KEY, problemOf { repository.load(Section.HORROR, Filters()) })
    }

    // ---------------------------------------------------------- paging and cache

    @Test
    fun furtherPagesAreLoadedWhenFewFilmsCanBeWatchedButNeverWithoutLimit() {
        // every page has films, none of which can be watched
        tmdb.on("discover/movie") { call ->
            val page = call.query.getValue("page").toInt()
            TmdbResponse(200, TmdbFixtures.pageJson(page, 400, *movies(page * 10 + 1, page * 10 + 2)))
        }
        for (id in 11..62) {
            offer(id)
        }

        val batch = repository.load(Section.SCI_FI, Filters())

        assertTrue(batch.movies.isEmpty())
        assertEquals(DiscoverRepository.MAX_PAGES_PER_LOAD, tmdb.paths().count { it == "discover/movie" })
        assertEquals(DiscoverRepository.MAX_PAGES_PER_LOAD * 2, batch.hidden)
        // the list can be continued where this load stopped
        assertEquals(DiscoverRepository.MAX_PAGES_PER_LOAD + 1, batch.nextPage)
    }

    @Test
    fun availabilityIsCachedForADayAndListsAreKeptForReopening() {
        tmdb.json("discover/movie", TmdbFixtures.pageJson(1, 3, *movies(1, 2)))
        offer(1, "free" to "Plex")
        offer(2, "flatrate" to "Netflix")
        val filters = Filters()

        assertNull(repository.cached(Section.ACTION, filters))
        assertTrue(repository.isStale(null))
        repository.load(Section.ACTION, filters)
        assertEquals(2, providerLookups())

        // a new instance, as after a restart of the app
        val reopened = newRepository()
        val cached = reopened.cached(Section.ACTION, filters)
        assertNotNull(cached)
        assertEquals(listOf(1, 2), cached!!.movies.map { it.movie.id })
        assertEquals(Access.FREE, cached.movies[0].availability?.best)
        assertFalse(reopened.isStale(cached))
        // other filters have their own list
        assertNull(reopened.cached(Section.ACTION, Filters(access = AccessFilter.FREE_ONLY)))

        reopened.load(Section.ACTION, filters)
        assertEquals("availability is taken from the cache", 2, providerLookups())

        now += DiscoverRepository.LIST_FRESH_MILLIS + 1
        assertTrue(reopened.isStale(cached))

        now += DiscoverRepository.AVAILABILITY_FRESH_MILLIS
        offer(1, "rent" to "Apple TV")
        val later = reopened.load(Section.ACTION, filters)
        assertEquals("availability is looked up again after a day", 4, providerLookups())
        assertEquals(Access.RENT, later.movies[0].availability?.best)
    }

    // --------------------------------------------------------------------- search

    @Test
    fun searchResultsThatCannotBeWatchedAreCountedNotListed() {
        tmdb.json(
            "search/movie",
            TmdbFixtures.pageJson(
                1,
                1,
                TmdbFixtures.movieJson(1, "Dune: Part Two", "2024-02-27"),
                TmdbFixtures.movieJson(2, "Dune", "1984-12-14"),
                TmdbFixtures.movieJson(3, "Dune Drifter", "2020-12-01"),
                TmdbFixtures.movieJson(4, "Dune World", "2021-01-01")
            )
        )
        offer(1, "flatrate" to "Netflix")
        offer(3)
        offer(4, "ads" to "Tubi TV")

        val batch = repository.search("dune", Filters())

        assertEquals("dune", tmdb.calls[0].query["query"])
        assertEquals(listOf(1, 4), batch.movies.map { it.movie.id })
        // the film from 1984 (year filter) and the one without any offer
        assertEquals(2, batch.hidden)
        // the old film is filtered out before anything is looked up for it
        assertFalse(tmdb.paths().contains("movie/2/watch/providers"))

        val all = repository.search("dune", Filters(years = Years.ANY, access = AccessFilter.FREE_ONLY))
        assertEquals(listOf(4), all.movies.map { it.movie.id })
        assertEquals(3, all.hidden)
    }

    // ------------------------------------------------------------------ watchlist

    @Test
    fun theWatchlistIsStoredOnTheDeviceAndSurvivesARestart() {
        val film = TmdbMovie(7, "Seven", releaseDate = "2025-01-01")
        val availability = Availability("AU", listOf(Offer(Access.FREE_WITH_ADS, Provider(1, "Tubi TV"))), fetchedAt = now)

        assertFalse(repository.isOnWatchlist(7))
        assertTrue(repository.toggleWatchlist(film, availability))
        assertTrue(repository.toggleWatchlist(TmdbMovie(8, "Eight"), null))
        assertTrue(repository.isOnWatchlist(7))

        val reopened = newRepository()
        // the newest entry first; nothing is requested for showing the stored list
        assertEquals(listOf(8, 7), reopened.watchlistMovies(false).map { it.movie.id })
        assertEquals(Access.FREE_WITH_ADS, reopened.watchlistMovies(false)[1].availability?.best)
        assertTrue(tmdb.calls.isEmpty())

        assertFalse(reopened.toggleWatchlist(film, availability))
        assertEquals(listOf(8), reopened.watchlist().map { it.movie.id })
    }

    @Test
    fun watchlistLabelsAreRefreshedAndKeptWhenThatFails() {
        val film = TmdbMovie(7, "Seven", releaseDate = "2025-01-01")
        val stored = Availability("AU", listOf(Offer(Access.FREE, Provider(1, "Plex"))), fetchedAt = now)
        repository.toggleWatchlist(film, stored)
        now += DiscoverRepository.AVAILABILITY_FRESH_MILLIS + 1

        // offline: the stored label is shown
        tmdb.offline = true
        assertEquals(Access.FREE, repository.watchlistMovies(true).single().availability?.best)

        // the film left the free catalogue: it stays on the list with its new label
        tmdb.offline = false
        offer(7, "rent" to "Apple TV")
        assertEquals(Access.RENT, repository.watchlistMovies(true).single().availability?.best)
        assertEquals(Access.RENT, newRepository().watchlist().single().availability?.best)
    }

    @Test
    fun detailsUpdateTheCacheAndTheWatchlistEntry() {
        val film = TmdbMovie(42, "Old title")
        repository.toggleWatchlist(film, null)
        tmdb.json("movie/42", TmdbFixtures.detailsJson(42, "Interstellar Two", "AU", mapOf("ads" to listOf("Tubi TV"))))

        val details = repository.details(42)

        assertEquals(now, details.availability.fetchedAt)
        val entry = repository.watchlist().single()
        assertEquals("Interstellar Two", entry.movie.title)
        assertEquals(Access.FREE_WITH_ADS, entry.availability?.best)
        // listing the film afterwards needs no further lookup
        val listed = repository.label(listOf(details.movie)).single()
        assertEquals(Access.FREE_WITH_ADS, listed.availability?.best)
        assertEquals(0, providerLookups())
    }

    // ---------------------------------------------------------------- preferences

    @Test
    fun viewingPreferencesAreRememberedAndCachesCanBeCleared() {
        assertEquals(DiscoverPreferences(), repository.preferences())
        assertEquals(Section.NEW_RELEASES, repository.preferences().section)
        assertEquals(Years.FROM_2020, repository.preferences().filters.years)

        val chosen = DiscoverPreferences(
            Filters(Years.LATEST, AccessFilter.FREE_ONLY, SortOrder.TOP_RATED, 7),
            Section.ANIME
        )
        repository.savePreferences(chosen)
        assertEquals(chosen, newRepository().preferences())

        tmdb.json("discover/movie", TmdbFixtures.pageJson(1, 1, *movies(1)))
        offer(1, "free" to "Plex")
        repository.toggleWatchlist(TmdbMovie(9, "Nine"), null)
        repository.load(Section.ANIME, chosen.filters)
        assertNotNull(repository.cached(Section.ANIME, chosen.filters))

        repository.clearCaches()
        assertNull(repository.cached(Section.ANIME, chosen.filters))
        // what the user chose and saved is kept
        assertEquals(chosen, repository.preferences())
        assertEquals(listOf(9), repository.watchlist().map { it.movie.id })
    }
}
