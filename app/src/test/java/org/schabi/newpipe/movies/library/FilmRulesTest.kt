/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.movies.Genre

class FilmRulesTest {
    @Test
    fun `parses the running times sources publish`() {
        assertEquals(3117L, FilmRules.parseDuration("3117.52"))
        assertEquals(4724L, FilmRules.parseDuration("1:18:44"))
        assertEquals(3600L, FilmRules.parseDuration("60:00"))
        assertEquals(1233L, FilmRules.parseDuration("20:33"))
        assertEquals(6316L, FilmRules.parseDuration("01:45:16"))
        assertEquals(4500L, FilmRules.parseDuration("75 min"))
        assertEquals(1233L, FilmRules.parseDuration("20 minutes 33 seconds"))
        assertEquals(4800L, FilmRules.parseDuration("1 h 20 min"))
        assertEquals(6368L, FilmRules.parseDuration("1:46.08"))
        assertNull(FilmRules.parseDuration(null))
        assertNull(FilmRules.parseDuration(""))
        assertNull(FilmRules.parseDuration("feature length"))
    }

    @Test
    fun `only more than forty minutes is full length`() {
        assertFalse(FilmRules.isFullLength(39 * 60 + 59))
        assertTrue(FilmRules.isFullLength(40 * 60))
        assertFalse(FilmRules.isFullLength(0))
    }

    @Test
    fun `rejects trailers, shorts, television and adult material by their labels`() {
        assertNull(FilmRules.rejectByText("The General (1926)", listOf("Comedy", "Silent", "War")))
        assertNull(FilmRules.rejectByText("Short Circuit Sally", listOf("drama")))
        assertNull(FilmRules.rejectByText("Total Eclipse", listOf("Drama films", "Videos of films from 1936")))

        assertEquals(Rejection.TRAILER_OR_EXCERPT, FilmRules.rejectByText("Charade - Trailer", emptyList()))
        assertEquals(Rejection.TRAILER_OR_EXCERPT, FilmRules.rejectByText("Charade", listOf("Film trailers in the public domain")))
        assertEquals(Rejection.TRAILER_OR_EXCERPT, FilmRules.rejectByText("Metropolis (clip)", emptyList()))
        assertEquals(Rejection.TV_OR_SERIAL, FilmRules.rejectByText("Dragnet S01E03 The Big Cast", emptyList()))
        assertEquals(Rejection.TV_OR_SERIAL, FilmRules.rejectByText("Flash Gordon Chapter 4", emptyList()))
        assertEquals(Rejection.TV_OR_SERIAL, FilmRules.rejectByText("The Phantom Empire Part 2", emptyList()))
        assertEquals(Rejection.TV_OR_SERIAL, FilmRules.rejectByText("Beverly Hillbillies", listOf("Television series")))
        assertEquals(Rejection.TV_OR_SERIAL, FilmRules.rejectByText("Zorro", listOf("Film serials in the public domain")))
        assertEquals(Rejection.NOT_FULL_LENGTH, FilmRules.rejectByText("The Bank (1915)", listOf("Short films videos")))
        assertEquals(Rejection.NOT_FULL_LENGTH, FilmRules.rejectByText("One Week", listOf("short")))
        assertEquals(Rejection.ADULT, FilmRules.rejectByText("Some Title", listOf("Pornographic films")))
        assertEquals(Rejection.ADULT, FilmRules.rejectByText("Some Title", listOf("Erotic films of the 1970s")))
        assertEquals(Rejection.ADULT, FilmRules.rejectByText("Diary of a Nudist", listOf("drama")))
    }

    @Test
    fun `derives genres from subjects, collections and categories`() {
        assertEquals(
            setOf(Genre.CLASSICS, Genre.COMEDY, Genre.ROMANCE, Genre.WAR),
            FilmRules.genres(listOf("Silent", "Comedy", "Romance", "War", "Buster Keaton"), 1926)
        )
        assertEquals(setOf(Genre.COMEDY), FilmRules.genres(listOf("Comedy_Films", "feature_films"), 1985))
        // a mixed collection does not say which of its genres a film has
        assertEquals(emptySet<Genre>(), FilmRules.genres(listOf("SciFi_Horror"), null))
        assertEquals(setOf(Genre.SCI_FI), FilmRules.genres(listOf("SciFi_Horror", "film_scifi", "sci-fi"), null))
        assertEquals(setOf(Genre.CRIME), FilmRules.genres(listOf("Film_Noir"), 1975))
        assertEquals(setOf(Genre.HORROR), FilmRules.genres(listOf("Horror films videos", "Videos of films from 1972"), 1972))
        assertEquals(emptySet<Genre>(), FilmRules.genres(listOf("moviesandfilms"), 2001))
    }

    @Test
    fun `cleans titles and finds years`() {
        assertEquals("The General", FilmRules.cleanTitle("The General (1926)"))
        assertEquals("Moby Dick", FilmRules.cleanTitle("Moby Dick (1930 film)"))
        assertEquals("7th Heaven", FilmRules.cleanTitle("7th_Heaven_(1927)"))
        assertEquals("Charade", FilmRules.cleanTitle("Charade [Full Movie]"))
        assertEquals("1984", FilmRules.cleanTitle("1984"))
        assertEquals(1926, FilmRules.year(null, "The General (1926)"))
        assertEquals(1938, FilmRules.year("1938", "Sex Madness"))
        assertNull(FilmRules.year("undated", null))
    }

    @Test
    fun `query words cannot change the meaning of a search expression`() {
        assertEquals(listOf("night", "living", "dead"), FilmRules.queryWords("night) OR (living \"dead\" a"))
        assertEquals(listOf("1931"), FilmRules.queryWords("M 1931"))
        assertEquals(emptyList<String>(), FilmRules.queryWords("  :*() "))
    }
}
