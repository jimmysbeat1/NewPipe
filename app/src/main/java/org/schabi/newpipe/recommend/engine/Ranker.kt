/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.recommend.engine

import kotlin.math.exp
import kotlin.math.log10

/**
 * Ranks candidates for the "For You" feed. Everything happens on the device, on data that never
 * leaves it: candidates are filtered (seen, hidden, blocked), scored against the
 * [InterestProfile] and then reordered so that the feed stays varied.
 */
object Ranker {
    private const val DAY_MILLIS = 86_400_000.0

    /** Number of top remaining candidates considered for each slot when diversifying. */
    private const val LOOKAHEAD = 40

    /** Number of previously picked items a new pick is compared against. */
    private const val SIMILARITY_WINDOW = 5

    private class Scored(
        val recommendation: Recommendation,
        val words: Set<String>,
        val channel: String,
        /** `true` if nothing in the profile explains this candidate. */
        val unfamiliar: Boolean
    )

    fun rank(
        candidates: Collection<Candidate>,
        profile: InterestProfile,
        nowMillis: Long,
        config: RankerConfig = RankerConfig(),
        limit: Int = 150
    ): List<Recommendation> {
        val scored = dedupe(candidates)
            .filter { isEligible(it, profile, config) }
            .map { score(it, profile, nowMillis, config) }
            .sortedWith(
                compareByDescending<Scored> { it.recommendation.score }
                    .thenBy { it.recommendation.candidate.url }
            )
        return diversify(scored, profile, config, limit)
    }

    /** Keeps one candidate per stream, preferring the more meaningful source. */
    private fun dedupe(candidates: Collection<Candidate>): List<Candidate> {
        val byUrl = LinkedHashMap<String, Candidate>()
        for (candidate in candidates) {
            val existing = byUrl[candidate.url]
            if (existing == null || candidate.source.ordinal < existing.source.ordinal) {
                byUrl[candidate.url] = candidate
            }
        }
        return byUrl.values.toList()
    }

    fun isEligible(candidate: Candidate, profile: InterestProfile, config: RankerConfig): Boolean {
        if (candidate.url in profile.hiddenUrls) {
            return false
        }
        if (channelKey(candidate.uploaderUrl, candidate.uploader) in profile.blockedChannels) {
            return false
        }
        if ((profile.watched[candidate.url] ?: 0.0) >= config.watchedFraction) {
            return false
        }
        if (config.hideShorts && candidate.durationSeconds in 1..config.shortsMaxSeconds) {
            return false
        }
        if (profile.blockedTopics.isNotEmpty()) {
            val terms = Tokenizer.terms(candidate.title)
            if (profile.blockedTopics.any { it in terms }) {
                return false
            }
        }
        return true
    }

    private fun score(
        candidate: Candidate,
        profile: InterestProfile,
        nowMillis: Long,
        config: RankerConfig
    ): Scored {
        val terms = Tokenizer.terms(candidate.title)
        val channel = channelKey(candidate.uploaderUrl, candidate.uploader)

        var topicSum = 0.0
        var negativeSum = 0.0
        var bestTopic: String? = null
        var bestTopicWeight = 0.0
        for (term in terms) {
            val weight = profile.termWeights[term]
            if (weight != null) {
                topicSum += weight
                if (weight > bestTopicWeight && term in profile.topics) {
                    bestTopic = term
                    bestTopicWeight = weight
                }
            }
            negativeSum += profile.negativeTerms[term] ?: 0.0
        }
        // saturating: the first matching term matters most, piling up keywords does not help
        val topic = topicSum / (1.0 + topicSum)
        val channelAffinity = profile.channelWeights[channel] ?: 0.0
        val subscribed = channel in profile.subscribedChannels
        val freshness = candidate.uploadEpochMillis?.let {
            exp(-((nowMillis - it) / DAY_MILLIS).coerceAtLeast(0.0) / config.freshnessDecayDays)
        } ?: 0.25
        val popularity = (log10(1.0 + candidate.viewCount.coerceAtLeast(0)) / 7.0).coerceAtMost(1.0)
        val negative = negativeSum.coerceAtMost(1.0) + (profile.channelPenalties[channel] ?: 0.0)

        val score = config.topicWeight * topic +
            config.channelWeight * channelAffinity +
            config.subscriptionWeight * (if (subscribed) 1.0 else 0.0) +
            config.freshnessWeight * freshness +
            config.popularityWeight * popularity +
            sourcePrior(candidate.source) +
            jitter(candidate.url, nowMillis) -
            config.negativeWeight * negative

        val topicPart = config.topicWeight * topic
        val channelPart = config.channelWeight * channelAffinity
        val reason: Reason
        var detail: String? = null
        if (subscribed && candidate.source == CandidateSource.SUBSCRIPTION) {
            reason = Reason.SUBSCRIPTION
        } else if (bestTopic != null && topicPart >= channelPart) {
            reason = Reason.TOPIC
            detail = bestTopic
        } else if (channelAffinity > 0.0) {
            reason = if (subscribed) Reason.SUBSCRIPTION else Reason.CHANNEL
        } else if (candidate.source == CandidateSource.RELATED) {
            reason = Reason.RELATED
            detail = candidate.seed
        } else if (candidate.source == CandidateSource.TOPIC && candidate.seed != null) {
            reason = Reason.TOPIC
            detail = candidate.seed
        } else if (candidate.source == CandidateSource.TRENDING) {
            reason = Reason.TRENDING
        } else {
            reason = Reason.FRESH
        }

        return Scored(
            Recommendation(candidate, score, reason, detail),
            Tokenizer.words(candidate.title).toSet(),
            channel,
            unfamiliar = topicSum == 0.0 && channelAffinity == 0.0
        )
    }

    private fun sourcePrior(source: CandidateSource): Double = when (source) {
        CandidateSource.SUBSCRIPTION -> 0.06
        CandidateSource.RELATED -> 0.05
        CandidateSource.TOPIC -> 0.03
        CandidateSource.TRENDING -> 0.0
    }

    /**
     * A small, stable pseudo-random nudge that changes daily, so that near-ties are not always
     * resolved the same way and the feed does not look identical every day.
     */
    private fun jitter(url: String, nowMillis: Long): Double {
        val day = (nowMillis / DAY_MILLIS).toLong()
        var hash = url.hashCode().toLong() * 31 + day
        hash = hash xor (hash ushr 16)
        hash *= 0x45d9f3b
        hash = hash xor (hash ushr 16)
        return (hash and 0xffff) / 65535.0 * 0.03
    }

    /**
     * Greedy maximal-marginal-relevance reordering: each slot goes to the candidate with the
     * best score after subtracting its similarity to the items just above it. On top of that,
     * a channel may only appear [RankerConfig.channelCap] times within a window, and every
     * [RankerConfig.explorationEvery]-th slot is given to something outside the known interests
     * so that the user can discover new topics instead of being locked into a bubble.
     */
    private fun diversify(
        sorted: List<Scored>,
        profile: InterestProfile,
        config: RankerConfig,
        limit: Int
    ): List<Recommendation> {
        val remaining = sorted.toMutableList()
        val picked = ArrayList<Scored>()
        val explore = config.explorationEvery > 0 && !profile.isEmpty

        while (remaining.isNotEmpty() && picked.size < limit) {
            var choice: Scored? = null
            if (explore && (picked.size + 1) % config.explorationEvery == 0) {
                choice = remaining.firstOrNull { it.unfamiliar && fitsChannelCap(it, picked, config) }
            }
            if (choice == null) {
                var bestValue = Double.NEGATIVE_INFINITY
                val window = remaining.subList(0, minOf(LOOKAHEAD, remaining.size))
                for (item in window) {
                    if (!fitsChannelCap(item, picked, config)) {
                        continue
                    }
                    val value = item.recommendation.score -
                        config.diversityLambda * maxSimilarity(item, picked) * SIMILARITY_SCALE
                    if (value > bestValue) {
                        bestValue = value
                        choice = item
                    }
                }
            }
            // everything in reach is from over-represented channels: relax the cap
            val next = choice ?: remaining.first()
            remaining.remove(next)
            picked.add(next)
        }
        return picked.map { it.recommendation }
    }

    /** Scores are roughly in `[0, 1]` but mostly below 0.5; scale similarity to match. */
    private const val SIMILARITY_SCALE = 0.5

    private fun fitsChannelCap(item: Scored, picked: List<Scored>, config: RankerConfig): Boolean {
        if (config.channelCap <= 0) {
            return true
        }
        val from = (picked.size - config.channelCapWindow + 1).coerceAtLeast(0)
        var count = 0
        for (i in from until picked.size) {
            if (picked[i].channel == item.channel) {
                count++
            }
        }
        return count < config.channelCap
    }

    private fun maxSimilarity(item: Scored, picked: List<Scored>): Double {
        var max = 0.0
        for (i in (picked.size - SIMILARITY_WINDOW).coerceAtLeast(0) until picked.size) {
            max = maxOf(max, similarity(item, picked[i]))
        }
        return max
    }

    private fun similarity(a: Scored, b: Scored): Double {
        val sameChannel = if (a.channel == b.channel) 0.5 else 0.0
        if (a.words.isEmpty() || b.words.isEmpty()) {
            return sameChannel
        }
        val common = a.words.count { it in b.words }
        val jaccard = common.toDouble() / (a.words.size + b.words.size - common)
        return (sameChannel + jaccard).coerceAtMost(1.0)
    }
}
