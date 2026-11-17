/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.discover

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverQueryTest {
    private val today = LocalDate.of(2026, 11, 17)

    private fun query(section: Section, filters: Filters = Filters()) = DiscoverQuery.build(section, filters, today)

    @Test
    fun everySectionAsksForReleasedFeatureFilmsThatCanBeWatchedInAustralia() {
        for (section in Section.entries.filter { it.online }) {
            val query = query(section)
            assertEquals(section.name, "AU", query["watch_region"])
            assertEquals(section.name, "AU", query["region"])
            assertEquals(section.name, "false", query["include_adult"])
            assertEquals(section.name, "2026-11-17", query["primary_release_date.lte"])
            assertEquals(section.name, "60", query["with_runtime.gte"])
            assertTrue(section.name, query.getValue("with_watch_monetization_types").startsWith("free|ads"))
        }
    }

    @Test
    fun byDefaultFilmsFrom2020OnwardsAreShown() {
        assertEquals("2020-01-01", query(Section.HOLLYWOOD)["primary_release_date.gte"])
        assertEquals("2020-01-01", query(Section.FREE)["primary_release_date.gte"])
        assertEquals("2020-01-01", query(Section.HORROR)["primary_release_date.gte"])
    }

    @Test
    fun theLatestYearsAreTheCurrentAndTheTwoBefore() {
        val filters = Filters(years = Years.LATEST)
        assertEquals("2024-01-01", query(Section.ACTION, filters)["primary_release_date.gte"])
        assertEquals(
            LocalDate.of(2025, 1, 1),
            DiscoverQuery.firstReleaseDate(Section.COMEDY, Years.LATEST, LocalDate.of(2027, 3, 1))
        )
        assertNull(query(Section.ACTION, Filters(years = Years.ANY))["primary_release_date.gte"])
    }

    @Test
    fun newReleasesCoverTheLastTwelveMonthsNewestFirstWhateverTheYearFilter() {
        for (years in Years.entries) {
            val query = query(Section.NEW_RELEASES, Filters(years = years))
            assertEquals("2025-11-17", query["primary_release_date.gte"])
            assertEquals("primary_release_date.desc", query["sort_by"])
        }
        assertEquals("popularity.desc", query(Section.NEW_RELEASES, Filters(sort = SortOrder.POPULAR))["sort_by"])
    }

    @Test
    fun freeMoviesOnlyEverAskForFreeOffers() {
        for (access in AccessFilter.entries) {
            assertEquals("free|ads", query(Section.FREE, Filters(access = access))["with_watch_monetization_types"])
        }
        assertEquals("free|ads", query(Section.ACTION, Filters(access = AccessFilter.FREE_ONLY))["with_watch_monetization_types"])
        assertEquals(
            "free|ads|flatrate",
            query(Section.ACTION, Filters(access = AccessFilter.FREE_AND_SUBSCRIPTION))["with_watch_monetization_types"]
        )
        assertEquals("free|ads|flatrate|rent|buy", query(Section.ACTION)["with_watch_monetization_types"])
    }

    @Test
    fun sectionsNarrowByOriginAndGenre() {
        assertEquals("US", query(Section.HOLLYWOOD)["with_origin_country"])
        assertEquals("en", query(Section.HOLLYWOOD)["with_original_language"])
        assertEquals("16", query(Section.ANIME)["with_genres"])
        assertEquals("ja", query(Section.ANIME)["with_original_language"])
        assertEquals("28", query(Section.ACTION)["with_genres"])
        assertEquals("27", query(Section.HORROR)["with_genres"])
        assertEquals("35", query(Section.COMEDY)["with_genres"])
        assertEquals("878", query(Section.SCI_FI)["with_genres"])
        assertNull(query(Section.NEW_RELEASES)["with_genres"])
        assertNull(query(Section.FREE)["with_origin_country"])
    }

    @Test
    fun sortingAndRatingFiltersAreTranslated() {
        assertEquals("popularity.desc", query(Section.COMEDY)["sort_by"])
        assertEquals("primary_release_date.desc", query(Section.COMEDY, Filters(sort = SortOrder.NEWEST))["sort_by"])
        val rated = query(Section.COMEDY, Filters(sort = SortOrder.TOP_RATED, minimumRating = 7))
        assertEquals("vote_average.desc", rated["sort_by"])
        assertEquals("7", rated["vote_average.gte"])
        // a score needs enough votes to mean something
        assertEquals("200", rated["vote_count.gte"])
        assertNull(query(Section.COMEDY)["vote_average.gte"])
        assertTrue(query(Section.COMEDY).getValue("vote_count.gte").toInt() > 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun listsKeptOnTheDeviceAreNotRequestedFromTmdb() {
        query(Section.WATCHLIST)
    }

    @Test
    fun accessFiltersNeverTreatAPaidOrUnknownFilmAsFree() {
        assertTrue(AccessFilter.FREE_ONLY.accepts(Access.FREE))
        assertTrue(AccessFilter.FREE_ONLY.accepts(Access.FREE_WITH_ADS))
        for (paid in listOf(Access.SUBSCRIPTION, Access.RENT, Access.BUY, null)) {
            assertFalse(AccessFilter.FREE_ONLY.accepts(paid))
        }
        assertTrue(AccessFilter.FREE_AND_SUBSCRIPTION.accepts(Access.SUBSCRIPTION))
        assertFalse(AccessFilter.FREE_AND_SUBSCRIPTION.accepts(Access.RENT))
        assertFalse(AccessFilter.FREE_AND_SUBSCRIPTION.accepts(null))
        assertTrue(AccessFilter.ANYTHING_WATCHABLE.accepts(Access.BUY))
        // a film without any offer cannot be watched and is never accepted
        assertFalse(AccessFilter.ANYTHING_WATCHABLE.accepts(null))
        assertEquals(listOf(Access.FREE, Access.FREE_WITH_ADS), Access.entries.filter { it.isFree })
    }

    @Test
    fun searchResultsAreHeldToTheYearAndRatingFilters() {
        val recent = TmdbMovie(1, "Recent", releaseDate = "2023-05-01", voteAverage = 6.5, voteCount = 100)
        val old = TmdbMovie(2, "Old", releaseDate = "1999-05-01", voteAverage = 8.5, voteCount = 100)
        val undated = TmdbMovie(3, "Undated", voteAverage = 8.5, voteCount = 100)

        assertTrue(DiscoverQuery.matches(recent, Filters(), today))
        assertFalse(DiscoverQuery.matches(old, Filters(), today))
        assertFalse(DiscoverQuery.matches(undated, Filters(), today))
        assertFalse(DiscoverQuery.matches(recent, Filters(years = Years.LATEST), today))
        assertTrue(DiscoverQuery.matches(old, Filters(years = Years.ANY), today))
        assertTrue(DiscoverQuery.matches(undated, Filters(years = Years.ANY), today))
        assertFalse(DiscoverQuery.matches(recent, Filters(minimumRating = 7), today))
        assertTrue(DiscoverQuery.matches(old, Filters(years = Years.ANY, minimumRating = 7), today))
    }
}
