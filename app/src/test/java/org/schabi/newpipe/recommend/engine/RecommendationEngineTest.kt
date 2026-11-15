/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.recommend.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationEngineTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun watch(
        title: String,
        uploader: String = "Some Channel",
        url: String = "https://example.com/watch?v=" + title.hashCode(),
        daysAgo: Long = 1,
        duration: Long = 600,
        fraction: Double? = 0.9,
        count: Long = 1
    ) = WatchEvent(
        url,
        title,
        uploader,
        "https://example.com/channel/$uploader",
        duration,
        fraction?.let { (it * duration * 1000).toLong() },
        count,
        now - daysAgo * day
    )

    private fun candidate(
        title: String,
        uploader: String = "Other Channel",
        source: CandidateSource = CandidateSource.TOPIC,
        daysOld: Long? = 3,
        views: Long = 10_000,
        duration: Long = 600,
        url: String = "https://example.com/watch?v=c" + (title + uploader).hashCode()
    ) = Candidate(
        0, url, title, uploader, "https://example.com/channel/$uploader", duration, views,
        daysOld?.let { now - it * day }, null, "VIDEO_STREAM", source
    )

    private fun profile(
        history: List<WatchEvent> = emptyList(),
        searches: List<SearchEvent> = emptyList(),
        subscriptions: List<SubscribedChannel> = emptyList(),
        feedback: List<Feedback> = emptyList()
    ) = InterestProfile.build(history, searches, subscriptions, feedback, now)

    private val keyboardHistory = listOf(
        watch("Building a mechanical keyboard from scratch", "KeebLab"),
        watch("Mechanical keyboard switches explained", "KeebLab"),
        watch("My mechanical keyboard collection tour", "Typist"),
        watch("Lubing switches for a mechanical keyboard", "Typist"),
        watch("Sourdough bread for beginners", "Baker", fraction = 0.1)
    )

    // ---------------------------------------------------------------- tokenizer

    @Test
    fun `tokenizer drops filler and keeps phrases`() {
        val terms = Tokenizer.terms("The BEST Mechanical Keyboard of 2025 (Official Video) [4K]")
        assertTrue("mechanical" in terms)
        assertTrue("mechanical keyboard" in terms)
        assertFalse("the" in terms)
        assertFalse("official" in terms)
        assertFalse("2025" in terms)
        assertFalse("4k" in terms)
    }

    @Test
    fun `tokenizer handles non latin scripts and empty input`() {
        assertTrue("программирование" in Tokenizer.terms("Программирование на Kotlin"))
        assertTrue(Tokenizer.terms(null).isEmpty())
        assertTrue(Tokenizer.terms("   ").isEmpty())
    }

    // ------------------------------------------------------------------ profile

    @Test
    fun `profile learns topics from watch history and prefers phrases`() {
        val profile = profile(keyboardHistory)
        assertEquals("mechanical keyboard", profile.topics.first())
        assertFalse("keyboard" in profile.topics)
        assertEquals(1.0, profile.termWeights.getValue("keyboard"), 1e-9)
        // barely watched video counts for much less than the ones watched to the end
        assertTrue(profile.termWeights.getValue("sourdough") < 0.5)
    }

    @Test
    fun `watch duration recency and rewatching increase engagement`() {
        val full = InterestProfile.engagement(watch("a", fraction = 1.0), now)
        val abandoned = InterestProfile.engagement(watch("a", fraction = 0.05), now)
        val old = InterestProfile.engagement(watch("a", fraction = 1.0, daysAgo = 90), now)
        val repeated = InterestProfile.engagement(watch("a", fraction = 1.0, count = 5), now)
        assertTrue(full > abandoned * 2)
        assertEquals(full / 8, old, full * 0.01) // three half-lives
        assertTrue(repeated > full)
        // unknown progress is neutral
        val unknown = InterestProfile.engagement(watch("a", fraction = null), now)
        assertTrue(unknown < full && unknown > abandoned)
    }

    @Test
    fun `searches and subscriptions contribute to the profile`() {
        val profile = profile(
            searches = listOf(SearchEvent("rust programming tutorial", now - day)),
            subscriptions = listOf(SubscribedChannel("https://example.com/channel/Ferris", "Ferris"))
        )
        assertFalse(profile.isEmpty)
        assertTrue("rust programming" in profile.termWeights)
        assertTrue("https://example.com/channel/ferris" in profile.subscribedChannels)
        assertTrue(profile.channelWeights.getValue("https://example.com/channel/ferris") > 0)
    }

    @Test
    fun `topics need repeated evidence unless searched for explicitly`() {
        val profile = profile(
            keyboardHistory,
            searches = listOf(SearchEvent("sourdough starter", now - day))
        )
        // "sourdough" was watched once and searched once, "starter" only searched
        assertTrue("sourdough starter" in profile.topics)
        // words from a single title are not topics, however much that video was enjoyed
        assertFalse(profile.topics.any { "scratch" in it || "collection" in it || "explained" in it })
        // ... but still count when ranking
        assertTrue("collection" in profile.termWeights)
        // "switches" came up in two titles
        assertTrue("switches" in profile.topics)
    }

    @Test
    fun `empty history yields an empty profile`() {
        val profile = profile()
        assertTrue(profile.isEmpty)
        assertTrue(profile.topics.isEmpty())
        assertTrue(profile.seeds.isEmpty())
    }

    @Test
    fun `seeds are the most engaging watched streams`() {
        val profile = profile(keyboardHistory)
        assertEquals(5, profile.seeds.size)
        assertEquals("Sourdough bread for beginners", profile.seeds.last().title)
    }

    // ------------------------------------------------------------------ ranking

    @Test
    fun `matching topic outranks unrelated popular video`() {
        val ranked = Ranker.rank(
            listOf(
                candidate("Celebrity gossip roundup", views = 50_000_000, source = CandidateSource.TRENDING),
                candidate("Budget mechanical keyboard buying guide", views = 2_000)
            ),
            profile(keyboardHistory),
            now
        )
        assertEquals("Budget mechanical keyboard buying guide", ranked[0].candidate.title)
        assertEquals(Reason.TOPIC, ranked[0].reason)
        assertEquals("mechanical keyboard", ranked[0].reasonDetail)
        assertEquals(Reason.TRENDING, ranked[1].reason)
    }

    @Test
    fun `fresher upload wins between otherwise equal candidates`() {
        val ranked = Ranker.rank(
            listOf(
                candidate("Mechanical keyboard news one", daysOld = 200, url = "u1"),
                candidate("Mechanical keyboard news two", daysOld = 1, url = "u2")
            ),
            profile(keyboardHistory),
            now,
            RankerConfig(diversityLambda = 0.0)
        )
        assertEquals("u2", ranked[0].candidate.url)
    }

    @Test
    fun `watched channel and subscriptions are boosted`() {
        val profile = profile(
            keyboardHistory,
            subscriptions = listOf(SubscribedChannel("https://example.com/channel/SubbedChannel", "SubbedChannel"))
        )
        val ranked = Ranker.rank(
            listOf(
                candidate("Something completely different", "Nobody"),
                candidate("Studio tour vlog", "KeebLab"),
                candidate("Weekly upload", "SubbedChannel", CandidateSource.SUBSCRIPTION)
            ),
            profile,
            now,
            RankerConfig(explorationEvery = 0)
        )
        assertEquals("Nobody", ranked.last().candidate.uploader)
        assertEquals(Reason.CHANNEL, ranked.first { it.candidate.uploader == "KeebLab" }.reason)
        assertEquals(Reason.SUBSCRIPTION, ranked.first { it.candidate.uploader == "SubbedChannel" }.reason)
    }

    @Test
    fun `already watched and short videos are filtered`() {
        val seen = watch("Mechanical keyboard switches explained", "KeebLab", url = "seen", fraction = 0.95)
        val half = watch("Mechanical keyboard deep dive", "KeebLab", url = "half", fraction = 0.4)
        val ranked = Ranker.rank(
            listOf(
                candidate("Mechanical keyboard switches explained", url = "seen"),
                candidate("Mechanical keyboard deep dive", url = "half"),
                candidate("Mechanical keyboard in 30 seconds", duration = 30, url = "short"),
                candidate("Mechanical keyboard livestream", duration = 0, url = "live")
            ),
            profile(listOf(seen, half)),
            now
        )
        assertEquals(setOf("half", "live"), ranked.map { it.candidate.url }.toSet())

        val withShorts = Ranker.rank(
            listOf(candidate("Mechanical keyboard in 30 seconds", duration = 30, url = "short")),
            profile(listOf(seen)),
            now,
            RankerConfig(hideShorts = false)
        )
        assertEquals(1, withShorts.size)
    }

    @Test
    fun `duplicates are merged keeping the strongest source`() {
        val ranked = Ranker.rank(
            listOf(
                candidate("Same video", source = CandidateSource.TRENDING, url = "dup"),
                candidate("Same video", source = CandidateSource.SUBSCRIPTION, url = "dup")
            ),
            profile(keyboardHistory),
            now
        )
        assertEquals(1, ranked.size)
        assertEquals(CandidateSource.SUBSCRIPTION, ranked[0].candidate.source)
    }

    // ----------------------------------------------------------------- feedback

    @Test
    fun `not interested hides the video and demotes similar ones`() {
        val disliked = candidate("Minecraft speedrun world record", "Runner", url = "disliked")
        val similar = candidate("Another Minecraft speedrun attempt", "Someone", url = "similar")
        val neutral = candidate("Gardening basics", "Gardener", url = "neutral")
        val candidates = listOf(disliked, similar, neutral)

        val before = Ranker.rank(candidates, profile(keyboardHistory), now)
        val scoreBefore = before.first { it.candidate.url == "similar" }.score

        val feedback = Feedback(
            FeedbackKind.VIDEO,
            "disliked",
            disliked.title,
            channelKey(disliked.uploaderUrl, disliked.uploader)
        )
        val after = Ranker.rank(candidates, profile(keyboardHistory, feedback = listOf(feedback)), now)
        assertFalse(after.any { it.candidate.url == "disliked" })
        val similarAfter = after.first { it.candidate.url == "similar" }
        assertTrue(similarAfter.score < scoreBefore - 0.2)
        assertTrue(after.indexOf(similarAfter) > after.indexOfFirst { it.candidate.url == "neutral" })
    }

    @Test
    fun `one dislike does not erase a strong interest`() {
        val feedback = Feedback(FeedbackKind.VIDEO, "x", "Boring mechanical keyboard unboxing", "name:x")
        val profile = profile(keyboardHistory, feedback = listOf(feedback))
        assertNotNull(profile.termWeights["mechanical keyboard"])
        assertFalse("unboxing" in profile.termWeights)
    }

    @Test
    fun `blocked channel and blocked topic never appear`() {
        val blockedChannel = channelKey("https://example.com/channel/Spammer", "Spammer")
        val profile = profile(
            keyboardHistory,
            feedback = listOf(
                Feedback(FeedbackKind.CHANNEL, blockedChannel),
                Feedback(FeedbackKind.TOPIC, "mechanical keyboard")
            )
        )
        val ranked = Ranker.rank(
            listOf(
                candidate("Great video", "Spammer"),
                candidate("Mechanical keyboard giveaway", "Honest"),
                candidate("Lubing switches properly", "Honest")
            ),
            profile,
            now
        )
        assertEquals(listOf("Lubing switches properly"), ranked.map { it.candidate.title })
        assertFalse("mechanical keyboard" in profile.topics)
    }

    @Test
    fun `repeated dislikes of one channel push it down`() {
        val key = channelKey("https://example.com/channel/Clickbait", "Clickbait")
        val feedback = (1..3).map { Feedback(FeedbackKind.VIDEO, "v$it", "Title $it", key) }
        val ranked = Ranker.rank(
            listOf(
                candidate("Mechanical keyboard mega video", "Clickbait"),
                candidate("Pottery wheel basics", "Potter")
            ),
            profile(keyboardHistory, feedback = feedback),
            now
        )
        assertEquals("Potter", ranked[0].candidate.uploader)
    }

    // ---------------------------------------------------------------- diversity

    @Test
    fun `one channel cannot flood the top of the feed`() {
        val flood = (1..10).map { candidate("Mechanical keyboard build number $it", "KeebLab", url = "k$it") }
        val others = (1..10).map { candidate("Mechanical keyboard thoughts $it", "Creator $it", url = "o$it") }
        val ranked = Ranker.rank(flood + others, profile(keyboardHistory), now)
        assertEquals(20, ranked.size)
        assertTrue(ranked.take(12).count { it.candidate.uploader == "KeebLab" } <= 2)
    }

    @Test
    fun `exploration slots surface content outside known interests`() {
        val familiar = (1..30).map { candidate("Mechanical keyboard video $it", "Creator $it", url = "f$it") }
        val unknown = (1..5).map {
            candidate("Deep sea creatures $it", "Ocean $it", CandidateSource.TRENDING, url = "x$it")
        }
        val ranked = Ranker.rank(familiar + unknown, profile(keyboardHistory), now)
        assertTrue(ranked[6].candidate.url.startsWith("x"))
        assertTrue(ranked[13].candidate.url.startsWith("x"))
        assertTrue(ranked.take(6).all { it.candidate.url.startsWith("f") })

        val noExploration = Ranker.rank(
            familiar + unknown,
            profile(keyboardHistory),
            now,
            RankerConfig(explorationEvery = 0)
        )
        assertTrue(noExploration.take(30).all { it.candidate.url.startsWith("f") })
    }

    @Test
    fun `cold start falls back to trending ordered by freshness and popularity`() {
        val ranked = Ranker.rank(
            listOf(
                candidate("Old and obscure", daysOld = 400, views = 10, source = CandidateSource.TRENDING),
                candidate("New and popular", daysOld = 1, views = 5_000_000, source = CandidateSource.TRENDING)
            ),
            profile(),
            now
        )
        assertEquals("New and popular", ranked[0].candidate.title)
        assertTrue(ranked.all { it.reason == Reason.TRENDING })
    }

    @Test
    fun `ranking is deterministic and respects the limit`() {
        val candidates = (1..60).map { candidate("Mechanical keyboard video $it", "Creator ${it % 9}", url = "d$it") }
        val profile = profile(keyboardHistory)
        val first = Ranker.rank(candidates, profile, now, limit = 25)
        val second = Ranker.rank(candidates.reversed(), profile, now, limit = 25)
        assertEquals(25, first.size)
        assertEquals(first.map { it.candidate.url }, second.map { it.candidate.url })
    }
}
