/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.recommend.data

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.schabi.newpipe.recommend.engine.Candidate
import org.schabi.newpipe.recommend.engine.Feedback
import org.schabi.newpipe.recommend.engine.InterestProfile
import org.schabi.newpipe.recommend.engine.Ranker
import org.schabi.newpipe.recommend.engine.RankerConfig
import org.schabi.newpipe.recommend.engine.Recommendation
import org.schabi.newpipe.recommend.engine.SearchEvent
import org.schabi.newpipe.recommend.engine.SubscribedChannel
import org.schabi.newpipe.recommend.engine.Tokenizer
import org.schabi.newpipe.recommend.engine.WatchEvent

/** Everything the device already knows about the user's viewing habits. */
data class Signals(
    val history: List<WatchEvent> = emptyList(),
    val searches: List<SearchEvent> = emptyList(),
    val subscriptions: List<SubscribedChannel> = emptyList(),
    /** Recent uploads of subscribed channels, taken from the local subscription feed. */
    val subscriptionUploads: List<Candidate> = emptyList()
)

fun interface SignalSource {
    fun load(): Signals
}

@Serializable
data class CachedCandidate(val candidate: Candidate, val fetchedAt: Long)

@Serializable
data class CandidateCache(
    val refreshedAt: Long = 0,
    val items: List<CachedCandidate> = emptyList()
)

/** The feed as it can be computed right now, without any network access. */
class FeedSnapshot(
    val profile: InterestProfile,
    val recommendations: List<Recommendation>,
    val refreshedAt: Long
)

class RefreshOutcome(val succeeded: Int, val failed: Int, val error: Throwable?) {
    /** `true` if not a single request went through, e.g. because the device is offline. */
    val nothingLoaded: Boolean
        get() = succeeded == 0 && failed > 0
}

/**
 * Glues the engine to its inputs: the local signals, the stored feedback and the cached
 * candidates. All functions block; call them from a background thread.
 */
class RecommendationRepository(
    private val signalSource: SignalSource,
    private val store: PersonalStore,
    private val fetcher: CandidateFetcher,
    private val clock: () -> Long = System::currentTimeMillis
) {
    // ------------------------------------------------------------------ feedback

    fun feedback(): List<Feedback> = store.read(FEEDBACK, ListSerializer(Feedback.serializer()), emptyList())

    fun addFeedback(feedback: Feedback) {
        val stamped = feedback.copy(epochMillis = clock())
        val all = feedback().filterNot { it.kind == feedback.kind && it.key == feedback.key }
        store.write(FEEDBACK, ListSerializer(Feedback.serializer()), all + stamped)
    }

    fun removeFeedback(feedback: Feedback) {
        val all = feedback().filterNot { it.kind == feedback.kind && it.key == feedback.key }
        store.write(FEEDBACK, ListSerializer(Feedback.serializer()), all)
    }

    /** Forgets all feedback and all cached candidates. The watch history is not touched. */
    fun reset() {
        store.delete(FEEDBACK)
        store.delete(CANDIDATES)
    }

    // ---------------------------------------------------------------------- feed

    private fun cache(): CandidateCache = store.read(CANDIDATES, CandidateCache.serializer(), CandidateCache())

    private fun profile(signals: Signals): InterestProfile {
        return InterestProfile.build(
            signals.history,
            signals.searches,
            signals.subscriptions,
            feedback(),
            clock()
        )
    }

    /**
     * Ranks what is already on the device.
     *
     * @param topic if set, only candidates about this topic are returned
     */
    fun snapshot(config: RankerConfig = RankerConfig(), topic: String? = null): FeedSnapshot {
        val signals = signalSource.load()
        val profile = profile(signals)
        val cache = cache()
        var candidates = cache.items.map { it.candidate } + signals.subscriptionUploads
        var effectiveConfig = config
        if (topic != null) {
            candidates = candidates.filter { matchesTopic(it, topic) }
            // within a single topic there is nothing to explore
            effectiveConfig = config.copy(explorationEvery = 0)
        }
        return FeedSnapshot(
            profile,
            Ranker.rank(candidates, profile, clock(), effectiveConfig, FEED_SIZE),
            cache.refreshedAt
        )
    }

    fun isStale(): Boolean = clock() - cache().refreshedAt > STALE_AFTER_MILLIS

    /**
     * Fetches new candidates for the current profile: videos about the user's topics, videos
     * related to what they watched, and what is trending (for exploration and cold start).
     * Which topics and seeds are used rotates over time, so that successive refreshes keep
     * widening the pool instead of asking for the same things again.
     */
    fun refresh(serviceId: Int): RefreshOutcome {
        val now = clock()
        val profile = profile(signalSource.load())
        val rotation = (now / ROTATION_MILLIS).toInt()

        val tasks = ArrayList<Callable<List<Candidate>>>()
        for (topic in rotate(profile.topics, TOPICS_PER_REFRESH, PINNED_TOPICS, rotation)) {
            tasks.add(Callable { fetcher.search(serviceId, topic) })
        }
        for (seed in rotate(profile.seeds, SEEDS_PER_REFRESH, PINNED_SEEDS, rotation)) {
            tasks.add(Callable { fetcher.related(seed.serviceId, seed.url, seed.title) })
        }
        tasks.add(Callable { fetcher.trending(serviceId) })

        return runAndStore(tasks, now)
    }

    /** Fetches more candidates about a single topic, e.g. when the user selects its chip. */
    fun refreshTopic(serviceId: Int, topic: String): RefreshOutcome {
        return runAndStore(listOf(Callable { fetcher.search(serviceId, topic) }), clock(), false)
    }

    private fun runAndStore(
        tasks: List<Callable<List<Candidate>>>,
        now: Long,
        fullRefresh: Boolean = true
    ): RefreshOutcome {
        val fetched = ArrayList<Candidate>()
        var succeeded = 0
        var failed = 0
        var error: Throwable? = null
        val executor = Executors.newFixedThreadPool(PARALLEL_REQUESTS)
        try {
            for (future in executor.invokeAll(tasks)) {
                try {
                    fetched.addAll(future.get())
                    succeeded++
                } catch (e: java.util.concurrent.ExecutionException) {
                    failed++
                    error = error ?: (e.cause ?: e)
                }
            }
        } finally {
            executor.shutdown()
        }

        if (succeeded > 0) {
            val old = cache()
            val fetchedUrls = fetched.mapTo(HashSet()) { it.url }
            val kept = old.items.filter {
                it.candidate.url !in fetchedUrls && now - it.fetchedAt < KEEP_MILLIS
            }
            val items = (fetched.distinctBy { it.url }.map { CachedCandidate(it, now) } + kept)
                .take(MAX_CACHED)
            store.write(
                CANDIDATES,
                CandidateCache.serializer(),
                CandidateCache(if (fullRefresh) now else old.refreshedAt, items)
            )
        }
        return RefreshOutcome(succeeded, failed, error)
    }

    companion object {
        private const val FEEDBACK = "feedback"
        private const val CANDIDATES = "candidates"

        const val FEED_SIZE = 200
        const val STALE_AFTER_MILLIS = 3 * 60 * 60 * 1000L
        private const val ROTATION_MILLIS = 60 * 60 * 1000L
        private const val KEEP_MILLIS = 7 * 24 * 60 * 60 * 1000L
        private const val MAX_CACHED = 800
        private const val PARALLEL_REQUESTS = 4
        private const val TOPICS_PER_REFRESH = 5
        private const val PINNED_TOPICS = 2
        private const val SEEDS_PER_REFRESH = 3
        private const val PINNED_SEEDS = 1

        fun matchesTopic(candidate: Candidate, topic: String): Boolean {
            return candidate.seed.equals(topic, ignoreCase = true) ||
                topic.lowercase() in Tokenizer.terms(candidate.title)
        }

        /**
         * Picks [count] items: always the first [pinned] (the strongest ones), and the rest
         * from the remainder, starting at a position that advances with [rotation].
         */
        fun <T> rotate(items: List<T>, count: Int, pinned: Int, rotation: Int): List<T> {
            if (items.size <= count) {
                return items
            }
            val head = items.take(pinned)
            val tail = items.drop(pinned)
            val start = Math.floorMod(rotation * (count - pinned), tail.size)
            return head + (0 until count - pinned).map { tail[(start + it) % tail.size] }
        }
    }
}
