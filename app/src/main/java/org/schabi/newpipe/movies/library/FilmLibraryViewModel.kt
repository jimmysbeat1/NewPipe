/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.movies.Genre
import org.schabi.newpipe.recommend.data.PersonalStore

class FilmLibraryViewModel(application: Application) : AndroidViewModel(application) {

    /** What the grid is narrowed to. */
    sealed class Filter {
        object All : Filter()

        object Downloaded : Filter()

        data class OfGenre(val genre: Genre) : Filter()
    }

    data class State(
        val loading: Boolean = true,
        /** Search results while [query] is set, the library narrowed to [filter] otherwise. */
        val films: List<Film> = emptyList(),
        val genres: List<Genre> = emptyList(),
        val filter: Filter = Filter.All,
        val query: String = "",
        val error: Throwable? = null
    )

    private val repository = FilmLibraryRepository(
        PersonalStore.get(application),
        InternetArchiveSource(FilmNetwork.http),
        CommonsSource(FilmNetwork.http)
    )
    private val downloads = FilmDownloads.get(application)

    private val mutableState = MutableLiveData(State())
    val state: LiveData<State> = mutableState
    private var job: Job? = null
    private var loadMoreJob: Job? = null
    private var library: List<Film> = emptyList()

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

    private fun filtered(filter: Filter): List<Film> {
        return when (filter) {
            Filter.All -> library
            Filter.Downloaded -> downloads.all().values.map { it.film }
            is Filter.OfGenre -> library.filter { filter.genre in it.genres }
        }
    }

    private suspend fun publish(loading: Boolean, error: Throwable? = null) {
        library = io { FilmLibraryRepository.arrange(repository.cache().films) }
        val genres = Genre.entries.filter { genre -> library.any { genre in it.genres } }
        val filter = current.filter.takeIf { it !is Filter.OfGenre || it.genre in genres } ?: Filter.All
        val searching = current.query.isNotBlank()
        mutableState.value = current.copy(
            loading = loading,
            films = if (searching) current.films else filtered(filter),
            genres = genres,
            filter = filter,
            error = error.takeIf { library.isEmpty() && !searching }
        )
    }

    fun load(force: Boolean) = launch {
        mutableState.value = current.copy(loading = true, error = null)
        publish(loading = true)
        if (!force && !io { repository.isStale() }) {
            mutableState.value = current.copy(loading = false)
            return@launch
        }
        val outcome = io { repository.refresh() }
        publish(loading = false, error = outcome.error)
    }

    /** Extends the library once the user reaches the end of the list. */
    fun loadMore() {
        val filter = current.filter
        if (loadMoreJob?.isActive == true || current.loading || current.query.isNotBlank() ||
            filter == Filter.Downloaded
        ) {
            return
        }
        val genre = (filter as? Filter.OfGenre)?.genre
        loadMoreJob = viewModelScope.launch {
            try {
                if (io { repository.canLoadMore(genre) } && io { repository.loadMore(genre) }.added > 0) {
                    publish(loading = current.loading)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // more films are a bonus; the list simply ends here
            }
        }
    }

    fun select(filter: Filter) {
        mutableState.value = current.copy(filter = filter, films = filtered(filter))
        // a genre with only a few known films is topped up from the source
        if (filter is Filter.OfGenre && filtered(filter).size < FEW_FILMS) {
            loadMore()
        }
    }

    /** Called when the set of downloads changes, to keep the "Downloaded" view current. */
    fun downloadsChanged() {
        if (current.filter == Filter.Downloaded && current.query.isBlank()) {
            mutableState.value = current.copy(films = filtered(Filter.Downloaded))
        }
    }

    fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            clearSearch()
            return
        }
        launch {
            // matches from the library show up immediately, results from the sources follow
            mutableState.value = current.copy(
                loading = true,
                query = trimmed,
                films = FilmRules.search(library, trimmed),
                error = null
            )
            val (results, error) = io { repository.search(trimmed) }
            mutableState.value = current.copy(
                loading = false,
                films = results,
                error = error.takeIf { results.isEmpty() }
            )
        }
    }

    fun clearSearch() {
        job?.cancel()
        mutableState.value = current.copy(
            loading = false,
            query = "",
            films = filtered(current.filter),
            error = null
        )
    }

    companion object {
        private const val FEW_FILMS = 12
    }
}
