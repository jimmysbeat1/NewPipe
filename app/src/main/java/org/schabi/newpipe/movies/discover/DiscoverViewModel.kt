/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.discover

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DiscoverViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * @param movies the films of the section, or the search results while [query] is set
     * @param nextPage the TMDB page that continues the list, or `null` at its end
     * @param hidden how many matches of a search are not shown because they cannot be watched
     * @param problem why loading failed; films that were loaded earlier may still be shown
     */
    data class State(
        val hasKey: Boolean = false,
        val section: Section = Section.NEW_RELEASES,
        val filters: Filters = Filters(),
        val query: String = "",
        val loading: Boolean = true,
        val movies: List<ListedMovie> = emptyList(),
        val watchlistIds: Set<Int> = emptySet(),
        val nextPage: Int? = null,
        val hidden: Int = 0,
        val problem: TmdbProblem? = null
    )

    private val repository = Discover.repository(application)
    private val credentials = TmdbCredentials.get(application)

    private val mutableState = MutableLiveData(State())
    val state: LiveData<State> = mutableState
    private var job: Job? = null
    private var moreJob: Job? = null

    private val current: State
        get() = mutableState.value ?: State()

    init {
        viewModelScope.launch {
            val preferences = io { repository.preferences() }
            mutableState.value = current.copy(section = preferences.section, filters = preferences.filters)
            load(false)
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun problemOf(error: Exception): TmdbProblem {
        return (error as? TmdbException)?.problem ?: TmdbProblem.NETWORK
    }

    private fun launch(block: suspend () -> Unit) {
        job?.cancel()
        moreJob?.cancel()
        job = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                mutableState.value = current.copy(loading = false, problem = problemOf(e))
            }
        }
    }

    private fun savePreferences() {
        val preferences = DiscoverPreferences(current.filters, current.section)
        viewModelScope.launch { io { repository.savePreferences(preferences) } }
    }

    /** Shows the current section: from the cache at once, and from TMDB when that is out of date. */
    fun load(force: Boolean) = launch {
        val section = current.section
        val filters = current.filters
        val hasKey = io { credentials.has() }
        val watchlistIds = io { repository.watchlist().map { it.movie.id }.toSet() }
        mutableState.value = current.copy(
            hasKey = hasKey,
            watchlistIds = watchlistIds,
            query = "",
            loading = true,
            hidden = 0,
            problem = null
        )

        if (section == Section.DOWNLOADED) {
            // the downloads are shown from the download list, not from here
            mutableState.value = current.copy(loading = false, movies = emptyList(), nextPage = null)
            return@launch
        }
        if (section == Section.WATCHLIST) {
            val stored = io { repository.watchlistMovies(false) }
            mutableState.value = current.copy(movies = stored, nextPage = null)
            val movies = io { repository.watchlistMovies(hasKey) }
            mutableState.value = current.copy(loading = false, movies = movies)
            return@launch
        }
        if (!hasKey) {
            mutableState.value = current.copy(
                loading = false,
                movies = emptyList(),
                nextPage = null,
                problem = TmdbProblem.NO_KEY
            )
            return@launch
        }
        val cached = io { repository.cached(section, filters) }
        mutableState.value = current.copy(movies = cached?.movies.orEmpty(), nextPage = cached?.nextPage)
        if (force || io { repository.isStale(cached) }) {
            val batch = io { repository.load(section, filters) }
            mutableState.value = current.copy(movies = batch.movies, nextPage = batch.nextPage)
        }
        mutableState.value = current.copy(loading = false)
    }

    /** Continues the list once the user reaches its end. */
    fun loadMore() {
        val state = current
        val page = state.nextPage ?: return
        if (moreJob?.isActive == true || state.loading || !state.section.online && state.query.isBlank()) {
            return
        }
        moreJob = viewModelScope.launch {
            try {
                val batch = io {
                    if (state.query.isNotBlank()) {
                        repository.search(state.query, state.filters, page)
                    } else {
                        repository.load(state.section, state.filters, page)
                    }
                }
                mutableState.value = current.copy(
                    movies = (current.movies + batch.movies).distinctBy { it.movie.id },
                    nextPage = batch.nextPage,
                    hidden = current.hidden + batch.hidden
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // more films are a bonus; the list simply ends here for now
                mutableState.value = current.copy(nextPage = null)
            }
        }
    }

    fun select(section: Section) {
        if (section == current.section && current.query.isBlank()) {
            return
        }
        mutableState.value = current.copy(section = section, movies = emptyList(), nextPage = null)
        savePreferences()
        load(false)
    }

    fun setFilters(filters: Filters) {
        if (filters == current.filters) {
            return
        }
        mutableState.value = current.copy(filters = filters)
        savePreferences()
        val query = current.query
        if (query.isNotBlank()) search(query) else load(false)
    }

    fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            clearSearch()
            return
        }
        launch {
            val hasKey = io { credentials.has() }
            mutableState.value = current.copy(
                hasKey = hasKey,
                query = trimmed,
                loading = true,
                movies = emptyList(),
                nextPage = null,
                hidden = 0,
                problem = null
            )
            if (!hasKey) {
                mutableState.value = current.copy(loading = false, problem = TmdbProblem.NO_KEY)
                return@launch
            }
            val batch = io { repository.search(trimmed, current.filters) }
            mutableState.value = current.copy(
                loading = false,
                movies = batch.movies,
                nextPage = batch.nextPage,
                hidden = batch.hidden
            )
        }
    }

    fun clearSearch() {
        if (current.query.isNotBlank()) {
            load(false)
        }
    }

    /** Called after the key was entered, replaced or removed. */
    fun keyChanged() {
        viewModelScope.launch {
            io { repository.clearCaches() }
            load(true)
        }
    }

    /** Called after the watchlist was changed from the details of a film. */
    fun watchlistChanged() {
        viewModelScope.launch {
            val entries = io { repository.watchlistMovies(false) }
            val ids = entries.map { it.movie.id }.toSet()
            mutableState.value = if (current.section == Section.WATCHLIST && current.query.isBlank()) {
                current.copy(watchlistIds = ids, movies = entries)
            } else {
                current.copy(watchlistIds = ids)
            }
        }
    }
}
