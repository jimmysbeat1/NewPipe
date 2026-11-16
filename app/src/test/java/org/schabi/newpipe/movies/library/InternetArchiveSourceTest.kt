/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.library

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.movies.Genre

/** The fixtures follow the structure of real responses of the metadata API. */
class InternetArchiveSourceTest {
    private fun file(
        name: String,
        source: String,
        length: String?,
        height: Int,
        size: Long,
        original: String? = null,
        extra: String = ""
    ): String {
        val fields = ArrayList<String>()
        fields.add("\"name\":\"$name\"")
        fields.add("\"source\":\"$source\"")
        fields.add("\"size\":\"$size\"")
        fields.add("\"height\":\"$height\"")
        fields.add("\"sha1\":\"c6c14e713ef96e8e78f7d1d5c74c8050f53166b6\"")
        length?.let { fields.add("\"length\":\"$it\"") }
        original?.let { fields.add("\"original\":\"$it\"") }
        return "{" + fields.joinToString(",") + extra + "}"
    }

    private fun item(
        files: List<String>,
        licence: String? = "http://creativecommons.org/licenses/publicdomain/",
        collections: String = "[\"Comedy_Films\",\"feature_films\",\"moviesandfilms\"]",
        title: String = "Sex Madness",
        subject: String = "\"exploitation;melodrama\"",
        extra: String = ""
    ): String {
        val licenceField = licence?.let { "\"licenseurl\":\"$it\"," }.orEmpty()
        val thumbnail = "{\"name\":\"__ia_thumb.jpg\",\"source\":\"original\",\"size\":\"10287\"}"
        return """{"server":"dn800304.us.archive.org","dir":"/0/items/x","metadata":{"mediatype":"movies",
            "collection":$collections,"title":"$title","subject":$subject,"director":"William Curran",
            "date":"1938",$licenceField"description":"A <b>cautionary</b> tale.","identifier":"x"$extra},
            "files":[${(listOf(thumbnail) + files).joinToString(",")}]}"""
    }

    private val feature = listOf(
        file("sex_madness.mp4", "derivative", "3117.52", 480, 324928028, "sex_madness.mpeg"),
        file("sex_madness.mpeg", "original", "3117.48", 720, 2054154246),
        file("sex_madness.ogv", "derivative", "3117.39", 304, 223016577, "sex_madness.mpeg")
    )

    private fun accepted(body: String): Film {
        val verdict = InternetArchiveSource.parseItem("sex_madness", body, 42)
        assertTrue("rejected: " + (verdict as? Verdict.Rejected)?.reason, verdict is Verdict.Accepted)
        return (verdict as Verdict.Accepted).film
    }

    private fun rejection(body: String): Rejection {
        val verdict = InternetArchiveSource.parseItem("x", body)
        assertTrue("accepted", verdict is Verdict.Rejected)
        assertEquals("INTERNET_ARCHIVE:x", (verdict as Verdict.Rejected).key)
        return verdict.reason
    }

    @Test
    fun `accepts a licensed feature film and offers only the files android can play`() {
        val film = accepted(item(feature))
        assertEquals("INTERNET_ARCHIVE:sex_madness", film.key)
        assertEquals("Sex Madness", film.title)
        assertEquals(1938, film.year)
        assertEquals(3117L, film.durationSeconds)
        assertEquals(LicenceKind.PUBLIC_DOMAIN, film.licence.kind)
        assertEquals("http://creativecommons.org/licenses/publicdomain/", film.licence.evidence)
        assertEquals("William Curran", film.creator)
        assertEquals("A cautionary tale.", film.description)
        assertEquals("https://archive.org/details/sex_madness", film.pageUrl)
        assertEquals("https://archive.org/services/img/sex_madness", film.posterUrl)
        assertEquals(42L, film.popularity)
        assertEquals(setOf(Genre.CLASSICS, Genre.COMEDY), film.genres)

        // the MPEG-2 original and the Ogg Theora derivative cannot be played by Android
        val file = film.files.single()
        assertEquals("https://archive.org/download/sex_madness/sex_madness.mp4", file.url)
        assertEquals("480p", file.label)
        assertEquals("MP4", file.container)
        assertEquals(324928028L, file.sizeBytes)
        assertEquals("c6c14e713ef96e8e78f7d1d5c74c8050f53166b6", file.sha1)
        assertFalse(file.original)
    }

    @Test
    fun `lists every playable quality, best first`() {
        val files = listOf(
            file("film.mkv", "original", "5400", 1080, 4_000_000_000),
            file("film.mp4", "derivative", "5400.1", 480, 600_000_000, "film.mkv"),
            file("film_512kb.mp4", "derivative", "5400.1", 240, 300_000_000, "film.mkv"),
            file("film name #1.ia.mp4", "derivative", "5400.1", 360, 400_000_000, "film.mkv")
        )
        val film = accepted(item(files))
        assertEquals(listOf("1080p (original)", "480p", "360p", "240p"), film.files.map { it.label })
        assertEquals("MKV", film.files[0].container)
        assertEquals(
            "https://archive.org/download/sex_madness/film%20name%20%231.ia.mp4",
            film.files[2].url
        )
    }

    @Test
    fun `rejects what is not a full-length film with a download licence`() {
        assertEquals(Rejection.NO_ACCEPTED_LICENCE, rejection(item(feature, licence = null)))
        assertEquals(
            Rejection.NO_ACCEPTED_LICENCE,
            rejection(item(feature, licence = "https://rightsstatements.org/vocab/InC/1.0/"))
        )
        // real case: a 20 minute Chaplin short filed under feature films
        val short = listOf(file("job.mp4", "derivative", "1233.2", 480, 90_000_000, "job.avi"))
        assertEquals(Rejection.NOT_FULL_LENGTH, rejection(item(short)))
        assertEquals(Rejection.NOT_A_MOVIE, rejection(item(emptyList())))
        assertEquals(Rejection.NOT_A_MOVIE, rejection(item(feature, collections = "[\"opensource_movies\"]")))
        assertEquals(
            Rejection.TV_OR_SERIAL,
            rejection(item(feature, collections = "[\"feature_films\",\"classic_tv\"]"))
        )
        assertEquals(Rejection.TRAILER_OR_EXCERPT, rejection(item(feature, title = "Sex Madness (trailer)")))
        assertEquals(Rejection.ADULT, rejection(item(feature, subject = "[\"Adult film\",\"1970s\"]")))
        assertEquals(
            Rejection.ACCESS_RESTRICTED,
            rejection(item(feature, extra = ",\"access-restricted-item\":\"true\""))
        )
        val unplayable = listOf(file("film.mpeg", "original", "5000", 480, 2_000_000_000))
        assertEquals(Rejection.NO_PLAYABLE_FILE, rejection(item(unplayable)))
        val private = listOf(file("film.mp4", "original", "5000", 480, 500_000_000, extra = ",\"private\":\"true\""))
        assertEquals(Rejection.NO_PLAYABLE_FILE, rejection(item(private)))
    }

    @Test
    fun `rejects bundles of several videos but accepts alternative versions`() {
        val episodes = listOf(
            file("ep_one.mp4", "original", "2700", 480, 300_000_000),
            file("ep_two.mp4", "original", "3100", 480, 300_000_000),
            file("ep_three.mp4", "original", "2500", 480, 300_000_000)
        )
        assertEquals(Rejection.SEVERAL_FILMS, rejection(item(episodes)))

        val versions = listOf(
            file("film_hd.mp4", "original", "5400", 720, 900_000_000),
            file("film_sd.mp4", "original", "5398", 480, 500_000_000),
            file("intro.mp4", "original", "45", 480, 3_000_000)
        )
        assertEquals(1, accepted(item(versions)).files.size)
    }

    @Test
    fun `falls back to the stated runtime when files carry no length`() {
        val files = listOf(file("film.mp4", "original", null, 480, 500_000_000))
        assertEquals(4724L, accepted(item(files, extra = ",\"runtime\":\"1:18:44\"")).durationSeconds)
        assertEquals(Rejection.NOT_FULL_LENGTH, rejection(item(files)))
    }

    @Test
    fun `withdrawn items are unavailable`() {
        val dark = """{"created":1790185490,"d1":"ia601606.us.archive.org","is_dark":true,"server":"x"}"""
        assertEquals(Rejection.UNAVAILABLE, rejection(dark))
        assertEquals(Rejection.UNAVAILABLE, rejection("{}"))
        assertThrows(IOException::class.java) { InternetArchiveSource.parseItem("x", "<html>busy</html>") }
    }

    @Test
    fun `search asks only for licensed feature films`() {
        val plain = InternetArchiveSource.query(null, null)
        assertTrue(plain.startsWith("mediatype:movies AND collection:feature_films"))
        assertTrue("licenseurl:http*creativecommons.org*" in plain)
        assertTrue("-collection:(short_films OR classic_tv" in plain)

        val query = InternetArchiveSource.query("the General) OR x", Genre.SCI_FI)
        assertTrue("title:(the AND General)" in query)
        assertTrue("subject:(\"sci-fi\" OR \"scifi\" OR \"science fiction\"" in query)
        assertTrue("OR collection:(SciFi_Horror OR film_scifi)" in query)

        val url = InternetArchiveSource.searchUrl(null, null, 3)
        assertTrue(url.startsWith("https://archive.org/advancedsearch.php?q=mediatype"))
        assertTrue("page=3" in url && "rows=40" in url && "output=json" in url)
    }

    @Test
    fun `reads search results`() {
        val body = """{"responseHeader":{"status":0},"response":{"numFound":9133,"start":0,"docs":[
            {"identifier":"The_General_Buster_Keaton","downloads":273360},
            {"identifier":"TheGeneral"},{"downloads":5}]}}"""
        val hits = InternetArchiveSource.parseSearch(body)
        assertEquals(listOf("The_General_Buster_Keaton", "TheGeneral"), hits.map { it.identifier })
        assertEquals(listOf(273360L, 0L), hits.map { it.downloads })
        assertThrows(IOException::class.java) { InternetArchiveSource.parseSearch("{\"error\":\"x\"}") }
    }
}
