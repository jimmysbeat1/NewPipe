/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.recommend.engine

import kotlinx.serialization.Serializable

/**
 * A stream the user watched, as recorded in the local watch history.
 *
 * @param progressMillis the saved playback position, or `null` when no position was stored
 */
data class WatchEvent(
    val url: String,
    val title: String,
    val uploader: String,
    val uploaderUrl: String?,
    val durationSeconds: Long,
    val progressMillis: Long?,
    val watchCount: Long,
    val lastWatchedEpochMillis: Long,
    val serviceId: Int = 0
)

/** A search the user ran, as recorded in the local search history. */
data class SearchEvent(
    val query: String,
    val epochMillis: Long
)

/** A channel the user subscribed to. */
data class SubscribedChannel(
    val url: String,
    val name: String
)

/** Where a recommendation candidate came from. */
enum class CandidateSource {
    /** A recent upload of a subscribed channel. */
    SUBSCRIPTION,

    /** Related to something the user watched. */
    RELATED,

    /** Found by searching for one of the user's topics. */
    TOPIC,

    /** Currently trending; used for cold start and exploration. */
    TRENDING
}

/** A stream that could be recommended. */
@Serializable
data class Candidate(
    val serviceId: Int,
    val url: String,
    val title: String,
    val uploader: String,
    val uploaderUrl: String?,
    val durationSeconds: Long,
    val viewCount: Long,
    val uploadEpochMillis: Long?,
    val thumbnailUrl: String?,
    val streamType: String,
    val source: CandidateSource,
    /** The topic or title that led to this candidate, if any. */
    val seed: String? = null,
    /** Whether the service flagged this stream as short-form content. */
    val isShort: Boolean = false
)

enum class FeedbackKind {
    /** "Not interested" on a single video. */
    VIDEO,

    /** "Don't recommend this channel". */
    CHANNEL,

    /** "Not interested" in a whole topic. */
    TOPIC
}

/**
 * Negative feedback given by the user.
 *
 * @param key the stream url, the channel key (see [channelKey]) or the topic
 * @param title the title of the stream for [FeedbackKind.VIDEO], used to learn disliked terms
 * @param channel the channel key of the stream for [FeedbackKind.VIDEO]
 */
@Serializable
data class Feedback(
    val kind: FeedbackKind,
    val key: String,
    val title: String? = null,
    val channel: String? = null,
    val epochMillis: Long = 0
)

/** Why an item was recommended; shown to the user below the title. */
enum class Reason { SUBSCRIPTION, TOPIC, CHANNEL, RELATED, TRENDING, FRESH }

data class Recommendation(
    val candidate: Candidate,
    val score: Double,
    val reason: Reason,
    /** The topic matched for [Reason.TOPIC], or the watched title for [Reason.RELATED]. */
    val reasonDetail: String? = null
)

/** Tuning knobs of the ranker; the defaults are what the app ships with. */
data class RankerConfig(
    val topicWeight: Double = 0.38,
    val channelWeight: Double = 0.20,
    val subscriptionWeight: Double = 0.10,
    val freshnessWeight: Double = 0.16,
    val popularityWeight: Double = 0.08,
    val negativeWeight: Double = 0.60,
    /** Days after which the freshness of an upload has dropped to 1/e. */
    val freshnessDecayDays: Double = 14.0,
    /** A stream watched at least this far is considered seen and is not recommended again. */
    val watchedFraction: Double = 0.85,
    /** Streams up to this length are treated as shorts and dropped when [hideShorts] is set. */
    val shortsMaxSeconds: Long = 60,
    val hideShorts: Boolean = true,
    /** How strongly similarity to already picked items is penalised (0 = no diversification). */
    val diversityLambda: Double = 0.35,
    /** Maximum number of items of one channel within [channelCapWindow] consecutive items. */
    val channelCap: Int = 2,
    val channelCapWindow: Int = 12,
    /** Every n-th slot is reserved for an item outside the user's known interests. */
    val explorationEvery: Int = 7
)

/** The key identifying a channel: its url when known, its lowercased name otherwise. */
fun channelKey(uploaderUrl: String?, uploader: String?): String {
    val url = uploaderUrl?.trim()?.trimEnd('/')?.lowercase().orEmpty()
    return url.ifEmpty { "name:" + uploader.orEmpty().trim().lowercase() }
}
