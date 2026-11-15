/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.schabi.newpipe.DownloaderImpl
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.movies.MovieCatalog
import org.schabi.newpipe.movies.MovieFetcher
import org.schabi.newpipe.movies.MoviesRepository
import org.schabi.newpipe.recommend.data.CandidateFetcher
import org.schabi.newpipe.recommend.data.PersonalStore
import org.schabi.newpipe.recommend.data.RecommendationRepository
import org.schabi.newpipe.recommend.data.Signals
import org.schabi.newpipe.recommend.engine.Reason
import org.schabi.newpipe.recommend.engine.SearchEvent
import org.schabi.newpipe.recommend.engine.WatchEvent

/**
 * End-to-end tests against the real service. They need network access and depend on what the
 * service returns at the time, so they only run when `PIPEHOME_LIVE_TESTS=1` is set:
 *
 * `PIPEHOME_LIVE_TESTS=1 ./gradlew :app:testDebugUnitTest --tests "*LiveServiceTest*"`
 */
class LiveServiceTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val youtube = ServiceList.YouTube.serviceId

    @Before
    fun setUp() {
        Assume.assumeTrue(System.getenv("PIPEHOME_LIVE_TESTS") == "1")
        NewPipe.init(DownloaderImpl.init(null))
    }

    @Test
    fun `candidate sources return usable streams`() {
        val fetcher = CandidateFetcher()

        val search = fetcher.search(youtube, "mechanical keyboard")
        println("LIVE search: ${search.size} items, first: ${search.firstOrNull()}")
        assertTrue(search.size >= 5)
        assertTrue(search.all { it.url.startsWith("https://") && it.title.isNotBlank() })
        assertTrue(search.any { it.durationSeconds > 0 && it.viewCount > 0 && it.uploadEpochMillis != null })

        val trending = fetcher.trending(youtube)
        println("LIVE trending: ${trending.size} items from ${trending.map { it.uploader }.distinct().size} channels")
        assertTrue(trending.size >= 10)

        val related = fetcher.related(youtube, "https://www.youtube.com/watch?v=jNQXAC9IVRw", "Me at the zoo")
        println("LIVE related: ${related.size} items")
        assertTrue(related.isNotEmpty())
    }

    @Test
    fun `feed adapts to a watch history`() {
        val now = System.currentTimeMillis()
        val history = listOf(
            "Restoring a rusty cast iron skillet",
            "Cast iron skillet seasoning guide",
            "Cast iron skillet steak recipe"
        ).mapIndexed { index, title ->
            WatchEvent("https://example.invalid/$index", title, "Cook", null, 600, 580_000, 1, now - 3_600_000, youtube)
        }
        val signals = Signals(history, listOf(SearchEvent("sourdough starter", now)))
        val repository = RecommendationRepository(
            { signals },
            PersonalStore(folder.newFolder()),
            // the history above is made up, so there is nothing real to load related streams for
            object : CandidateFetcher() {
                override fun related(serviceId: Int, url: String, seedTitle: String) = emptyList<org.schabi.newpipe.recommend.engine.Candidate>()
            }
        )

        val outcome = repository.refresh(youtube)
        println("LIVE refresh: ${outcome.succeeded} ok, ${outcome.failed} failed, error=${outcome.error}")
        assertFalse(outcome.nothingLoaded)

        val snapshot = repository.snapshot()
        println("LIVE topics: ${snapshot.profile.topics}")
        snapshot.recommendations.take(12).forEach {
            println("LIVE   %.3f %-8s %-22s %s".format(it.score, it.reason, it.reasonDetail, it.candidate.title))
        }
        assertTrue("cast iron" in snapshot.profile.topics || "iron skillet" in snapshot.profile.topics)
        assertTrue(snapshot.recommendations.size >= 20)
        val top = snapshot.recommendations.take(6)
        assertTrue(top.count { it.reason == Reason.TOPIC } >= 4)
        // exploration: something from outside the profile is mixed in
        assertTrue(snapshot.recommendations.take(14).any { it.reason == Reason.TRENDING })
        // diversity: the top of the feed is not a single channel
        assertTrue(top.map { it.candidate.uploader }.distinct().size >= 3)
    }

    @Test
    fun `every trusted distributor resolves and publishes full length films`() {
        val fetcher = MovieFetcher()
        var total = 0
        for (distributor in MovieCatalog.DISTRIBUTORS) {
            val page = fetcher.channel(distributor)
            println("LIVE ${distributor.name}: ${page.movies.size} films, more=${page.next != null}, e.g. ${page.movies.firstOrNull()?.title}")
            assertTrue(distributor.name, page.movies.isNotEmpty())
            assertTrue(page.movies.all { it.trusted && it.durationSeconds >= MovieCatalog.MIN_DURATION_SECONDS })
            total += page.movies.size
        }
        assertTrue(total > 150)
    }

    @Test
    fun `movie catalog refresh load more and search`() {
        val repository = MoviesRepository(PersonalStore(folder.newFolder()), MovieFetcher())
        val outcome = repository.refresh()
        println("LIVE catalog refresh: ${outcome.succeeded} ok, ${outcome.failed} failed, error=${outcome.error}")
        assertEquals(0, outcome.failed)
        val first = repository.catalog().movies
        val genres = first.flatMap { it.genres }.groupingBy { it }.eachCount()
        println("LIVE catalog: ${first.size} films, genres=$genres")
        assertTrue(first.size > 150)
        assertTrue(genres.size >= 8)

        assertTrue(repository.canLoadMore())
        repository.loadMore()
        val second = repository.catalog().movies
        println("LIVE catalog after load more: ${second.size} films")
        assertTrue(second.size > first.size + 50)

        val (trusted, error) = repository.search("western", false)
        println("LIVE search western: ${trusted.size} trusted results, error=$error")
        assertNotNull(trusted.firstOrNull())
        assertTrue(trusted.all { it.trusted })
        val (all, _) = repository.search("western", true)
        println("LIVE search western incl. unverified: ${all.size} results")
        assertTrue(all.size > trusted.size)
    }
}
