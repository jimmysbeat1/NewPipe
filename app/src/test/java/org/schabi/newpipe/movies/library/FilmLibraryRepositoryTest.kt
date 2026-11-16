/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.library

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.schabi.newpipe.movies.Genre
import org.schabi.newpipe.recommend.data.PersonalStore

class FilmLibraryRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_800_000_000_000L
    private lateinit var archive: FakeArchive
    private lateinit var commons: FakeCommons
    private lateinit var repository: FilmLibraryRepository

    private fun film(source: FilmSource, id: String, title: String = id, year: Int? = null, popularity: Long = 0): Film {
        return Film(
            source = source,
            id = id,
            title = title,
            year = year,
            durationSeconds = 5000,
            genres = if (id.startsWith("comedy")) setOf(Genre.COMEDY) else emptySet(),
            pageUrl = "https://example.org/$id",
            licence = FilmLicence(LicenceKind.PUBLIC_DOMAIN, "Public domain"),
            files = listOf(FilmFile("https://example.org/$id.mp4", "$id.mp4", 480, "MP4", 1000)),
            popularity = popularity
        )
    }

    private inner class FakeArchive : InternetArchiveSource(FilmHttp { throw IOException("unused") }) {
        var offline = false
        val verified = ArrayList<String>()
        val withdrawn = HashSet<String>()
        val searches = ArrayList<String>()

        override fun search(text: String?, genre: Genre?, page: Int): List<Hit> {
            if (offline) {
                throw IOException("offline")
            }
            searches.add("$text/$genre/$page")
            return when {
                text != null -> listOf(Hit("found-$text", 5))
                genre != null -> listOf(Hit("comedy-$page", 1))
                page == 1 -> List(PAGE_SIZE) { Hit(if (it % 2 == 0) "good-$it" else "short-$it", 100L - it) }
                else -> listOf(Hit("good-page-$page", 1))
            }
        }

        override fun verify(hit: Hit): Verdict {
            synchronized(verified) { verified.add(hit.identifier) }
            return if (hit.identifier.startsWith("short") || hit.identifier in withdrawn) {
                Verdict.Rejected(key(hit.identifier), Rejection.NOT_FULL_LENGTH)
            } else {
                Verdict.Accepted(film(FilmSource.INTERNET_ARCHIVE, hit.identifier, popularity = hit.downloads))
            }
        }
    }

    private inner class FakeCommons : CommonsSource(FilmHttp { throw IOException("unused") }) {
        var offline = false

        override fun search(text: String?, offset: Int): Page {
            if (offline) {
                throw IOException("offline")
            }
            val accepted = Verdict.Accepted(film(FilmSource.WIKIMEDIA_COMMONS, "c-$offset-${text.orEmpty()}"))
            val rejected = Verdict.Rejected(key("99"), Rejection.NO_ACCEPTED_LICENCE)
            return Page(listOf(accepted, rejected), if (offset == 0) 30 else null)
        }

        override fun verify(pageId: String): Verdict = Verdict.Rejected(key(pageId), Rejection.DISPUTED)
    }

    @Before
    fun setUp() {
        archive = FakeArchive()
        commons = FakeCommons()
        repository = FilmLibraryRepository(PersonalStore(folder.newFolder()), archive, commons) { now }
    }

    @Test
    fun `refresh audits candidates and remembers accepted and rejected ones`() {
        assertTrue(repository.isStale())
        val outcome = repository.refresh()
        assertNull(outcome.error)
        assertEquals(21, outcome.added)

        val cache = repository.cache()
        assertEquals(now, cache.refreshedAt)
        assertFalse(repository.isStale())
        assertEquals(20, cache.films.count { it.source == FilmSource.INTERNET_ARCHIVE })
        assertEquals(1, cache.films.count { it.source == FilmSource.WIKIMEDIA_COMMONS })
        assertTrue(cache.films.none { it.id.startsWith("short") })
        assertEquals(20, cache.rejected.size)
        assertEquals(Rejection.NOT_FULL_LENGTH, cache.rejected["INTERNET_ARCHIVE:short-1"])

        // nothing that is already known gets fetched again
        archive.verified.clear()
        assertEquals(0, repository.refresh().added)
        assertTrue(archive.verified.isEmpty())
    }

    @Test
    fun `loads further pages and genres on demand`() {
        repository.refresh()
        assertTrue(repository.canLoadMore(null))
        assertEquals(2, repository.loadMore(null).added)
        assertTrue(repository.cache().films.any { it.id == "good-page-2" })
        assertTrue(repository.cache().films.any { it.id == "c-30-" })
        // the last pages were short: both sources are exhausted
        assertFalse(repository.canLoadMore(null))
        assertEquals(0, repository.loadMore(null).added)

        assertEquals(1, repository.loadMore(Genre.COMEDY).added)
        assertEquals(setOf(Genre.COMEDY), repository.cache().films.single { it.id == "comedy-1" }.genres)
        assertTrue("null/COMEDY/1" in archive.searches)
    }

    @Test
    fun `reports an error only when nothing could be loaded`() {
        commons.offline = true
        assertNull(repository.refresh().error)
        archive.offline = true
        val outcome = repository.refresh()
        assertNotNull(outcome.error)
        assertEquals(0, outcome.added)
        // the library is kept
        assertEquals(20, repository.cache().films.size)
    }

    @Test
    fun `search combines the library with both sources`() {
        repository.refresh()
        val (films, error) = repository.search("good-0")
        assertNull(error)
        assertEquals(
            setOf("good-0", "found-good-0", "c-0-good-0"),
            films.map { it.id }.toSet()
        )
        commons.offline = true
        val (partial, partialError) = repository.search("good-0")
        assertNotNull(partialError)
        assertEquals(setOf("good-0", "found-good-0"), partial.map { it.id }.toSet())
    }

    @Test
    fun `a film that fails the audit before a download is removed`() {
        repository.refresh()
        val film = repository.cache().films.first { it.id == "good-0" }
        assertEquals(film.key, repository.recheck(film)?.key)

        archive.withdrawn.add("good-0")
        assertNull(repository.recheck(film))
        assertTrue(repository.cache().films.none { it.id == "good-0" })
        assertEquals(Rejection.NOT_FULL_LENGTH, repository.cache().rejected[film.key])

        val fromCommons = repository.cache().films.first { it.source == FilmSource.WIKIMEDIA_COMMONS }
        assertNull(repository.recheck(fromCommons))
    }

    @Test
    fun `arranges by popularity, mixes the sources and drops second copies`() {
        val films = listOf(
            film(FilmSource.INTERNET_ARCHIVE, "a", "The General", 1926, 10),
            film(FilmSource.INTERNET_ARCHIVE, "b", "Charade", 1963, 30),
            film(FilmSource.INTERNET_ARCHIVE, "c", "the general", 1926, 20),
            film(FilmSource.INTERNET_ARCHIVE, "d", "Untitled", null, 5),
            film(FilmSource.WIKIMEDIA_COMMONS, "e", "The General", 1926),
            film(FilmSource.WIKIMEDIA_COMMONS, "f", "Nosferatu", 1922)
        )
        assertEquals(listOf("b", "c", "d", "f"), FilmLibraryRepository.arrange(films).map { it.id })
    }
}
