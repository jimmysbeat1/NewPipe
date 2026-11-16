/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.library

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.IOException
import org.schabi.newpipe.BaseFragment
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentForYouBinding
import org.schabi.newpipe.databinding.HeaderFilmLibraryBinding
import org.schabi.newpipe.movies.MoviesFragment
import org.schabi.newpipe.movies.library.FilmLibraryViewModel.Filter
import org.schabi.newpipe.recommend.ui.ChipRow
import org.schabi.newpipe.recommend.ui.SingleViewAdapter

/**
 * The Movies section: full-length films from the Internet Archive and Wikimedia Commons whose
 * licence permits downloading them, with genres, search, downloads and offline playback.
 */
class FilmLibraryFragment : BaseFragment() {
    private var binding: FragmentForYouBinding? = null
    private var header: HeaderFilmLibraryBinding? = null
    private lateinit var viewModel: FilmLibraryViewModel
    private lateinit var downloads: FilmDownloads
    private lateinit var adapter: FilmPosterAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        viewModel = ViewModelProvider(this)[FilmLibraryViewModel::class.java]
        downloads = FilmDownloads.get(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_for_you, container, false)
    }

    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)
        val binding = FragmentForYouBinding.bind(rootView).also { this.binding = it }
        val header = HeaderFilmLibraryBinding.inflate(layoutInflater).also { this.header = it }

        adapter = FilmPosterAdapter(::open)
        val spanCount = (resources.configuration.screenWidthDp / POSTER_MIN_WIDTH_DP).coerceIn(2, 8)
        val layoutManager = GridLayoutManager(requireContext(), spanCount)
        val concat = ConcatAdapter(
            ConcatAdapter.Config.Builder().setIsolateViewTypes(false).build(),
            SingleViewAdapter(header.root),
            adapter
        )
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (concat.getItemViewType(position) == SingleViewAdapter.VIEW_TYPE) spanCount else 1
            }
        }
        binding.itemsList.layoutManager = layoutManager
        binding.itemsList.adapter = concat
        val side = (GRID_SIDE_PADDING_DP * resources.displayMetrics.density).toInt()
        binding.itemsList.setPadding(side, 0, side, binding.itemsList.paddingBottom)
        binding.itemsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 &&
                    layoutManager.findLastVisibleItemPosition() >= concat.itemCount - spanCount * 3
                ) {
                    viewModel.loadMore()
                }
            }
        })

        binding.swipeRefresh.setOnRefreshListener {
            val query = viewModel.state.value?.query.orEmpty()
            if (query.isBlank()) viewModel.load(true) else viewModel.search(query)
        }
        binding.retryButton.setOnClickListener { viewModel.load(true) }

        header.searchInput.setOnEditorActionListener { view, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                viewModel.search(view.text.toString())
                hideKeyboard(view)
                true
            } else {
                false
            }
        }
        header.searchInput.doAfterTextChanged { text ->
            header.searchClear.isVisible = !text.isNullOrEmpty()
        }
        header.searchClear.setOnClickListener {
            header.searchInput.text?.clear()
            hideKeyboard(header.searchInput)
            viewModel.clearSearch()
        }

        viewModel.state.observe(viewLifecycleOwner) { render() }
        downloads.live.observe(viewLifecycleOwner) {
            viewModel.downloadsChanged()
            render()
        }
    }

    override fun onDestroyView() {
        binding?.itemsList?.adapter = null
        binding = null
        header = null
        super.onDestroyView()
    }

    private fun hideKeyboard(view: View) {
        view.clearFocus()
        requireContext().getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun render() {
        val binding = binding ?: return
        val header = header ?: return
        val state = viewModel.state.value ?: return
        val records = downloads.live.value.orEmpty()
        val searching = state.query.isNotBlank()

        binding.swipeRefresh.isRefreshing = state.loading
        adapter.submitList(
            state.films.map { film ->
                FilmPosterAdapter.Card(film, FilmFormat.status(requireContext(), records[film.key]))
            }
        )

        val chips = ArrayList<ChipRow.Chip<Filter>>()
        chips.add(ChipRow.Chip<Filter>(Filter.All, getString(R.string.movies_genre_all)))
        if (records.isNotEmpty() || state.filter == Filter.Downloaded) {
            chips.add(ChipRow.Chip<Filter>(Filter.Downloaded, getString(R.string.film_filter_downloads)))
        }
        state.genres.mapTo(chips) { genre ->
            ChipRow.Chip<Filter>(Filter.OfGenre(genre), MoviesFragment.genreLabel(requireContext(), genre))
        }
        ChipRow.bind(header.chips, chips, state.filter, onSelect = viewModel::select)
        header.chipsScroll.isVisible = !searching

        val filter = state.filter
        header.sectionTitle.text = when {
            searching -> getString(R.string.movies_results_for, state.query)
            filter is Filter.OfGenre -> MoviesFragment.genreLabel(requireContext(), filter.genre)
            filter == Filter.Downloaded -> getString(R.string.film_filter_downloads)
            else -> getString(R.string.film_section_title)
        }
        header.sectionSubtitle.isVisible = !searching && filter != Filter.Downloaded

        val empty = state.films.isEmpty() && !state.loading
        header.emptyText.isVisible = empty
        binding.emptyState.isVisible = false
        if (empty) {
            val error = state.error
            header.emptyText.text = when {
                filter == Filter.Downloaded && !searching -> getString(R.string.film_empty_downloads)
                error is IOException -> getString(R.string.network_error)
                error != null -> getString(R.string.movies_error)
                searching -> getString(R.string.film_no_results)
                else -> getString(R.string.movies_empty)
            }
        }
    }

    private fun open(film: Film) {
        FilmDetailDialog.newInstance(film).show(childFragmentManager, DETAIL_TAG)
    }

    // ---------------------------------------------------------------------- menu

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_film_library, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_films_refresh -> viewModel.load(true)

            R.id.action_films_about ->
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.film_about)
                    .setMessage(R.string.film_about_message)
                    .setPositiveButton(R.string.ok, null)
                    .show()

            R.id.action_films_youtube ->
                fm.beginTransaction()
                    .replace(R.id.fragment_holder, MoviesFragment())
                    .addToBackStack(null)
                    .commit()

            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    companion object {
        private const val POSTER_MIN_WIDTH_DP = 118
        private const val GRID_SIDE_PADDING_DP = 8
        private const val DETAIL_TAG = "film_detail"
    }
}
