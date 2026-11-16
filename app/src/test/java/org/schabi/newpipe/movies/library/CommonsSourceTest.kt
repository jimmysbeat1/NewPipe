/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.library

import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.movies.Genre

/** The fixtures follow the structure of real `prop=videoinfo` responses. */
class CommonsSourceTest {
    private fun meta(vararg values: Pair<String, String>): String {
        return values.joinToString(",", "{", "}") { (key, value) -> "\"$key\":{\"value\":\"$value\"}" }
    }

    private val publicDomain = meta(
        "ObjectName" to "7th Heaven (1927)",
        "Categories" to "7th Heaven (1927 film)|Silent films videos|Videos of films from 1927|Drama films",
        "DateTimeOriginal" to "1927",
        "Artist" to "<bdi><a href='https://en.wikipedia.org/wiki/Frank_Borzage'>Frank Borzage</a></bdi>",
        "LicenseShortName" to "Public domain",
        "License" to "pd"
    )

    private fun page(
        id: Long,
        index: Int,
        duration: Double = 7119.896,
        metadata: String = publicDomain,
        mime: String = "video/webm",
        info: Boolean = true
    ): String {
        val base = "https://upload.wikimedia.org/wikipedia/commons"
        val videoinfo = ""","videoinfo":[{"size":5365577191,"width":1440,"height":1080,"duration":$duration,
            "thumburl":"https://thumb.wikimedia.org/x/500px--7th_Heaven.webm.jpg?utm_source=commons",
            "url":"$base/a/ab/7th_Heaven_%281927%29.webm?utm_source=commons&utm_content=original",
            "descriptionurl":"https://commons.wikimedia.org/wiki/File:7th_Heaven_(1927).webm",
            "sha1":"BD07215D2BE1C0B6CEE0B4ED54124C1397A72D78","mime":"$mime","mediatype":"VIDEO",
            "extmetadata":$metadata,"derivatives":[
            {"src":"$base/transcoded/a/ab/7th.webm/7th.webm.3x1080p.vp9.mpd","type":"application/dash+xml",
             "transcodekey":"3x1080p.vp9.mpd","width":1440,"height":1080,"bandwidth":-1},
            {"src":"$base/transcoded/a/ab/7th.webm/7th.webm.240p.vp9.webm","type":"video/webm; codecs=\"vp9, opus\"",
             "transcodekey":"240p.vp9.webm","width":320,"height":240,"bandwidth":264560},
            {"src":"$base/transcoded/a/ab/7th.webm/7th.webm.360p.mpeg4.mov","type":"video/quicktime; codecs=\"mp4v\"",
             "transcodekey":"360p.mpeg4.mov","width":480,"height":360,"bandwidth":1636936},
            {"src":"$base/transcoded/a/ab/7th.webm/7th.webm.1080p.vp9.webm","type":"video/webm; codecs=\"vp9, opus\"",
             "transcodekey":"1080p.vp9.webm","width":1440,"height":1080,"bandwidth":3788064}]}]"""
        return """{"pageid":$id,"ns":6,"title":"File:7th Heaven (1927).webm","index":$index${if (info) videoinfo else ""}}"""
    }

    private fun audit(page: String): Verdict {
        val http = FilmHttp { """{"batchcomplete":true,"query":{"pages":[$page]}}""" }
        return CommonsSource(http).verify("1")
    }

    private fun rejection(page: String): Rejection = (audit(page) as Verdict.Rejected).reason

    @Test
    fun `accepts a public domain feature and lists its renditions`() {
        val film = (audit(page(153, 1)) as Verdict.Accepted).film
        assertEquals("WIKIMEDIA_COMMONS:153", film.key)
        assertEquals("7th Heaven", film.title)
        assertEquals(1927, film.year)
        assertEquals(7119L, film.durationSeconds)
        assertEquals(LicenceKind.PUBLIC_DOMAIN, film.licence.kind)
        assertEquals("Public domain", film.licence.name)
        assertNull(film.licence.url)
        assertEquals("Frank Borzage", film.creator)
        assertEquals(setOf(Genre.CLASSICS, Genre.DRAMA), film.genres)
        assertEquals("https://commons.wikimedia.org/wiki/File:7th_Heaven_(1927).webm", film.pageUrl)
        assertEquals("https://thumb.wikimedia.org/x/500px--7th_Heaven.webm.jpg", film.posterUrl)

        // the original wins over the transcode of the same height; DASH and MPEG-4 are skipped
        assertEquals(listOf("1080p (original)", "240p"), film.files.map { it.label })
        val original = film.files[0]
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/a/ab/7th_Heaven_%281927%29.webm", original.url)
        assertEquals("7th_Heaven_(1927).webm", original.fileName)
        assertEquals(5365577191L, original.sizeBytes)
        assertEquals("bd07215d2be1c0b6cee0b4ed54124c1397a72d78", original.sha1)
        val small = film.files[1]
        assertTrue(small.sizeEstimated)
        assertEquals(264560L * 7119 / 8, small.sizeBytes)
        assertNull(small.sha1)
    }

    @Test
    fun `offers only transcodes when the original is not playable`() {
        val film = (audit(page(1, 1, mime = "application/ogg")) as Verdict.Accepted).film
        assertEquals(listOf("1080p", "240p"), film.files.map { it.label })
    }

    @Test
    fun `rejects shorts, unfree, disputed and unsuitable files`() {
        // Big Buck Bunny: free, but ten minutes long
        assertEquals(Rejection.NOT_FULL_LENGTH, rejection(page(1, 1, duration = 634.553)))
        val gfdl = meta("License" to "gfdl", "LicenseShortName" to "GFDL", "Categories" to "Films")
        assertEquals(Rejection.NO_ACCEPTED_LICENCE, rejection(page(1, 1, metadata = gfdl)))
        assertEquals(Rejection.NO_ACCEPTED_LICENCE, rejection(page(1, 1, metadata = meta("Categories" to "Films"))))
        val disputed = meta("License" to "pd", "Categories" to "Films|Deletion requests March 2026")
        assertEquals(Rejection.DISPUTED, rejection(page(1, 1, metadata = disputed)))
        val unreviewed = meta("License" to "cc-by-3.0", "Categories" to "License review needed (video)")
        assertEquals(Rejection.DISPUTED, rejection(page(1, 1, metadata = unreviewed)))
        val trailer = meta("License" to "pd", "Categories" to "Film trailer videos")
        assertEquals(Rejection.TRAILER_OR_EXCERPT, rejection(page(1, 1, metadata = trailer)))
        val episode = meta("License" to "pd", "ObjectName" to "Dragnet Episode 12", "Categories" to "x")
        assertEquals(Rejection.TV_OR_SERIAL, rejection(page(1, 1, metadata = episode)))
        assertEquals(Rejection.UNAVAILABLE, rejection(page(1, 1, info = false)))
    }

    @Test
    fun `search restricts to large videos in the film category tree`() {
        val url = CommonsSource.searchUrl("night) OR x", 30).toHttpUrl()
        assertEquals("commons.wikimedia.org", url.host)
        assertEquals(
            "filetype:video filesize:>100000 deepcat:\"Videos of films by decade\" night",
            url.queryParameter("gsrsearch")
        )
        assertEquals("30", url.queryParameter("gsroffset"))
        assertEquals("6", url.queryParameter("gsrnamespace"))
        assertEquals("videoinfo", url.queryParameter("prop"))
        assertTrue(url.queryParameter("viprop")!!.contains("extmetadata"))
    }

    @Test
    fun `follows batched responses and reports the next page`() {
        val requests = ArrayList<String>()
        val http = FilmHttp { url ->
            requests.add(url)
            if (requests.size == 1) {
                // the first batch only carries the details of one of the two files
                """{"continue":{"vistart":"2023-01-10T19:21:12Z","continue":"||"},
                    "query":{"pages":[${page(2, 2, info = false)},${page(1, 1)}]}}"""
            } else {
                """{"batchcomplete":true,"continue":{"gsroffset":30,"continue":"gsroffset||"},
                    "query":{"pages":[${page(2, 2, duration = 100.0)},${page(1, 1, info = false)}]}}"""
            }
        }
        val result = CommonsSource(http).search(null, 0)
        assertEquals(2, requests.size)
        assertEquals("2023-01-10T19:21:12Z", requests[1].toHttpUrl().queryParameter("vistart"))
        assertEquals("0", requests[1].toHttpUrl().queryParameter("gsroffset"))
        assertEquals(30, result.nextOffset)
        assertEquals(2, result.verdicts.size)
        assertEquals("WIKIMEDIA_COMMONS:1", (result.verdicts[0] as Verdict.Accepted).film.key)
        assertEquals(Rejection.NOT_FULL_LENGTH, (result.verdicts[1] as Verdict.Rejected).reason)
    }

    @Test
    fun `reports api errors`() {
        val http = FilmHttp { """{"error":{"code":"ratelimited","info":"Too many requests"}}""" }
        val error = assertThrows(IOException::class.java) { CommonsSource(http).search(null, 0) }
        assertTrue(error.message!!.contains("Too many requests"))
    }
}
