/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.live

import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.schabi.newpipe.movies.Genre
import org.schabi.newpipe.movies.library.CommonsSource
import org.schabi.newpipe.movies.library.Film
import org.schabi.newpipe.movies.library.FilmDownloader
import org.schabi.newpipe.movies.library.FilmLibraryRepository
import org.schabi.newpipe.movies.library.FilmNetwork
import org.schabi.newpipe.movies.library.FilmRules
import org.schabi.newpipe.movies.library.FilmSource
import org.schabi.newpipe.movies.library.InternetArchiveSource
import org.schabi.newpipe.movies.library.LicenceAudit
import org.schabi.newpipe.movies.library.Verdict
import org.schabi.newpipe.recommend.data.PersonalStore

/**
 * End-to-end tests of the film library against the real Internet Archive and Wikimedia Commons,
 * including real downloads. They need network access and depend on what the sources return at
 * the time, so they only run when `PIPEHOME_LIVE_TESTS=1` is set:
 *
 * `PIPEHOME_LIVE_TESTS=1 ./gradlew :app:testDebugUnitTest --tests "*FilmSourcesLiveTest*"`
 *
 * With `PIPEHOME_LIVE_REPORT` set to a file name, a summary of what was found is written there.
 */
class FilmSourcesLiveTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val archive = InternetArchiveSource(FilmNetwork.http)
    private val commons = CommonsSource(FilmNetwork.http)
    private val downloader = FilmDownloader(FilmNetwork.client, FilmNetwork.USER_AGENT)

    @Before
    fun setUp() {
        Assume.assumeTrue(System.getenv("PIPEHOME_LIVE_TESTS") == "1")
    }

    private fun report(line: String) {
        println("LIVE $line")
        System.getenv("PIPEHOME_LIVE_REPORT")?.takeIf { it.isNotBlank() }?.let { File(it).appendText(line + "\n") }
    }

    private fun describe(film: Film): String {
        val files = film.files.joinToString(", ") { it.label + " " + it.container + " " + it.sizeBytes / 1_000_000 + "MB" }
        return "${film.key} | ${film.title} (${film.year}) | ${film.durationSeconds / 60} min | " +
            "${film.licence.name} [${film.licence.evidence}] | ${film.genres} | $files"
    }

    /** Every film the library shows has to satisfy this, whatever the source returned. */
    private fun assertQualifies(film: Film) {
        assertTrue(film.key, FilmRules.isFullLength(film.durationSeconds))
        assertTrue(film.key, film.title.isNotBlank())
        assertTrue(film.key, film.files.isNotEmpty())
        assertTrue(film.key, film.files.all { it.url.startsWith("https://") })
        assertTrue(film.key, film.pageUrl.startsWith("https://"))
        assertNull(film.key, FilmRules.rejectByText(film.title, emptyList()))
        if (film.source == FilmSource.INTERNET_ARCHIVE) {
            assertNotNull(film.key, LicenceAudit.fromUrl(film.licence.evidence))
            assertTrue(film.key, film.files.all { it.url.startsWith("https://archive.org/download/") })
        } else {
            assertTrue(film.key, film.licence.evidence.isNotBlank())
            assertTrue(film.key, film.files.all { it.url.startsWith("https://upload.wikimedia.org/") })
        }
    }

    @Test
    fun `internet archive returns licensed feature films and the audit filters the rest`() {
        val hits = archive.search(null, null, 1)
        assertEquals(InternetArchiveSource.PAGE_SIZE, hits.size)
        val verdicts = hits.map(archive::verify)
        val films = verdicts.filterIsInstance<Verdict.Accepted>().map { it.film }
        val rejected = verdicts.filterIsInstance<Verdict.Rejected>()
        report("ARCHIVE page 1: ${hits.size} candidates, ${films.size} accepted, rejected: " + rejected.groupingBy { it.reason }.eachCount())
        films.forEach { report("ARCHIVE accepted: " + describe(it)) }
        rejected.forEach { report("ARCHIVE rejected: ${it.key} ${it.reason}") }
        assertTrue(films.size >= 10)
        films.forEach(::assertQualifies)
        assertTrue(films.any { it.files.any { file -> file.sha1 != null } })
        assertTrue(films.any { it.genres.isNotEmpty() })

        val comedies = archive.search(null, Genre.COMEDY, 1)
        assertTrue(comedies.size >= 10)
        val found = archive.search("general keaton", null, 1)
        report("ARCHIVE search 'general keaton': " + found.map { it.identifier })
        assertTrue(found.isNotEmpty())
    }

    @Test
    fun `wikimedia commons returns free feature films`() {
        val first = commons.search(null, 0)
        val second = commons.search(null, first.nextOffset ?: CommonsSource.PAGE_SIZE)
        val verdicts = first.verdicts + second.verdicts
        val films = verdicts.filterIsInstance<Verdict.Accepted>().map { it.film }
        val rejected = verdicts.filterIsInstance<Verdict.Rejected>()
        report("COMMONS 2 pages: ${verdicts.size} candidates, ${films.size} accepted, rejected: " + rejected.groupingBy { it.reason }.eachCount())
        films.forEach { report("COMMONS accepted: " + describe(it)) }
        assertNotNull(first.nextOffset)
        assertEquals(CommonsSource.PAGE_SIZE, first.verdicts.size)
        assertTrue(films.size >= 3)
        films.forEach(::assertQualifies)

        val found = commons.search("nosferatu", 0).verdicts.filterIsInstance<Verdict.Accepted>().map { it.film }
        report("COMMONS search 'nosferatu': " + found.map { it.title + " " + it.year })
        assertTrue(found.any { it.title.contains("Nosferatu", true) })

        // every film can be audited again by itself, as is done before a download
        val again = commons.verify(films.first().id)
        assertEquals(films.first().key, (again as Verdict.Accepted).film.key)
    }

    @Test
    fun `posters load with the image loader's plain requests`() {
        val film = archive.search(null, null, 1).asSequence().map(archive::verify)
            .filterIsInstance<Verdict.Accepted>().first().film
        val other = commons.search(null, 0).verdicts.filterIsInstance<Verdict.Accepted>().first().film
        val client = OkHttpClient()
        for (poster in listOf(film.posterUrl, other.posterUrl)) {
            client.newCall(Request.Builder().url(poster!!).build()).execute().use { response ->
                report("POSTER ${response.code} ${response.header("Content-Type")} $poster")
                assertTrue(response.isSuccessful)
                assertTrue(response.header("Content-Type").orEmpty().startsWith("image/"))
            }
        }
    }

    @Test
    fun `a film downloads from the internet archive, resumes and matches its checksum`() {
        // the smallest verified file among the most popular films keeps the test short
        val candidates = (1..2).flatMap { archive.search(null, null, it) }
            .asSequence()
            .map(archive::verify)
            .filterIsInstance<Verdict.Accepted>()
            .map { it.film }
            .take(25)
            .toList()
        val (film, file) = candidates
            .flatMap { film -> film.files.filter { it.sha1 != null }.map { film to it } }
            .minByOrNull { it.second.sizeBytes }!!
        report("DOWNLOAD archive: ${film.key} ${file.fileName} ${file.sizeBytes} bytes, sha1 ${file.sha1}")
        assertTrue("smallest file is too large for a test: ${file.sizeBytes}", file.sizeBytes < 700_000_000)

        val target = File(folder.root, "film." + file.container.lowercase())
        var seen = 0L
        // interrupt the download after a few megabytes ...
        assertThrows(FilmDownloader.Cancelled::class.java) {
            downloader.download(file.url, target, file.sha1, { seen > 4_000_000 }) { done, _ -> seen = done }
        }
        val partial = FilmDownloader.partFile(target).length()
        report("DOWNLOAD archive: interrupted after $partial bytes")
        assertTrue(partial in 1 until file.sizeBytes)
        assertFalse(target.exists())

        // ... and continue it: the result must be complete and match the published checksum
        val started = System.currentTimeMillis()
        var total = -1L
        val size = downloader.download(file.url, target, file.sha1, { false }) { _, all -> total = all }
        val seconds = (System.currentTimeMillis() - started) / 1000.0
        report("DOWNLOAD archive: resumed and finished, $size bytes in $seconds s, checksum verified")
        assertEquals(partial, downloader.resumedFrom)
        assertEquals(file.sizeBytes, size)
        assertEquals(file.sizeBytes, total)
        assertEquals(file.sha1, FilmDownloader.sha1(target))
    }

    @Test
    fun `a transcoded film downloads from wikimedia commons and resumes`() {
        val films = commons.search(null, 0).verdicts.filterIsInstance<Verdict.Accepted>().map { it.film }
        val (film, file) = films
            .flatMap { film -> film.files.filter { !it.original && it.sizeBytes > 0 }.map { film to it } }
            .minByOrNull { it.second.sizeBytes }!!
        report("DOWNLOAD commons: ${film.key} ${file.fileName} about ${file.sizeBytes} bytes")

        val target = File(folder.root, "film.webm")
        var seen = 0L
        assertThrows(FilmDownloader.Cancelled::class.java) {
            downloader.download(file.url, target, null, { seen > 3_000_000 }) { done, _ -> seen = done }
        }
        val partial = FilmDownloader.partFile(target).length()
        assertTrue(partial > 0)

        var total = -1L
        val size = downloader.download(file.url, target, null, { false }) { _, all -> total = all }
        report(
            "DOWNLOAD commons: interrupted after $partial bytes, continued from byte " +
                "${downloader.resumedFrom} and finished with $size bytes"
        )
        assertEquals(total, size)
        assertTrue(size > partial)
        // WebM files start with the EBML magic number
        val magic = target.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
        assertEquals(listOf(0x1A, 0x45, 0xDF, 0xA3), magic.map { it.toInt() and 0xFF })
    }

    @Test
    fun `the repository builds a library from both sources`() {
        val repository = FilmLibraryRepository(PersonalStore(folder.newFolder()), archive, commons)
        val outcome = repository.refresh()
        val cache = repository.cache()
        report("LIBRARY first refresh: ${outcome.added} films, ${cache.rejected.size} rejected, error ${outcome.error}")
        assertTrue(cache.films.count { it.source == FilmSource.INTERNET_ARCHIVE } >= 10)
        assertTrue(cache.films.any { it.source == FilmSource.WIKIMEDIA_COMMONS })
        cache.films.forEach(::assertQualifies)
        report("LIBRARY genres: " + cache.films.flatMap { it.genres }.groupingBy { it }.eachCount())

        val (results, error) = repository.search("chaplin")
        report("LIBRARY search 'chaplin': " + results.map { it.title + " " + it.year + " " + it.source })
        assertTrue(error == null && results.isNotEmpty())
        val film = cache.films.first()
        assertEquals(film.key, repository.recheck(film)?.key)
    }
}
