/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.discover

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.DialogMovieDetailBinding
import org.schabi.newpipe.error.ErrorUtil
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.movies.Movie
import org.schabi.newpipe.movies.MovieCatalogCache
import org.schabi.newpipe.movies.OfficialPlayback
import org.schabi.newpipe.movies.library.Film
import org.schabi.newpipe.movies.library.FilmDetailDialog
import org.schabi.newpipe.movies.library.FilmDownloads
import org.schabi.newpipe.movies.library.FilmLibraryCache
import org.schabi.newpipe.movies.library.FilmPosters
import org.schabi.newpipe.recommend.data.PersonalStore
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.external_communication.ShareUtils

/**
 * Everything about one film from TMDB: what it is, where it can be watched in Australia and at
 * what kind of cost, and the ways to get there.
 *
 * The main button always leads to an official provider. Playback inside the app is offered only
 * for what the app may play: the trailer, and the film itself when it is part of the
 * open-licence library or published by a distributor on YouTube.
 */
class MovieDetailDialog : DialogFragment() {
    private var binding: DialogMovieDetailBinding? = null
    private val scope: CoroutineScope = MainScope()
    private lateinit var movie: TmdbMovie
    private var availability: Availability? = null
    private var trailers: List<Trailer> = emptyList()
    private var onWatchlist = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val listed = JSON.decodeFromString(ListedMovie.serializer(), requireArguments().getString(ARGUMENT_MOVIE).orEmpty())
        movie = listed.movie
        availability = listed.availability
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogMovieDetailBinding.inflate(requireActivity().layoutInflater).also { this.binding = it }
        val context = requireContext()
        with(binding) {
            moviePoster.clipToOutline = true
            FilmPosters.load(moviePoster, DiscoverFormat.posterUrl(movie))
            movieWatch.setOnClickListener { watch() }
            movieTrailer.setOnClickListener { playTrailer() }
            movieWatchlist.setOnClickListener { toggleWatchlist() }
            movieTmdb.setOnClickListener { ShareUtils.openUrlInBrowser(context, ProviderLinks.tmdbPage(movie.id)) }
        }
        render(null, loading = true)
        load()
        return AlertDialog.Builder(context)
            .setView(binding.root)
            .setNegativeButton(R.string.film_close, null)
            .create()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** What the app can play itself for this film, found among the films it already knows. */
    private class Local(val film: Film?, val youtube: Movie?)

    private fun load() {
        val context = requireContext().applicationContext
        val repository = Discover.repository(context)
        val id = movie.id
        scope.launch {
            onWatchlist = withContext(Dispatchers.IO) { repository.isOnWatchlist(id) }
            val local = withContext(Dispatchers.IO) { findLocal(PersonalStore.get(context), FilmDownloads.get(context)) }
            renderWatchlist()
            renderLocal(local)

            val details = withContext(Dispatchers.IO) {
                try {
                    repository.details(id)
                } catch (e: IOException) {
                    null
                }
            }
            if (details != null) {
                movie = details.movie
                availability = details.availability
                trailers = details.trailers
            }
            render(details, loading = false)
        }
    }

    private fun findLocal(store: PersonalStore, downloads: FilmDownloads): Local {
        val films = downloads.all().values.map { it.film } +
            store.read("film_library", FilmLibraryCache.serializer(), FilmLibraryCache()).films
        val catalog = store.read("movie_catalog", MovieCatalogCache.serializer(), MovieCatalogCache()).movies
        return Local(LocalMatches.film(movie, films), LocalMatches.youtube(movie, catalog))
    }

    private fun facts(details: MovieDetails?): String {
        val parts = ArrayList<String>(2)
        DiscoverFormat.details(movie).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        val minutes = details?.runtimeMinutes ?: 0
        if (minutes > 0) {
            parts.add(
                if (minutes >= 60) {
                    getString(R.string.discover_detail_runtime, minutes / 60, minutes % 60)
                } else {
                    getString(R.string.movies_duration_minutes, minutes)
                }
            )
        }
        return parts.joinToString(" • ")
    }

    private fun render(details: MovieDetails?, loading: Boolean) {
        val binding = binding ?: return
        val context = requireContext()
        val availability = availability
        with(binding) {
            movieTitle.text = movie.title
            movieFacts.text = facts(details)
            movieGenres.text = details?.genres.orEmpty().joinToString(", ")
            movieGenres.isVisible = !details?.genres.isNullOrEmpty()
            movieOverview.text = movie.overview
            movieOverview.isVisible = !movie.overview.isNullOrBlank()
            movieBadge.text = DiscoverFormat.badge(context, availability)

            val best = availability?.best
            movieOffers.text = when {
                availability == null && loading -> getString(R.string.discover_detail_loading)
                availability == null -> getString(R.string.discover_detail_failed)
                best == null -> getString(R.string.discover_detail_none)
                else -> availability?.let { DiscoverFormat.offers(context, it) }.orEmpty()
            }

            val provider = availability?.bestProviders()?.firstOrNull()
            movieWatch.isVisible = best != null && provider != null
            movieWatchNote.isVisible = movieWatch.isVisible
            if (best != null && provider != null) {
                movieWatch.text = getString(
                    when (best) {
                        Access.FREE -> R.string.discover_watch_free
                        Access.FREE_WITH_ADS -> R.string.discover_watch_ads
                        Access.SUBSCRIPTION -> R.string.discover_watch_subscription
                        Access.RENT -> R.string.discover_watch_rent
                        Access.BUY -> R.string.discover_watch_buy
                    },
                    provider
                )
                movieWatchNote.text = if (ProviderLinks.providerSearch(provider, movie) != null) {
                    getString(R.string.discover_watch_note_direct, provider)
                } else {
                    getString(R.string.discover_watch_note_page)
                }
            }

            movieTrailer.isVisible = trailers.isNotEmpty()
            movieChecked.isVisible = availability != null
            if (availability != null) {
                val checked = DateUtils.formatDateTime(
                    context,
                    availability.fetchedAt,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR
                )
                movieChecked.text = getString(R.string.discover_checked, checked)
            }
            if (!loading && details == null && availability != null) {
                movieChecked.text = getString(R.string.discover_detail_failed) + " " + movieChecked.text
            }
        }
    }

    private fun renderWatchlist() {
        binding?.movieWatchlist?.setText(
            if (onWatchlist) R.string.movies_watchlist_remove else R.string.movies_watchlist_add
        )
    }

    private fun renderLocal(local: Local) {
        val binding = binding ?: return
        val film = local.film
        val youtube = local.youtube
        when {
            film != null -> {
                binding.movieLocal.setText(R.string.discover_local_film)
                binding.movieLocal.setOnClickListener {
                    FilmDetailDialog.newInstance(film).show(parentFragmentManager, LOCAL_TAG)
                    dismissAllowingStateLoss()
                }
            }

            youtube != null -> {
                binding.movieLocal.text = getString(R.string.discover_local_youtube, youtube.channel)
                binding.movieLocal.setOnClickListener { playInApp(youtube.serviceId, youtube.url, youtube.title) }
            }
        }
        binding.movieLocal.isVisible = film != null || youtube != null
        // a film the app may download has its own details with the download controls
        binding.movieDownloadNote.isVisible = film == null
    }

    // ------------------------------------------------------------------- actions

    /** Opens the official provider, or the page that links to it. Nothing is played in the app. */
    private fun watch() {
        val context = requireContext()
        val provider = availability?.bestProviders()?.firstOrNull() ?: return
        val search = ProviderLinks.providerSearch(provider, movie)
        when {
            search == null ->
                ShareUtils.openUrlInBrowser(
                    context,
                    ProviderLinks.watchPage(movie.id, availability, DiscoverQuery.REGION)
                )

            ProviderLinks.isYoutube(provider) -> OfficialPlayback.open(context, search)

            else -> openInProviderApp(search)
        }
    }

    /** Lets the app of the provider handle the link if it is installed, the browser otherwise. */
    private fun openInProviderApp(url: String) {
        val context = requireContext()
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            ShareUtils.openUrlInBrowser(context, url)
        } catch (e: SecurityException) {
            ShareUtils.openUrlInBrowser(context, url)
        }
    }

    private fun playTrailer() {
        val trailer = trailers.firstOrNull() ?: return
        playInApp(ServiceList.YouTube.serviceId, trailer.url, movie.title + " • " + trailer.name)
    }

    private fun playInApp(serviceId: Int, url: String, title: String) {
        val activity = requireActivity()
        try {
            NavigationHelper.openVideoDetailFragment(
                activity,
                activity.supportFragmentManager,
                serviceId,
                url,
                title,
                null,
                false
            )
            dismissAllowingStateLoss()
        } catch (e: Exception) {
            ErrorUtil.showUiErrorSnackbar(activity, "Opening video detail fragment", e)
        }
    }

    private fun toggleWatchlist() {
        val context = requireContext().applicationContext
        val repository = Discover.repository(context)
        val movie = movie
        val availability = availability
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    repository.toggleWatchlist(movie, availability)
                } catch (e: IOException) {
                    null
                }
            }
            if (result == null) {
                Toast.makeText(context, R.string.discover_watchlist_failed, Toast.LENGTH_SHORT).show()
            } else {
                onWatchlist = result
                renderWatchlist()
                if (isAdded) {
                    parentFragmentManager.setFragmentResult(RESULT, Bundle.EMPTY)
                }
            }
        }
    }

    companion object {
        const val RESULT = "movie_watchlist_changed"
        private const val ARGUMENT_MOVIE = "movie"
        private const val LOCAL_TAG = "film_detail"
        private val JSON = Json { ignoreUnknownKeys = true }

        fun newInstance(listed: ListedMovie): MovieDetailDialog {
            return MovieDetailDialog().apply {
                arguments = bundleOf(ARGUMENT_MOVIE to JSON.encodeToString(ListedMovie.serializer(), listed))
            }
        }
    }
}
