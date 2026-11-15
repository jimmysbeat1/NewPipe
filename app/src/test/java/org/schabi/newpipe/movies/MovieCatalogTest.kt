/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieCatalogTest {
    private val popcornflix = "https://www.youtube.com/channel/UCVFYikepF-avelvuIaQ_lHA"
    private val stranger = "https://www.youtube.com/channel/UC0000000000000000000000"

    private fun movie(
        title: String,
        channelUrl: String? = popcornflix,
        duration: Long = 90 * 60,
        upload: Long? = null
    ) = MovieCatalog.toMovie(0, "https://youtu.be/" + title.hashCode(), title, "Uploader", channelUrl, duration, 10, upload, null)

    @Test
    fun `titles as published by the distributors are cleaned up`() {
        val expected = mapOf(
            "A Birder's Guide To Everything | FULL MOVIE | Comedy | Kodi Smit-McPhee" to "A Birder's Guide To Everything",
            "Elephant Boy (1937) [Adventure] [Drama] [Family]" to "Elephant Boy",
            "The Curse | HORROR | Full Movie in English💎" to "The Curse",
            "Wasteland Cop FULL MOVIE | Action Movies | The Midnight Screening" to "Wasteland Cop",
            "QUEEN COBRA - Free Adventure Movie | Hollywood Snake Action" to "Queen Cobra",
            "Death Wheel 🔄 Four Friends Must Break a Deadly Time Loop | FULL HORROR MOVIE" to "Death Wheel",
            "Yu-Gi-Oh! The Movie: Pyramid of Light | Official Full Movie [English Dub]" to "Yu-Gi-Oh! The Movie: Pyramid of Light",
            "Morvern Callar | Full Movie" to "Morvern Callar",
            "The Four of the Apocalypse | 4K | Fabio Testi | Action | Spaghetti Western" to "The Four of the Apocalypse",
            "NEW HD RESTORATION🍕The Beast of Yucca Flats (1961) Tor Johnson, RADIOACTIVE CINEMA🍕" to "The Beast of Yucca Flats",
            "¡ENGAÑOS Y SECRETOS! ♠️ Cartas marcadas (1948) [Película completa a color]" to "Cartas marcadas",
            "[4K] 최후의 증인 (1980) / The Last Witness (Choehu-ui jeung-in)" to "최후의 증인",
            "Charade" to "Charade"
        )
        for ((raw, clean) in expected) {
            assertEquals(raw, clean, MovieCatalog.cleanTitle(raw))
        }
    }

    @Test
    fun `cleaning never returns an empty title`() {
        assertEquals("| Full Movie |", MovieCatalog.cleanTitle(" | Full Movie | "))
        assertEquals("🎬", MovieCatalog.cleanTitle("🎬"))
    }

    @Test
    fun `year is read from brackets only`() {
        assertEquals(1937, MovieCatalog.year("Elephant Boy (1937) [Adventure]"))
        assertEquals(1968, MovieCatalog.year("Night of the Living Dead [1968]"))
        assertNull(MovieCatalog.year("2001 A Space Odyssey explained"))
        assertNull(MovieCatalog.year("Blade Runner 2049"))
    }

    @Test
    fun `genres come from title keywords the channel and the year`() {
        assertEquals(
            setOf(Genre.ACTION, Genre.WESTERN),
            MovieCatalog.genres("The Four of the Apocalypse | Action | Spaghetti Western", null)
        )
        assertEquals(
            setOf(Genre.ADVENTURE, Genre.CLASSICS, Genre.DRAMA, Genre.FAMILY),
            MovieCatalog.genres("Elephant Boy (1937) [Adventure] [Drama] [Family]", null)
        )
        val horrorChannel = MovieCatalog.DISTRIBUTORS.first { it.name == "Kings of Horror" }
        assertEquals(setOf(Genre.HORROR), MovieCatalog.genres("Death Wheel", horrorChannel))
        // keywords must match whole words: "warning" is not "war", "dramatic" is not "drama"
        assertTrue(MovieCatalog.genres("A dramatic warning", null).isEmpty())
        assertEquals(setOf(Genre.SCI_FI), MovieCatalog.genres("Moon Base | Sci-Fi", null))
    }

    @Test
    fun `only full length uploads become movies`() {
        assertNull(movie("Official trailer", duration = 150))
        assertNull(movie("Short film", duration = 39 * 60))
        assertNotNull(movie("Feature", duration = 40 * 60))
    }

    @Test
    fun `trust is decided by channel id not by name`() {
        val trusted = movie("Feature")!!
        assertTrue(trusted.trusted)
        assertEquals("Popcornflix", trusted.channel)
        assertFalse(trusted.officialOnly)

        val impostor = MovieCatalog.toMovie(0, "u", "Feature | Full Movie", "Popcornflix", stranger, 6000, 1, null, null)!!
        assertFalse(impostor.trusted)
        assertFalse(movie("Feature", channelUrl = null)!!.trusted)
    }

    @Test
    fun `youtube movies channel is official playback only regardless of length`() {
        val url = "https://www.youtube.com/channel/" + MovieCatalog.YOUTUBE_MOVIES_CHANNEL_ID
        val paid = movie("Some Blockbuster", channelUrl = url, duration = 0)!!
        assertTrue(paid.officialOnly)
        assertTrue(paid.trusted)
    }

    @Test
    fun `distributor ids are well formed and unique`() {
        val ids = MovieCatalog.DISTRIBUTORS.map { it.channelId }
        assertEquals(ids.size, ids.toSet().size)
        for (id in ids) {
            assertEquals(id, MovieCatalog.channelId("https://www.youtube.com/channel/$id"))
        }
        assertNull(MovieCatalog.channelId("https://www.youtube.com/@Popcornflix"))
    }

    @Test
    fun `search matches all words in title or channel`() {
        val movies = listOfNotNull(
            movie("Night of the Living Dead (1968) | Horror"),
            movie("Dead Calm | Thriller"),
            movie("Living Large | Comedy")
        )
        assertEquals(listOf("Night of the Living Dead"), MovieCatalog.search(movies, "living DEAD").map { it.title })
        assertEquals(3, MovieCatalog.search(movies, "popcornflix").size)
        assertTrue(MovieCatalog.search(movies, "  ").isEmpty())
        assertTrue(MovieCatalog.search(movies, "nothing").isEmpty())
    }

    @Test
    fun `arrange lets channels take turns newest first`() {
        val a = "https://www.youtube.com/channel/UCVFYikepF-avelvuIaQ_lHA"
        val b = "https://www.youtube.com/channel/UC8IHAQMuiJdY6ALuhG7iU8Q"
        val movies = listOfNotNull(
            movie("A old", a, upload = 1),
            movie("A new", a, upload = 3),
            movie("A mid", a, upload = 2),
            movie("B only", b, upload = 1)
        )
        assertEquals(
            listOf("B only", "A new", "A mid", "A old"),
            MovieCatalog.arrange(movies).map { it.title }
        )
    }
}
