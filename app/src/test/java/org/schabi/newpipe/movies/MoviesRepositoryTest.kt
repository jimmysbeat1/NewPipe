/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies

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
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.recommend.data.PersonalStore

class MoviesRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val good = Distributor("UCVFYikepF-avelvuIaQ_lHA", "Good")
    private val broken = Distributor("UC8IHAQMuiJdY6ALuhG7iU8Q", "Broken")
    private var now = 1_800_000_000_000L
    private lateinit var store: PersonalStore
    private lateinit var fetcher: FakeFetcher

    private fun movie(title: String, distributor: Distributor? = good): Movie {
        val channelUrl = distributor?.let { "https://www.youtube.com/channel/" + it.channelId }
            ?: "https://www.youtube.com/channel/UC0000000000000000000000"
        return MovieCatalog.toMovie(0, "https://youtu.be/$title", title, "x", channelUrl, 6000, 1, null, null)!!
    }

    private inner class FakeFetcher : MovieFetcher() {
        var failSearch = false
        var channelCalls = 0
        private val handler = ListLinkHandler("u", "u", "id", emptyList(), "")

        override fun channel(distributor: Distributor): ChannelPage {
            channelCalls++
            if (distributor === broken) {
                throw IOException("offline")
            }
            return ChannelPage(listOf(movie("New 1"), movie("New 2")), Continuation(handler, Page("page2")))
        }

        override fun more(distributor: Distributor, continuation: Continuation): ChannelPage {
            assertEquals("page2", continuation.page.url)
            return ChannelPage(listOf(movie("Old 1")), null)
        }

        override fun search(query: String): List<Movie> {
            if (failSearch) {
                throw IOException("offline")
            }
            return listOf(movie("Remote $query"), movie("Bootleg $query", null))
        }
    }

    private fun repository(vararg distributors: Distributor) = MoviesRepository(store, fetcher, distributors.toList()) { now }

    @Before
    fun setUp() {
        store = PersonalStore(folder.newFolder("store"))
        fetcher = FakeFetcher()
    }

    @Test
    fun `refresh stores the catalog and survives a failing distributor`() {
        val repository = repository(good, broken)
        assertTrue(repository.isStale())
        assertTrue(repository.catalog().movies.isEmpty())

        val outcome = repository.refresh()
        assertEquals(1, outcome.succeeded)
        assertEquals(1, outcome.failed)
        assertFalse(outcome.nothingLoaded)
        assertEquals(listOf("New 1", "New 2"), repository.catalog().movies.map { it.title })
        assertFalse(repository.isStale())

        // a new repository instance (app restart) reads the same catalog from disk
        assertEquals(2, repository(good).catalog().movies.size)

        now += MoviesRepository.STALE_AFTER_MILLIS + 1
        assertTrue(repository.isStale())
    }

    @Test
    fun `when nothing loads the old catalog is kept and the error reported`() {
        repository(good).refresh()
        val outcome = repository(broken).refresh()
        assertTrue(outcome.nothingLoaded)
        assertTrue(outcome.error is IOException)
        assertEquals(2, repository(broken).catalog().movies.size)
    }

    @Test
    fun `load more appends older films until the channel is exhausted`() {
        val repository = repository(good)
        assertFalse(repository.canLoadMore())
        repository.refresh()
        assertTrue(repository.canLoadMore())

        assertEquals(1, repository.loadMore().succeeded)
        assertEquals(listOf("New 1", "New 2", "Old 1"), repository.catalog().movies.map { it.title })
        assertFalse(repository.canLoadMore())
        assertEquals(0, repository.loadMore().succeeded)
    }

    @Test
    fun `search hides unverified uploaders unless opted in`() {
        val repository = repository(good)
        repository.refresh()

        val (trustedOnly, error) = repository.search("new", false)
        assertNull(error)
        assertEquals(listOf("New 1", "New 2", "Remote new"), trustedOnly.map { it.title })
        assertTrue(trustedOnly.all { it.trusted })

        val (everything, _) = repository.search("new", true)
        assertEquals("Bootleg new", everything.last().title)
        assertFalse(everything.last().trusted)
    }

    @Test
    fun `search falls back to the catalog when offline`() {
        val repository = repository(good)
        repository.refresh()
        fetcher.failSearch = true
        val (results, error) = repository.search("new 2", false)
        assertEquals(listOf("New 2"), results.map { it.title })
        assertNotNull(error)
    }

    @Test
    fun `watchlist is persisted newest first without duplicates`() {
        val repository = repository(good)
        repository.addToWatchlist(movie("A"))
        repository.addToWatchlist(movie("B"))
        repository.addToWatchlist(movie("A"))
        assertEquals(listOf("A", "B"), repository(good).watchlist().map { it.title })
        repository.removeFromWatchlist(movie("A").url)
        assertEquals(listOf("B"), repository.watchlist().map { it.title })
    }

    @Test
    fun `purchases are stored as official playback only`() {
        val repository = repository(good)
        repository.addPurchase("Blockbuster", "https://www.youtube.com/watch?v=abc")
        val purchase = repository(good).purchases().single()
        assertTrue(purchase.officialOnly)
        assertEquals("Blockbuster", purchase.title)
        repository.removePurchase(purchase.url)
        assertTrue(repository.purchases().isEmpty())
    }
}
