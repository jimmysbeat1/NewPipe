/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.recommend

import androidx.test.core.app.ApplicationProvider
import java.time.OffsetDateTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.feed.model.FeedEntity
import org.schabi.newpipe.database.history.model.SearchHistoryEntry
import org.schabi.newpipe.database.history.model.StreamHistoryEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.stream.model.StreamStateEntity
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.recommend.data.DatabaseSignalSource
import org.schabi.newpipe.recommend.engine.CandidateSource
import org.schabi.newpipe.recommend.engine.InterestProfile
import org.schabi.newpipe.testUtil.TestDatabase

class DatabaseSignalSourceTest {
    private lateinit var database: AppDatabase
    private val now = OffsetDateTime.now()

    @Before
    fun setup() {
        database = TestDatabase.createReplacingNewPipeDatabase()
    }

    @After
    fun cleanUp() {
        database.close()
    }

    private fun stream(id: String, title: String, uploadDate: OffsetDateTime? = null): Long {
        return database.streamDAO().insert(
            StreamEntity(
                serviceId = 0,
                url = "https://www.youtube.com/watch?v=$id",
                title = title,
                streamType = StreamType.VIDEO_STREAM,
                duration = 600,
                uploader = "KeebLab",
                uploaderUrl = "https://www.youtube.com/channel/keeblab",
                uploadDate = uploadDate
            )
        )
    }

    @Test
    fun emptyDatabaseGivesNoSignals() {
        val signals = DatabaseSignalSource(ApplicationProvider.getApplicationContext()).load()
        assertThat(signals.history).isEmpty()
        assertThat(signals.searches).isEmpty()
        assertThat(signals.subscriptions).isEmpty()
        assertThat(signals.subscriptionUploads).isEmpty()
    }

    @Test
    fun readsHistoryWithProgressSearchesSubscriptionsAndFeed() {
        val finished = stream("a", "Mechanical keyboard build")
        val unknown = stream("b", "Mechanical keyboard switches")
        database.streamHistoryDAO().insert(StreamHistoryEntity(finished, now.minusDays(1), 2))
        database.streamHistoryDAO().insert(StreamHistoryEntity(unknown, now.minusDays(2), 1))
        database.streamStateDAO().insert(StreamStateEntity(finished, 540_000))

        database.searchHistoryDAO().insert(SearchHistoryEntry(now, 0, "keyboard switches"))

        val subscription = SubscriptionEntity()
        subscription.serviceId = 0
        subscription.url = "https://www.youtube.com/channel/keeblab"
        subscription.name = "KeebLab"
        val subscriptionId = database.subscriptionDAO().insert(subscription)

        val fresh = stream("c", "New keyboard video", now.minusDays(2))
        val old = stream("d", "Ancient keyboard video", now.minusDays(200))
        database.feedDAO().insert(FeedEntity(fresh, subscriptionId))
        database.feedDAO().insert(FeedEntity(old, subscriptionId))

        val signals = DatabaseSignalSource(ApplicationProvider.getApplicationContext()).load()

        assertThat(signals.history).hasSize(2)
        val watched = signals.history.first { it.url.endsWith("=a") }
        assertThat(watched.title).isEqualTo("Mechanical keyboard build")
        assertThat(watched.watchCount).isEqualTo(2)
        assertThat(watched.progressMillis).isEqualTo(540_000L)
        assertThat(InterestProfile.watchFraction(watched)).isEqualTo(0.9)
        // no playback position stored: unknown, not "watched 0 %"
        assertThat(signals.history.first { it.url.endsWith("=b") }.progressMillis).isNull()

        assertThat(signals.searches.map { it.query }).containsExactly("keyboard switches")
        assertThat(signals.subscriptions.map { it.name }).containsExactly("KeebLab")

        // only recent uploads are candidates
        assertThat(signals.subscriptionUploads.map { it.title }).containsExactly("New keyboard video")
        assertThat(signals.subscriptionUploads[0].source).isEqualTo(CandidateSource.SUBSCRIPTION)

        val profile = InterestProfile.build(
            signals.history,
            signals.searches,
            signals.subscriptions,
            emptyList(),
            System.currentTimeMillis()
        )
        assertThat(profile.topics).contains("mechanical keyboard")
    }
}
