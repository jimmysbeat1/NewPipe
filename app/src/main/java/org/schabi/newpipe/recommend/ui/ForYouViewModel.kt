/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.recommend.ui

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.recommend.data.CandidateFetcher
import org.schabi.newpipe.recommend.data.DatabaseSignalSource
import org.schabi.newpipe.recommend.data.FeedSnapshot
import org.schabi.newpipe.recommend.data.PersonalStore
import org.schabi.newpipe.recommend.data.RecommendationRepository
import org.schabi.newpipe.recommend.engine.Feedback
import org.schabi.newpipe.recommend.engine.RankerConfig
import org.schabi.newpipe.recommend.engine.Recommendation
import org.schabi.newpipe.util.ServiceHelper

class ForYouViewModel(application: Application) : AndroidViewModel(application) {

    data class State(
        val loading: Boolean = true,
        val items: List<Recommendation> = emptyList(),
        val topics: List<String> = emptyList(),
        val selectedTopic: String? = null,
        /** `true` while there is no history, search or subscription to learn from. */
        val coldStart: Boolean = false,
        val error: Throwable? = null
    )

    private val repository = RecommendationRepository(
        DatabaseSignalSource(application),
        PersonalStore.get(application),
        CandidateFetcher()
    )
    private val preferences = PreferenceManager.getDefaultSharedPreferences(application)

    private val mutableState = MutableLiveData(State())
    val state: LiveData<State> = mutableState
    private var job: Job? = null

    var hideShorts: Boolean
        get() = preferences.getBoolean(KEY_HIDE_SHORTS, true)
        set(value) {
            preferences.edit { putBoolean(KEY_HIDE_SHORTS, value) }
            rerank()
        }

    private val current: State
        get() = mutableState.value ?: State()

    private val serviceId: Int
        get() = ServiceHelper.getSelectedServiceId(getApplication())

    init {
        load(false)
    }

    private fun config() = RankerConfig(hideShorts = hideShorts)

    private fun publish(snapshot: FeedSnapshot, loading: Boolean, error: Throwable? = null) {
        val topics = snapshot.profile.topics
        mutableState.value = current.copy(
            loading = loading,
            items = snapshot.recommendations,
            topics = topics,
            selectedTopic = current.selectedTopic?.takeIf { it in topics },
            coldStart = snapshot.profile.isEmpty,
            error = error.takeIf { snapshot.recommendations.isEmpty() }
        )
    }

    private fun launch(block: suspend () -> Unit) {
        job?.cancel()
        job = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = current.copy(loading = false, error = e)
            }
        }
    }

    private suspend fun snapshot(): FeedSnapshot {
        val topic = current.selectedTopic
        val config = config()
        return withContext(Dispatchers.IO) { repository.snapshot(config, topic) }
    }

    /**
     * Shows what can be computed from the data on the device right away, then (if the cached
     * candidates are stale or [force] is set) fetches new candidates and ranks again.
     */
    fun load(force: Boolean) = launch {
        mutableState.value = current.copy(loading = true, error = null)
        publish(snapshot(), loading = true)

        val service = serviceId
        val refresh = force || withContext(Dispatchers.IO) { repository.isStale() }
        if (!refresh) {
            mutableState.value = current.copy(loading = false)
            return@launch
        }
        val outcome = withContext(Dispatchers.IO) { repository.refresh(service) }
        publish(snapshot(), loading = false, error = outcome.error.takeIf { outcome.nothingLoaded })
    }

    /** Ranks again without network access, e.g. after something was watched. */
    fun rerank() {
        if (current.loading) {
            return // a load is in progress and will publish an up to date ranking anyway
        }
        launch { publish(snapshot(), loading = false) }
    }

    fun selectTopic(topic: String?) = launch {
        mutableState.value = current.copy(selectedTopic = topic, error = null)
        val local = snapshot()
        if (topic == null || local.recommendations.size >= ENOUGH_FOR_TOPIC) {
            publish(local, loading = false)
            return@launch
        }
        publish(local, loading = true)
        val service = serviceId
        val outcome = withContext(Dispatchers.IO) { repository.refreshTopic(service, topic) }
        publish(snapshot(), loading = false, error = outcome.error.takeIf { outcome.nothingLoaded })
    }

    fun addFeedback(feedback: Feedback) = launch {
        withContext(Dispatchers.IO) { repository.addFeedback(feedback) }
        publish(snapshot(), loading = false)
    }

    fun removeFeedback(feedback: Feedback) = launch {
        withContext(Dispatchers.IO) { repository.removeFeedback(feedback) }
        publish(snapshot(), loading = false)
    }

    /** Forgets feedback and cached candidates and starts over. */
    fun reset() {
        job?.cancel()
        job = viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.reset() }
            mutableState.value = State()
            load(true)
        }
    }

    companion object {
        const val KEY_HIDE_SHORTS = "for_you_hide_shorts"
        private const val ENOUGH_FOR_TOPIC = 12
    }
}
