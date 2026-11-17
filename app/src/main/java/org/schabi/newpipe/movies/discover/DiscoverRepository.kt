/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.discover

import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.schabi.newpipe.recommend.data.PersonalStore

/** A film the user wants to watch later. */
@Serializable
data class WatchlistEntry(
    val movie: TmdbMovie,
    val addedAt: Long,
    val availability: Availability? = null
)

/** What is known about where films can be watched, by TMDB id. */
@Serializable
data class AvailabilityCache(val entries: Map<Int, Availability> = emptyMap())

/**
 * The first films of a section as they were last shown.
 *
 * @param nextPage the TMDB page to continue with, or `null` at the end of the list
 */
@Serializable
data class CachedList(
    val fetchedAt: Long = 0,
    val movies: List<ListedMovie> = emptyList(),
    val nextPage: Int? = null
)

@Serializable
data class DiscoverCache(val lists: Map<String, CachedList> = emptyMap())

/** What the user last looked at and how it was filtered. */
@Serializable
data class DiscoverPreferences(
    val filters: Filters = Filters(),
    val section: Section = Section.NEW_RELEASES
)

/**
 * A part of a list.
 *
 * @param nextPage the TMDB page to continue with, or `null` at the end of the list
 * @param hidden how many films TMDB returned that are not shown, because they cannot be watched
 * in the chosen country in a way the filters accept
 */
class Batch(val movies: List<ListedMovie>, val nextPage: Int?, val hidden: Int = 0)

/**
 * The films of the Movies tab: lists from TMDB, each film labelled with how it can be watched
 * in Australia, the watchlist and the viewing preferences.
 *
 * A film is only listed after its availability has been looked up, and only if it can be
 * watched in a way the filters accept. The label of a film (free, free with ads, subscription,
 * rent, buy) always comes from that lookup and never from the request that found the film.
 *
 * Everything is stored on the device only. All functions block.
 */
class DiscoverRepository(
    private val store: PersonalStore,
    private val client: TmdbClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = LocalDate::now,
    private val region: String = DiscoverQuery.REGION,
    private val executor: ExecutorService = sharedExecutor
) {
    private val lock = Any()

    // ------------------------------------------------------------ preferences

    fun preferences(): DiscoverPreferences {
        return store.read(PREFERENCES, DiscoverPreferences.serializer(), DiscoverPreferences())
    }

    fun savePreferences(preferences: DiscoverPreferences) {
        try {
            store.write(PREFERENCES, DiscoverPreferences.serializer(), preferences)
        } catch (e: IOException) {
            // the preferences then apply until the app is closed
        }
    }

    // ------------------------------------------------------------------ lists

    private fun listKey(section: Section, filters: Filters): String {
        return listOf(section.name, filters.years.name, filters.access.name, filters.sort.name, filters.minimumRating, region)
            .joinToString("/")
    }

    /** What was last shown for the section, if anything. It may be out of date, see [isStale]. */
    fun cached(section: Section, filters: Filters): CachedList? {
        return store.read(LISTS, DiscoverCache.serializer(), DiscoverCache()).lists[listKey(section, filters)]
    }

    fun isStale(list: CachedList?): Boolean = list == null || clock() - list.fetchedAt > LIST_FRESH_MILLIS

    /**
     * Loads films of a section, starting at the TMDB page [page].
     *
     * Because films that turn out not to be watchable are dropped, one TMDB page can yield only
     * a few films. Further pages are then loaded, but never more than [MAX_PAGES_PER_LOAD] per
     * call, so that an unlucky filter cannot cause a flood of requests.
     */
    @Throws(TmdbException::class)
    fun load(section: Section, filters: Filters, page: Int = 1): Batch {
        val parameters = DiscoverQuery.build(section, filters, today(), region)
        val access = if (section == Section.FREE) AccessFilter.FREE_ONLY else filters.access
        val movies = ArrayList<ListedMovie>()
        var hidden = 0
        var next: Int? = page
        var loaded = 0
        while (next != null && loaded < MAX_PAGES_PER_LOAD && movies.size < WANTED_PER_LOAD) {
            val result = client.discover(parameters, next)
            loaded++
            val labelled = label(result.movies)
            val accepted = labelled.filter { access.accepts(it.availability?.best) }
            hidden += labelled.size - accepted.size
            movies.addAll(accepted)
            next = if (result.page < result.totalPages && result.page < MAX_TMDB_PAGE && result.movies.isNotEmpty()) {
                result.page + 1
            } else {
                null
            }
        }
        val unique = movies.distinctBy { it.movie.id }
        if (page == 1) {
            remember(listKey(section, filters), CachedList(clock(), unique, next))
        }
        return Batch(unique, next, hidden)
    }

    /**
     * Searches TMDB by title. Results are held to the same standard as the sections: a film
     * that cannot be watched in a way the filters accept is counted in [Batch.hidden] instead
     * of being listed.
     */
    @Throws(TmdbException::class)
    fun search(query: String, filters: Filters, page: Int = 1): Batch {
        val result = client.search(query, DiscoverQuery.LANGUAGE, region, page)
        val day = today()
        val candidates = result.movies.filter { DiscoverQuery.matches(it, filters, day) }
        val labelled = label(candidates)
        val accepted = labelled.filter { filters.access.accepts(it.availability?.best) }
        val next = if (result.page < result.totalPages && result.page < MAX_SEARCH_PAGE) result.page + 1 else null
        return Batch(accepted, next, result.movies.size - accepted.size)
    }

    @Throws(TmdbException::class)
    fun details(movieId: Int): MovieDetails {
        val details = client.details(movieId, DiscoverQuery.LANGUAGE, region, clock())
        synchronized(lock) {
            val cache = store.read(AVAILABILITY, AvailabilityCache.serializer(), AvailabilityCache())
            writeAvailability(cache.entries + (movieId to details.availability))
            // keep the watchlist entry current, so that its label is not out of date
            val watchlist = watchlist()
            if (watchlist.any { it.movie.id == movieId }) {
                val updated = watchlist.map {
                    if (it.movie.id == movieId) it.copy(movie = details.movie, availability = details.availability) else it
                }
                try {
                    saveWatchlist(updated)
                } catch (e: IOException) {
                    // the entry keeps its previous label
                }
            }
        }
        return details
    }

    private fun remember(key: String, list: CachedList) {
        synchronized(lock) {
            val cache = store.read(LISTS, DiscoverCache.serializer(), DiscoverCache())
            val lists = LinkedHashMap(cache.lists)
            lists[key] = list
            // only the most recently used lists are kept
            while (lists.size > MAX_CACHED_LISTS) {
                val oldest = lists.minByOrNull { it.value.fetchedAt }?.key ?: break
                lists.remove(oldest)
            }
            try {
                store.write(LISTS, DiscoverCache.serializer(), DiscoverCache(lists))
            } catch (e: IOException) {
                // the list is then simply loaded again next time
            }
        }
    }

    // ----------------------------------------------------------- availability

    /**
     * Looks up where each film can be watched: from the cache where that is recent enough, at
     * TMDB otherwise, a few requests at a time.
     *
     * A film whose lookup failed is returned without availability and is therefore not listed:
     * nothing is assumed about a film that could not be checked. If nothing at all is known
     * about any of the films, or the key was rejected, the failure is thrown instead.
     */
    @Throws(TmdbException::class)
    fun label(movies: List<TmdbMovie>): List<ListedMovie> {
        if (movies.isEmpty()) {
            return emptyList()
        }
        val now = clock()
        val known = HashMap<Int, Availability>()
        synchronized(lock) {
            val cache = store.read(AVAILABILITY, AvailabilityCache.serializer(), AvailabilityCache())
            for (movie in movies) {
                cache.entries[movie.id]
                    ?.takeIf { it.region == region && now - it.fetchedAt <= AVAILABILITY_FRESH_MILLIS }
                    ?.let { known[movie.id] = it }
            }
        }
        val missing = movies.map { it.id }.distinct().filter { it !in known }
        if (missing.isNotEmpty()) {
            val failures = ArrayList<TmdbException>()
            val fetched = HashMap<Int, Availability>()
            val tasks = missing.map { id -> Callable { client.availability(id, region, now) } }
            executor.invokeAll(tasks).forEachIndexed { index, future ->
                try {
                    fetched[missing[index]] = future.get()
                } catch (e: ExecutionException) {
                    failures.add(e.cause as? TmdbException ?: TmdbException(TmdbProblem.SERVICE))
                }
            }
            failures.firstOrNull { it.problem == TmdbProblem.INVALID_KEY || it.problem == TmdbProblem.NO_KEY }
                ?.let { throw it }
            if (known.isEmpty() && fetched.isEmpty() && failures.isNotEmpty()) {
                throw failures.first()
            }
            if (fetched.isNotEmpty()) {
                known.putAll(fetched)
                synchronized(lock) {
                    val cache = store.read(AVAILABILITY, AvailabilityCache.serializer(), AvailabilityCache())
                    writeAvailability(cache.entries + fetched)
                }
            }
        }
        return movies.map { ListedMovie(it, known[it.id]) }
    }

    private fun writeAvailability(entries: Map<Int, Availability>) {
        // the oldest entries make room for new ones
        val kept = if (entries.size > MAX_AVAILABILITY_ENTRIES) {
            entries.entries.sortedByDescending { it.value.fetchedAt }
                .take(MAX_AVAILABILITY_ENTRIES)
                .associate { it.key to it.value }
        } else {
            entries
        }
        try {
            store.write(AVAILABILITY, AvailabilityCache.serializer(), AvailabilityCache(kept))
        } catch (e: IOException) {
            // availability is then looked up again next time
        }
    }

    // -------------------------------------------------------------- watchlist

    fun watchlist(): List<WatchlistEntry> = store.read(WATCHLIST, WATCHLIST_SERIALIZER, emptyList())

    fun isOnWatchlist(movieId: Int): Boolean = watchlist().any { it.movie.id == movieId }

    /** Adds the film to the watchlist or removes it. @return whether it is on the list now */
    @Throws(IOException::class)
    fun toggleWatchlist(movie: TmdbMovie, availability: Availability?): Boolean {
        synchronized(lock) {
            val watchlist = watchlist()
            val listed = watchlist.any { it.movie.id == movie.id }
            saveWatchlist(
                if (listed) {
                    watchlist.filter { it.movie.id != movie.id }
                } else {
                    listOf(WatchlistEntry(movie, clock(), availability)) + watchlist
                }
            )
            return !listed
        }
    }

    /**
     * The watchlist with current availability. Films stay on the list when they can no longer
     * be watched, and are then labelled accordingly.
     *
     * @param refresh whether to look up availability that is out of date; without a key or a
     * connection the stored labels are shown
     */
    fun watchlistMovies(refresh: Boolean): List<ListedMovie> {
        var entries = watchlist()
        if (refresh && entries.isNotEmpty()) {
            try {
                val labelled = label(entries.map { it.movie }).associateBy { it.movie.id }
                synchronized(lock) {
                    entries = watchlist().map { entry ->
                        entry.copy(availability = labelled[entry.movie.id]?.availability ?: entry.availability)
                    }
                    saveWatchlist(entries)
                }
            } catch (e: IOException) {
                // the stored labels are better than nothing
            }
        }
        return entries.map { ListedMovie(it.movie, it.availability) }
    }

    private fun saveWatchlist(entries: List<WatchlistEntry>) {
        store.write(WATCHLIST, WATCHLIST_SERIALIZER, entries)
    }

    /** Forgets everything that was fetched from TMDB. The watchlist and preferences are kept. */
    fun clearCaches() {
        synchronized(lock) {
            store.delete(LISTS)
            store.delete(AVAILABILITY)
        }
    }

    companion object {
        private const val PREFERENCES = "discover_preferences"
        private const val LISTS = "discover_lists"
        private const val AVAILABILITY = "discover_availability"
        private const val WATCHLIST = "discover_watchlist"
        private val WATCHLIST_SERIALIZER = ListSerializer(WatchlistEntry.serializer())

        /** Lists are loaded again after this time. */
        const val LIST_FRESH_MILLIS = 6 * 60 * 60 * 1000L

        /** Where a film can be watched changes; it is looked up again after a day. */
        const val AVAILABILITY_FRESH_MILLIS = 24 * 60 * 60 * 1000L

        const val MAX_PAGES_PER_LOAD = 3
        const val WANTED_PER_LOAD = 18
        private const val MAX_TMDB_PAGE = 500
        private const val MAX_SEARCH_PAGE = 5
        private const val MAX_CACHED_LISTS = 24
        private const val MAX_AVAILABILITY_ENTRIES = 1500
        private const val PARALLEL_REQUESTS = 4

        /** Availability is looked up a few films at a time, well below TMDB's rate limit. */
        val sharedExecutor: ExecutorService by lazy {
            Executors.newFixedThreadPool(PARALLEL_REQUESTS) { runnable ->
                Thread(runnable, "tmdb-availability").apply { isDaemon = true }
            }
        }
    }
}
