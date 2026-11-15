/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.recommend.data

import java.io.File
import java.io.IOException
import java.util.Collections
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.schabi.newpipe.recommend.engine.Candidate
import org.schabi.newpipe.recommend.engine.CandidateSource
import org.schabi.newpipe.recommend.engine.Feedback
import org.schabi.newpipe.recommend.engine.FeedbackKind
import org.schabi.newpipe.recommend.engine.Reason
import org.schabi.newpipe.recommend.engine.SubscribedChannel
import org.schabi.newpipe.recommend.engine.WatchEvent
import org.schabi.newpipe.recommend.engine.channelKey

class RecommendationRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_800_000_000_000L
    private val day = 86_400_000L
    private lateinit var directory: File
    private lateinit var store: PersonalStore
    private lateinit var fetcher: FakeFetcher
    private var signals = Signals()

    private fun candidate(title: String, uploader: String, source: CandidateSource, seed: String? = null) = Candidate(
        0, "https://example.com/$title", title, uploader, "https://example.com/c/$uploader",
        600, 5_000, now - day, null, "VIDEO_STREAM", source, seed
    )

    private inner class FakeFetcher : CandidateFetcher() {
        var offline = false
        val searches: MutableList<String> = Collections.synchronizedList(ArrayList())
        val relatedTo: MutableList<String> = Collections.synchronizedList(ArrayList())

        override fun search(serviceId: Int, query: String, source: CandidateSource): List<Candidate> {
            if (offline) throw IOException("offline")
            searches.add(query)
            return (1..3).map { candidate("$query video $it", "Creator $it", source, query) }
        }

        override fun related(serviceId: Int, url: String, seedTitle: String): List<Candidate> {
            if (offline) throw IOException("offline")
            relatedTo.add(url)
            return listOf(candidate("Related to $seedTitle", "Rel", CandidateSource.RELATED, seedTitle))
        }

        override fun trending(serviceId: Int): List<Candidate> {
            if (offline) throw IOException("offline")
            return (1..4).map { candidate("Viral clip $it", "Viral $it", CandidateSource.TRENDING) }
        }
    }

    private fun repository() = RecommendationRepository({ signals }, store, fetcher) { now }

    private fun watched(title: String, uploader: String = "KeebLab") = WatchEvent(
        "https://example.com/watched/$title",
        title,
        uploader,
        "https://example.com/c/$uploader",
        600,
        590_000,
        1,
        now - day
    )

    @Before
    fun setUp() {
        directory = folder.newFolder("store")
        store = PersonalStore(directory)
        fetcher = FakeFetcher()
        signals = Signals(
            history = listOf(
                watched("Mechanical keyboard build guide"),
                watched("Mechanical keyboard sound test"),
                watched("Sourdough bread at home", "Baker")
            )
        )
    }

    @Test
    fun `cold start shows trending only`() {
        signals = Signals()
        val repository = repository()
        assertTrue(repository.snapshot().recommendations.isEmpty())
        assertTrue(repository.isStale())

        val outcome = repository.refresh(0)
        assertEquals(1, outcome.succeeded)
        assertTrue(fetcher.searches.isEmpty())

        val snapshot = repository.snapshot()
        assertTrue(snapshot.profile.isEmpty)
        assertEquals(4, snapshot.recommendations.size)
        assertTrue(snapshot.recommendations.all { it.reason == Reason.TRENDING })
        assertFalse(repository.isStale())
    }

    @Test
    fun `refresh searches the learned topics and seeds and ranks them first`() {
        val repository = repository()
        val outcome = repository.refresh(0)
        assertEquals(0, outcome.failed)
        assertTrue("mechanical keyboard" in fetcher.searches)
        assertEquals(3, fetcher.relatedTo.size)

        val snapshot = repository.snapshot()
        assertEquals("mechanical keyboard", snapshot.profile.topics.first())
        val first = snapshot.recommendations.first()
        assertTrue(first.candidate.title, "keyboard" in first.candidate.title.lowercase())
        assertEquals(Reason.TOPIC, first.reason)
        // trending items are still there, for exploration, but not on top
        assertTrue(snapshot.recommendations.any { it.reason == Reason.TRENDING })
    }

    @Test
    fun `feed survives restarts and works offline from the cache`() {
        repository().refresh(0)
        val before = repository().snapshot().recommendations.map { it.candidate.url }
        assertTrue(before.isNotEmpty())

        fetcher.offline = true
        val restarted = RecommendationRepository({ signals }, PersonalStore(directory), fetcher) { now }
        val outcome = restarted.refresh(0)
        assertTrue(outcome.nothingLoaded)
        assertTrue(outcome.error is IOException)
        assertEquals(before, restarted.snapshot().recommendations.map { it.candidate.url })
    }

    @Test
    fun `cache goes stale and old candidates expire`() {
        val repository = repository()
        repository.refresh(0)
        now += RecommendationRepository.STALE_AFTER_MILLIS + 1
        assertTrue(repository.isStale())

        val count = repository.snapshot().recommendations.size
        now += 8 * day
        signals = Signals() // nothing to search for any more: only trending is fetched
        repository.refresh(0)
        val urls = repository.snapshot().recommendations.map { it.candidate.title }
        assertTrue(urls.size < count)
        assertTrue(urls.all { it.startsWith("Viral") })
    }

    @Test
    fun `subscription uploads are recommended without any network access`() {
        signals = signals.copy(
            subscriptions = listOf(SubscribedChannel("https://example.com/c/Subbed", "Subbed")),
            subscriptionUploads = listOf(candidate("Weekly show", "Subbed", CandidateSource.SUBSCRIPTION))
        )
        val recommendations = repository().snapshot().recommendations
        assertEquals(listOf("Weekly show"), recommendations.map { it.candidate.title })
        assertEquals(Reason.SUBSCRIPTION, recommendations[0].reason)
    }

    @Test
    fun `watching a recommendation removes it from the feed`() {
        val repository = repository()
        repository.refresh(0)
        val target = repository.snapshot().recommendations.first().candidate
        signals = signals.copy(
            history = signals.history + WatchEvent(
                target.url,
                target.title,
                target.uploader,
                target.uploaderUrl,
                600,
                600_000,
                1,
                now
            )
        )
        assertFalse(repository.snapshot().recommendations.any { it.candidate.url == target.url })
    }

    @Test
    fun `not interested feedback is persisted applied and undoable`() {
        val repository = repository()
        repository.refresh(0)
        val target = repository.snapshot().recommendations.first().candidate
        val feedback = Feedback(
            FeedbackKind.VIDEO,
            target.url,
            target.title,
            channelKey(target.uploaderUrl, target.uploader)
        )

        repository.addFeedback(feedback)
        repository.addFeedback(feedback) // giving the same feedback twice changes nothing
        assertEquals(1, repository.feedback().size)
        assertEquals(now, repository.feedback()[0].epochMillis)
        assertFalse(repository().snapshot().recommendations.any { it.candidate.url == target.url })

        repository.removeFeedback(feedback)
        assertTrue(repository.feedback().isEmpty())
        assertTrue(repository.snapshot().recommendations.any { it.candidate.url == target.url })
    }

    @Test
    fun `blocking a channel and resetting`() {
        val repository = repository()
        repository.refresh(0)
        val key = channelKey("https://example.com/c/Creator 1", "Creator 1")
        repository.addFeedback(Feedback(FeedbackKind.CHANNEL, key))
        assertFalse(repository.snapshot().recommendations.any { it.candidate.uploader == "Creator 1" })

        repository.reset()
        assertTrue(repository.feedback().isEmpty())
        assertTrue(repository.snapshot().recommendations.isEmpty())
        assertTrue(repository.isStale())
    }

    @Test
    fun `topic filter narrows the feed and can fetch more for that topic`() {
        val repository = repository()
        repository.refresh(0)
        val sourdough = repository.snapshot(topic = "sourdough").recommendations
        assertTrue(sourdough.isNotEmpty())
        assertTrue(sourdough.all { "sourdough" in it.candidate.title.lowercase() || it.candidate.seed == "sourdough" })

        fetcher.searches.clear()
        val outcome = repository.refreshTopic(0, "sourdough bread")
        assertEquals(1, outcome.succeeded)
        assertEquals(listOf("sourdough bread"), fetcher.searches)
        val bread = repository.snapshot(topic = "sourdough bread").recommendations
        assertTrue(bread.count { it.candidate.seed == "sourdough bread" } == 3)
        assertTrue(bread.all { "sourdough bread" in it.candidate.title.lowercase() })
        // a topic refresh does not count as a full refresh
        now += RecommendationRepository.STALE_AFTER_MILLIS + 1
        assertTrue(repository.isStale())
    }

    @Test
    fun `rotation always keeps the strongest items and cycles through the rest`() {
        val items = (1..10).toList()
        assertEquals(listOf(1, 2, 3), RecommendationRepository.rotate(items.take(3), 5, 2, 7))
        assertEquals(listOf(1, 2, 3, 4, 5), RecommendationRepository.rotate(items, 5, 2, 0))
        assertEquals(listOf(1, 2, 6, 7, 8), RecommendationRepository.rotate(items, 5, 2, 1))
        assertEquals(listOf(1, 2, 9, 10, 3), RecommendationRepository.rotate(items, 5, 2, 2))
        val seen = (0..7).flatMap { RecommendationRepository.rotate(items, 5, 2, it) }.toSet()
        assertEquals(items.toSet(), seen)
    }

    @Test
    fun `store tolerates corrupt files and replaces values atomically`() {
        val serializer = ListSerializer(String.serializer())
        assertEquals(listOf("default"), store.read("missing", serializer, listOf("default")))
        store.write("list", serializer, listOf("a", "b"))
        store.write("list", serializer, listOf("c"))
        assertEquals(listOf("c"), store.read("list", serializer, emptyList()))
        assertEquals(listOf("list.json"), directory.list()!!.toList())

        File(directory, "list.json").writeText("{ not json")
        assertEquals(emptyList<String>(), store.read("list", serializer, emptyList()))
        store.delete("list")
        assertTrue(directory.list()!!.isEmpty())
    }
}
