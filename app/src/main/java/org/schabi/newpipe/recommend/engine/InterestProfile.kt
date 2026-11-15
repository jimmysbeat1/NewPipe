/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.recommend.engine

import kotlin.math.ln
import kotlin.math.pow

/**
 * What the engine knows about the user's taste. Built from scratch from the local history each
 * time, so that deleting history (or giving feedback) is immediately reflected.
 *
 * @param termWeights interest in a term, in `(0, 1]`
 * @param channelWeights affinity to a channel (see [channelKey]), in `(0, 1]`
 * @param negativeTerms dislike of a term, in `(0, 1]`
 * @param channelPenalties soft penalty of a channel, in `(0, 1]`
 * @param watched fraction watched per stream url, in `[0, 1]`
 */
class InterestProfile(
    val termWeights: Map<String, Double>,
    val channelWeights: Map<String, Double>,
    val subscribedChannels: Set<String>,
    val negativeTerms: Map<String, Double>,
    val channelPenalties: Map<String, Double>,
    val blockedChannels: Set<String>,
    val blockedTopics: Set<String>,
    val hiddenUrls: Set<String>,
    val watched: Map<String, Double>,
    val topics: List<String>,
    /** The most engaging recently watched streams, best first; used to fetch related streams. */
    val seeds: List<WatchEvent>
) {
    /** `true` when there is no positive signal at all, i.e. only trending can be shown. */
    val isEmpty: Boolean
        get() = termWeights.isEmpty() && channelWeights.isEmpty()

    companion object {
        const val HALF_LIFE_DAYS = 30.0
        const val MAX_TERMS = 400
        const val MAX_TOPICS = 12
        const val MAX_SEEDS = 8
        private const val DAY_MILLIS = 86_400_000.0

        /** Weight of something that happened [epochMillis], halving every [HALF_LIFE_DAYS]. */
        fun recency(epochMillis: Long, nowMillis: Long): Double {
            val ageDays = ((nowMillis - epochMillis) / DAY_MILLIS).coerceAtLeast(0.0)
            return 0.5.pow(ageDays / HALF_LIFE_DAYS)
        }

        /**
         * How much of a stream was watched, in `[0, 1]`. NewPipe only stores a position once
         * playback got past the first seconds, so a missing position on a watched stream is
         * treated as a neutral half.
         */
        fun watchFraction(event: WatchEvent): Double {
            val progress = event.progressMillis ?: return 0.5
            if (event.durationSeconds <= 0) {
                return 0.5
            }
            return (progress / (event.durationSeconds * 1000.0)).coerceIn(0.0, 1.0)
        }

        /** How much a watch event tells about the user's taste. */
        fun engagement(event: WatchEvent, nowMillis: Long): Double {
            val repeat = 1.0 + ln(event.watchCount.coerceAtLeast(1).toDouble())
            return recency(event.lastWatchedEpochMillis, nowMillis) *
                (0.25 + 0.75 * watchFraction(event)) * repeat
        }

        fun build(
            history: List<WatchEvent>,
            searches: List<SearchEvent>,
            subscriptions: List<SubscribedChannel>,
            feedback: List<Feedback>,
            nowMillis: Long
        ): InterestProfile {
            val terms = HashMap<String, Double>()
            // number of watch events and searches a term occurred in
            val support = HashMap<String, Int>()
            val searched = HashSet<String>()
            val channels = HashMap<String, Double>()
            val watched = HashMap<String, Double>()

            for (event in history) {
                val engagement = engagement(event, nowMillis)
                for (term in Tokenizer.terms(event.title)) {
                    terms.merge(term, engagement, Double::plus)
                    support.merge(term, 1, Int::plus)
                }
                channels.merge(channelKey(event.uploaderUrl, event.uploader), engagement, Double::plus)
                // a stream that was opened several times counts as seen even if it was never
                // finished in one sitting
                val fraction = if (event.watchCount >= 3) 1.0 else watchFraction(event)
                watched.merge(event.url, fraction, ::maxOf)
            }

            // an explicit search is a stronger statement of interest than a single watch
            for (search in searches) {
                val weight = 1.5 * recency(search.epochMillis, nowMillis)
                for (term in Tokenizer.terms(search.query)) {
                    terms.merge(term, weight, Double::plus)
                    support.merge(term, 1, Int::plus)
                    searched.add(term)
                }
            }

            val subscribed = HashSet<String>()
            for (subscription in subscriptions) {
                val key = channelKey(subscription.url, subscription.name)
                subscribed.add(key)
                channels.merge(key, 0.5, Double::plus)
            }

            val negativeTerms = HashMap<String, Double>()
            val channelPenalties = HashMap<String, Double>()
            val blockedChannels = HashSet<String>()
            val blockedTopics = HashSet<String>()
            val hiddenUrls = HashSet<String>()
            for (item in feedback) {
                when (item.kind) {
                    FeedbackKind.VIDEO -> {
                        hiddenUrls.add(item.key)
                        for (term in Tokenizer.terms(item.title)) {
                            negativeTerms.merge(term, 0.5, Double::plus)
                        }
                        item.channel?.let { channelPenalties.merge(it, 0.34, Double::plus) }
                    }

                    FeedbackKind.CHANNEL -> blockedChannels.add(item.key)

                    FeedbackKind.TOPIC -> {
                        val topic = item.key.lowercase()
                        blockedTopics.add(topic)
                        negativeTerms.merge(topic, 1.0, Double::plus)
                        Tokenizer.words(topic).forEach { negativeTerms.merge(it, 1.0, Double::plus) }
                    }
                }
            }
            // one dislike must not outweigh an interest the user demonstrated many times
            for ((term, dislike) in negativeTerms) {
                val interest = terms[term] ?: continue
                if (term in blockedTopics || dislike >= interest) {
                    terms.remove(term)
                } else {
                    terms[term] = interest - dislike
                }
            }
            blockedChannels.forEach(channels::remove)

            val seeds = history
                .filter { it.url !in hiddenUrls && channelKey(it.uploaderUrl, it.uploader) !in blockedChannels }
                .sortedByDescending { engagement(it, nowMillis) }
                .take(MAX_SEEDS)

            val termWeights = normalise(terms, MAX_TERMS)
            return InterestProfile(
                termWeights = termWeights,
                channelWeights = normalise(channels, Int.MAX_VALUE),
                subscribedChannels = subscribed,
                negativeTerms = negativeTerms.mapValues { it.value.coerceAtMost(1.0) },
                channelPenalties = channelPenalties.mapValues { it.value.coerceAtMost(1.0) },
                blockedChannels = blockedChannels,
                blockedTopics = blockedTopics,
                hiddenUrls = hiddenUrls,
                watched = watched,
                topics = discoverTopics(termWeights, terms, support, searched),
                seeds = seeds
            )
        }

        /**
         * Dampens the raw sums logarithmically (so that one binge does not drown everything
         * else), keeps the [limit] strongest entries and scales them to `(0, 1]`.
         */
        private fun normalise(raw: Map<String, Double>, limit: Int): Map<String, Double> {
            val damped = raw.entries
                .filter { it.value > 0 }
                .map { it.key to ln(1.0 + it.value) }
                .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
                .take(limit)
            val max = damped.firstOrNull()?.second ?: return emptyMap()
            return damped.associate { it.first to it.second / max }
        }

        /**
         * Picks the topics shown to the user and searched for new content. A term only becomes
         * a topic if it came up repeatedly or was searched for explicitly, since a word from a
         * single title says little. A phrase is preferred over its words when the words mostly
         * occur together ("cast iron" rather than "cast" and "iron").
         */
        private fun discoverTopics(
            weights: Map<String, Double>,
            raw: Map<String, Double>,
            support: Map<String, Int>,
            searched: Set<String>
        ): List<String> {
            val ranked = weights.entries
                .filter { (support[it.key] ?: 0) >= 2 || it.key in searched }
                .sortedWith(
                    compareByDescending<Map.Entry<String, Double>> {
                        // phrases are more specific, so they win ties against their words
                        it.value * if (' ' in it.key) 1.25 else 1.0
                    }.thenBy { it.key }
                )
            val topics = ArrayList<String>()
            val inPhrase = HashSet<String>()
            for ((term, _) in ranked) {
                val words = term.split(' ')
                if (words.size == 2) {
                    // only a real phrase if its words usually come together
                    val together = raw[term] ?: 0.0
                    val apart = words.minOf { raw[it] ?: 0.0 }
                    if (apart <= 0.0 || together / apart < 0.6 || words.any { it in inPhrase }) {
                        continue
                    }
                    topics.removeAll(words.toSet())
                    topics.add(term)
                    inPhrase.addAll(words)
                } else if (term !in inPhrase) {
                    topics.add(term)
                }
            }
            if (topics.size > MAX_TOPICS) {
                topics.subList(MAX_TOPICS, topics.size).clear()
            }
            return topics
        }
    }
}
