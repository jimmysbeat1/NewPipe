/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.library

import java.io.IOException
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlinx.serialization.Serializable
import org.schabi.newpipe.movies.Genre
import org.schabi.newpipe.recommend.data.PersonalStore

/**
 * @param rejected the candidates that failed the audit, by [Film.key], so that they are neither
 * shown nor fetched again
 */
@Serializable
data class FilmLibraryCache(
    val refreshedAt: Long = 0,
    val films: List<Film> = emptyList(),
    val rejected: Map<String, Rejection> = emptyMap()
)

class LibraryOutcome(val added: Int, val error: Throwable?)

/**
 * The library of full-length films that may be downloaded: finds candidates at the sources,
 * audits each one and remembers the result on the device. All functions block.
 */
class FilmLibraryRepository(
    private val store: PersonalStore,
    private val archive: InternetArchiveSource,
    private val commons: CommonsSource,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val archivePages = HashMap<Genre?, Int>()
    private var commonsOffset: Int? = 0

    fun cache(): FilmLibraryCache = store.read(NAME, FilmLibraryCache.serializer(), FilmLibraryCache())

    fun isStale(): Boolean = clock() - cache().refreshedAt > STALE_AFTER_MILLIS

    /** Loads the first page of both sources. */
    fun refresh(): LibraryOutcome {
        synchronized(this) {
            archivePages.clear()
            commonsOffset = 0
        }
        return load(null, true)
    }

    /** Extends the library; with a [genre], by more films of that genre. */
    fun loadMore(genre: Genre?): LibraryOutcome = load(genre, false)

    @Synchronized
    fun canLoadMore(genre: Genre?): Boolean {
        return archivePages[genre] != EXHAUSTED || (genre == null && commonsOffset != null)
    }

    private fun load(genre: Genre?, refresh: Boolean): LibraryOutcome {
        var error: Throwable? = null
        var succeeded = false
        val verdicts = ArrayList<Verdict>()

        val page = synchronized(this) { archivePages[genre] ?: 1 }
        if (page != EXHAUSTED) {
            try {
                val hits = archive.search(null, genre, page)
                verdicts.addAll(verify(hits))
                synchronized(this) {
                    archivePages[genre] = if (hits.size < InternetArchiveSource.PAGE_SIZE) EXHAUSTED else page + 1
                }
                succeeded = true
            } catch (e: IOException) {
                error = e
            }
        }
        val offset = synchronized(this) { commonsOffset }
        if (genre == null && offset != null) {
            try {
                val result = commons.search(null, offset)
                verdicts.addAll(result.verdicts)
                synchronized(this) { commonsOffset = result.nextOffset }
                succeeded = true
            } catch (e: IOException) {
                error = error ?: e
            }
        }
        val added = remember(verdicts, refresh && succeeded)
        return LibraryOutcome(added, error.takeIf { !succeeded })
    }

    /**
     * Searches the library and both sources.
     *
     * @return the matches and, if a source could not be reached, the error
     */
    fun search(query: String): Pair<List<Film>, Throwable?> {
        val local = FilmRules.search(cache().films, query)
        var error: Throwable? = null
        val verdicts = ArrayList<Verdict>()
        try {
            verdicts.addAll(verify(archive.search(query, null, 1)))
        } catch (e: IOException) {
            error = e
        }
        try {
            verdicts.addAll(commons.search(query, 0).verdicts)
        } catch (e: IOException) {
            error = error ?: e
        }
        remember(verdicts, false)
        val remote = verdicts.filterIsInstance<Verdict.Accepted>().map { it.film }
        return arrange(local + remote) to error
    }

    /**
     * Audits a film again at its source, as is done right before every download: licences can
     * be corrected and items withdrawn after they were added to the library.
     *
     * @return the up-to-date film, or `null` if it no longer qualifies (it is then removed)
     */
    @Throws(IOException::class)
    fun recheck(film: Film): Film? {
        val verdict = when (film.source) {
            FilmSource.INTERNET_ARCHIVE -> archive.verify(InternetArchiveSource.Hit(film.id, film.popularity))
            FilmSource.WIKIMEDIA_COMMONS -> commons.verify(film.id)
        }
        return when (verdict) {
            is Verdict.Accepted -> verdict.film

            is Verdict.Rejected -> {
                synchronized(this) {
                    val old = cache()
                    store.write(
                        NAME,
                        FilmLibraryCache.serializer(),
                        old.copy(
                            films = old.films.filterNot { it.key == film.key },
                            rejected = old.rejected + (film.key to verdict.reason)
                        )
                    )
                }
                null
            }
        }
    }

    /** Audits the candidates that are not known yet, a few at a time. */
    private fun verify(hits: List<InternetArchiveSource.Hit>): List<Verdict> {
        val known = cache()
        val knownKeys = known.films.mapTo(HashSet()) { it.key } + known.rejected.keys
        val unknown = hits.filter { InternetArchiveSource.key(it.identifier) !in knownKeys }
        if (unknown.isEmpty()) {
            return emptyList()
        }
        val executor = Executors.newFixedThreadPool(PARALLEL_REQUESTS)
        try {
            val futures = executor.invokeAll(unknown.map { hit -> Callable { archive.verify(hit) } })
            val verdicts = ArrayList<Verdict>()
            var failures = 0
            var failure: Throwable? = null
            for (future in futures) {
                try {
                    verdicts.add(future.get())
                } catch (e: ExecutionException) {
                    failures++
                    failure = e.cause ?: e
                }
            }
            if (verdicts.isEmpty() && failures > 0) {
                throw IOException("Could not check the films", failure)
            }
            return verdicts
        } finally {
            executor.shutdown()
        }
    }

    /** Merges audit results into the stored library. */
    @Synchronized
    private fun remember(verdicts: List<Verdict>, refreshed: Boolean): Int {
        val old = cache()
        if (verdicts.isEmpty() && !refreshed) {
            return 0
        }
        val accepted = verdicts.filterIsInstance<Verdict.Accepted>().map { it.film }
        val acceptedKeys = accepted.mapTo(HashSet()) { it.key }
        val rejected = verdicts.filterIsInstance<Verdict.Rejected>()
        val films = (old.films.filterNot { it.key in acceptedKeys } + accepted).takeLast(MAX_FILMS)
        val rejections = LinkedHashMap(old.rejected)
        for (verdict in rejected) {
            // only Internet Archive candidates are fetched one by one and worth remembering
            if (verdict.reason != Rejection.UNAVAILABLE && verdict.key.startsWith(ARCHIVE_PREFIX)) {
                rejections[verdict.key] = verdict.reason
            }
        }
        while (rejections.size > MAX_REJECTIONS) {
            rejections.remove(rejections.keys.first())
        }
        store.write(
            NAME,
            FilmLibraryCache.serializer(),
            FilmLibraryCache(if (refreshed) clock() else old.refreshedAt, films, rejections)
        )
        return accepted.count { film -> old.films.none { it.key == film.key } }
    }

    companion object {
        private const val NAME = "film_library"
        const val STALE_AFTER_MILLIS = 7 * 24 * 60 * 60 * 1000L
        private const val MAX_FILMS = 2000
        private const val MAX_REJECTIONS = 4000
        private const val PARALLEL_REQUESTS = 4
        private const val EXHAUSTED = -1

        private val ARCHIVE_PREFIX = FilmSource.INTERNET_ARCHIVE.name + ":"

        /**
         * Orders films for browsing: the most downloaded first, with the two sources taking
         * turns, and without second copies of the same film (same title and year).
         */
        fun arrange(films: Collection<Film>): List<Film> {
            val bySource = FilmSource.entries.map { source ->
                films.filter { it.source == source }.sortedByDescending { it.popularity }
            }
            val mixed = ArrayList<Film>(films.size)
            val positions = IntArray(bySource.size)
            while (mixed.size < films.size) {
                bySource.forEachIndexed { index, list ->
                    // two from the Internet Archive (by far the larger source) for one from Commons
                    val take = if (FilmSource.entries[index] == FilmSource.INTERNET_ARCHIVE) 2 else 1
                    repeat(take) {
                        list.getOrNull(positions[index])?.let(mixed::add)
                        positions[index]++
                    }
                }
            }
            return mixed.distinctBy { film ->
                if (film.year != null) film.title.lowercase(Locale.ROOT) + "|" + film.year else film.key
            }
        }
    }
}
