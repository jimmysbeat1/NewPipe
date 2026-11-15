/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.recommend.data

import android.content.Context
import java.time.OffsetDateTime
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.recommend.engine.CandidateSource
import org.schabi.newpipe.recommend.engine.SearchEvent
import org.schabi.newpipe.recommend.engine.SubscribedChannel
import org.schabi.newpipe.recommend.engine.WatchEvent

/**
 * Reads the signals from the regular NewPipe database: the watch history with playback
 * positions, the search history, the subscriptions and the subscription feed. The data is only
 * read, never copied elsewhere, so clearing the history in the settings also clears what the
 * recommendations know.
 */
class DatabaseSignalSource(context: Context) : SignalSource {
    private val database = NewPipeDatabase.getInstance(context)

    override fun load(): Signals {
        val history = database.streamHistoryDAO().getStatistics().blockingFirst().map {
            WatchEvent(
                url = it.streamEntity.url,
                title = it.streamEntity.title,
                uploader = it.streamEntity.uploader,
                uploaderUrl = it.streamEntity.uploaderUrl,
                durationSeconds = it.streamEntity.duration,
                // the query yields 0 when no playback position was stored
                progressMillis = it.progressMillis.takeIf { progress -> progress > 0 },
                watchCount = it.watchCount,
                lastWatchedEpochMillis = it.latestAccessDate.toInstant().toEpochMilli(),
                serviceId = it.streamEntity.serviceId
            )
        }

        val searches = database.searchHistoryDAO().getAll().blockingFirst()
            .take(MAX_SEARCHES)
            .mapNotNull { entry ->
                val query = entry.search?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SearchEvent(query, entry.creationDate?.toInstant()?.toEpochMilli() ?: 0)
            }

        val subscriptions = database.subscriptionDAO().getAll().blockingFirst().mapNotNull {
            val url = it.url ?: return@mapNotNull null
            SubscribedChannel(url, it.name.orEmpty())
        }

        val newerThan = OffsetDateTime.now().minusDays(FEED_DAYS)
        val uploads = database.feedDAO()
            .getStreams(FeedGroupEntity.GROUP_ALL_ID, true, true, null)
            .blockingGet()
            .orEmpty()
            .filter { it.stream.uploadDate?.isAfter(newerThan) ?: false }
            .map { it.stream.toStreamInfoItem().toCandidate(CandidateSource.SUBSCRIPTION) }

        return Signals(history, searches, subscriptions, uploads)
    }

    companion object {
        private const val MAX_SEARCHES = 200
        private const val FEED_DAYS = 21L
    }
}
