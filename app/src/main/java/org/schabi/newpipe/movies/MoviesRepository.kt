/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.schabi.newpipe.recommend.data.PersonalStore

@Serializable
data class MovieCatalogCache(
    val refreshedAt: Long = 0,
    val movies: List<Movie> = emptyList()
)

class MoviesOutcome(val succeeded: Int, val failed: Int, val error: Throwable?) {
    val nothingLoaded: Boolean
        get() = succeeded == 0 && failed > 0
}

/**
 * The catalog of free films from the trusted distributors, the watchlist and the list of
 * purchased titles. Everything is stored on the device only. All functions block.
 */
class MoviesRepository(
    private val store: PersonalStore,
    private val fetcher: MovieFetcher,
    private val distributors: List<Distributor> = MovieCatalog.DISTRIBUTORS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val continuations = HashMap<String, MovieFetcher.Continuation>()
    private val movieList = ListSerializer(Movie.serializer())

    // -------------------------------------------------------------------- catalog

    fun catalog(): MovieCatalogCache = store.read(CATALOG, MovieCatalogCache.serializer(), MovieCatalogCache())

    fun isStale(): Boolean = clock() - catalog().refreshedAt > STALE_AFTER_MILLIS

    /** Loads the newest films of every distributor and merges them into the catalog. */
    fun refresh(): MoviesOutcome {
        synchronized(continuations) { continuations.clear() }
        return fetch(distributors.map { distributor -> distributor to { fetcher.channel(distributor) } }, true)
    }

    /** `true` if [loadMore] can extend the catalog with older films. */
    fun canLoadMore(): Boolean = synchronized(continuations) { continuations.isNotEmpty() }

    /** Loads the next, older page of every distributor that has one. */
    fun loadMore(): MoviesOutcome {
        val pending = synchronized(continuations) { HashMap(continuations) }
        val requests = distributors.mapNotNull { distributor ->
            val continuation = pending[distributor.channelId] ?: return@mapNotNull null
            distributor to { fetcher.more(distributor, continuation) }
        }
        return fetch(requests, false)
    }

    private fun fetch(
        requests: List<Pair<Distributor, () -> MovieFetcher.ChannelPage>>,
        fullRefresh: Boolean
    ): MoviesOutcome {
        if (requests.isEmpty()) {
            return MoviesOutcome(0, 0, null)
        }
        val fetched = ArrayList<Movie>()
        var succeeded = 0
        var failed = 0
        var error: Throwable? = null
        val executor = Executors.newFixedThreadPool(PARALLEL_REQUESTS)
        try {
            val futures = executor.invokeAll(requests.map { Callable { it.second() } })
            futures.forEachIndexed { index, future ->
                val channelId = requests[index].first.channelId
                try {
                    val page = future.get()
                    fetched.addAll(page.movies)
                    synchronized(continuations) {
                        if (page.next != null) {
                            continuations[channelId] = page.next
                        } else {
                            continuations.remove(channelId)
                        }
                    }
                    succeeded++
                } catch (e: ExecutionException) {
                    failed++
                    error = error ?: (e.cause ?: e)
                    synchronized(continuations) { continuations.remove(channelId) }
                }
            }
        } finally {
            executor.shutdown()
        }

        if (succeeded > 0) {
            val old = catalog()
            val now = clock()
            // newly fetched entries replace stale copies; everything else is kept so that the
            // catalog keeps growing, up to a per-channel limit
            // (older pages go behind what is already there, a refresh in front of it)
            val combined = if (fullRefresh) fetched + old.movies else old.movies + fetched
            val merged = combined.distinctBy { it.url }
                .groupBy { it.channelUrl ?: it.channel }
                .flatMap { it.value.take(MAX_PER_CHANNEL) }
            store.write(
                CATALOG,
                MovieCatalogCache.serializer(),
                MovieCatalogCache(if (fullRefresh) now else old.refreshedAt, merged)
            )
        }
        return MoviesOutcome(succeeded, failed, error)
    }

    // --------------------------------------------------------------------- search

    /**
     * Searches the catalog and the service. Results from uploaders that are not trusted
     * distributors are only included when [includeUnverified] is set.
     *
     * @return the matches, catalog entries first, and the error of the online search if it failed
     */
    fun search(query: String, includeUnverified: Boolean): Pair<List<Movie>, Throwable?> {
        val local = MovieCatalog.search(catalog().movies, query)
        var error: Throwable? = null
        val remote = try {
            fetcher.search(query).filter { it.trusted || includeUnverified }
        } catch (e: Exception) {
            error = e
            emptyList()
        }
        return (local + remote).distinctBy { it.url } to error
    }

    // ------------------------------------------------------------------ watchlist

    fun watchlist(): List<Movie> = store.read(WATCHLIST, movieList, emptyList())

    fun addToWatchlist(movie: Movie) {
        store.write(WATCHLIST, movieList, listOf(movie) + watchlist().filterNot { it.url == movie.url })
    }

    fun removeFromWatchlist(url: String) {
        store.write(WATCHLIST, movieList, watchlist().filterNot { it.url == url })
    }

    // ------------------------------------------------------------------ purchases

    /** Titles the user bought or rented; they are played by the official player only. */
    fun purchases(): List<Movie> = store.read(PURCHASES, movieList, emptyList())

    fun addPurchase(title: String, url: String) {
        val movie = Movie(
            serviceId = 0,
            url = url,
            title = title,
            originalTitle = title,
            channel = "",
            channelUrl = null,
            durationSeconds = 0,
            trusted = true,
            officialOnly = true
        )
        store.write(PURCHASES, movieList, listOf(movie) + purchases().filterNot { it.url == url })
    }

    fun removePurchase(url: String) {
        store.write(PURCHASES, movieList, purchases().filterNot { it.url == url })
    }

    companion object {
        private const val CATALOG = "movie_catalog"
        private const val WATCHLIST = "movie_watchlist"
        private const val PURCHASES = "movie_purchases"

        const val STALE_AFTER_MILLIS = 24 * 60 * 60 * 1000L
        private const val MAX_PER_CHANNEL = 300
        private const val PARALLEL_REQUESTS = 4
    }
}
