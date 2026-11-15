/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies

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
import org.schabi.newpipe.recommend.data.PersonalStore

class MoviesViewModel(application: Application) : AndroidViewModel(application) {

    data class State(
        val loading: Boolean = true,
        /** The films to show: search results while [query] is set, the catalog otherwise. */
        val movies: List<Movie> = emptyList(),
        val genres: List<Genre> = emptyList(),
        val genre: Genre? = null,
        val query: String = "",
        val watchlist: List<Movie> = emptyList(),
        val purchases: List<Movie> = emptyList(),
        val error: Throwable? = null
    ) {
        val watchlistUrls: Set<String> by lazy { watchlist.mapTo(HashSet()) { it.url } }
    }

    private val repository = MoviesRepository(PersonalStore.get(application), MovieFetcher())
    private val preferences = PreferenceManager.getDefaultSharedPreferences(application)

    private val mutableState = MutableLiveData(State())
    val state: LiveData<State> = mutableState
    private var job: Job? = null
    private var loadMoreJob: Job? = null
    private var catalog: List<Movie> = emptyList()

    var includeUnverified: Boolean
        get() = preferences.getBoolean(KEY_INCLUDE_UNVERIFIED, false)
        set(value) {
            preferences.edit { putBoolean(KEY_INCLUDE_UNVERIFIED, value) }
            if (current.query.isNotBlank()) {
                search(current.query)
            }
        }

    private val current: State
        get() = mutableState.value ?: State()

    init {
        load(false)
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

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /** Publishes the catalog (narrowed to the selected genre) and the personal lists. */
    private suspend fun publishCatalog(loading: Boolean, error: Throwable? = null) {
        val cache = io { repository.catalog() }
        val watchlist = io { repository.watchlist() }
        val purchases = io { repository.purchases() }
        catalog = io { MovieCatalog.arrange(cache.movies) }
        val genres = Genre.entries.filter { genre -> catalog.any { genre in it.genres } }
        val genre = current.genre?.takeIf { it in genres }
        val searching = current.query.isNotBlank()
        mutableState.value = current.copy(
            loading = loading,
            movies = if (searching) current.movies else filtered(genre),
            genres = genres,
            genre = genre,
            watchlist = watchlist,
            purchases = purchases,
            error = error.takeIf { catalog.isEmpty() && !searching }
        )
    }

    private fun filtered(genre: Genre?): List<Movie> {
        return if (genre == null) catalog else catalog.filter { genre in it.genres }
    }

    fun load(force: Boolean) = launch {
        mutableState.value = current.copy(loading = true, error = null)
        publishCatalog(loading = true)
        if (!force && !io { repository.isStale() }) {
            mutableState.value = current.copy(loading = false)
            return@launch
        }
        val outcome = io { repository.refresh() }
        publishCatalog(loading = false, error = outcome.error.takeIf { outcome.nothingLoaded })
    }

    /** Extends the catalog with older films once the user reaches the end of the list. */
    fun loadMore() {
        if (loadMoreJob?.isActive == true || current.loading || current.query.isNotBlank()) {
            return
        }
        loadMoreJob = viewModelScope.launch {
            try {
                if (io { repository.canLoadMore() } && io { repository.loadMore() }.succeeded > 0) {
                    publishCatalog(loading = current.loading)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // older films are a bonus; the list simply ends here
            }
        }
    }

    fun selectGenre(genre: Genre?) {
        mutableState.value = current.copy(genre = genre, movies = filtered(genre))
    }

    fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            clearSearch()
            return
        }
        launch {
            // matches from the catalog show up immediately, online results follow
            mutableState.value = current.copy(
                loading = true,
                query = trimmed,
                movies = MovieCatalog.search(catalog, trimmed),
                error = null
            )
            val unverified = includeUnverified
            val (results, error) = io { repository.search(trimmed, unverified) }
            mutableState.value = current.copy(
                loading = false,
                movies = results,
                error = error.takeIf { results.isEmpty() }
            )
        }
    }

    fun clearSearch() {
        job?.cancel()
        mutableState.value = current.copy(
            loading = false,
            query = "",
            movies = filtered(current.genre),
            error = null
        )
    }

    private fun update(change: () -> Unit) {
        viewModelScope.launch {
            try {
                io(change)
                mutableState.value = current.copy(
                    watchlist = io { repository.watchlist() },
                    purchases = io { repository.purchases() }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = current.copy(error = e)
            }
        }
    }

    fun toggleWatchlist(movie: Movie) = update {
        if (repository.watchlist().any { it.url == movie.url }) {
            repository.removeFromWatchlist(movie.url)
        } else {
            repository.addToWatchlist(movie)
        }
    }

    fun addPurchase(title: String, url: String) = update { repository.addPurchase(title, url) }

    fun removePurchase(movie: Movie) = update { repository.removePurchase(movie.url) }

    companion object {
        const val KEY_INCLUDE_UNVERIFIED = "movies_include_unverified"
    }
}
