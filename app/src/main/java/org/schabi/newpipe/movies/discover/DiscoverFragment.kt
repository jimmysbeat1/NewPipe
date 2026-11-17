/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.discover

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
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.BaseFragment
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentForYouBinding
import org.schabi.newpipe.databinding.HeaderDiscoverBinding
import org.schabi.newpipe.movies.MoviesFragment
import org.schabi.newpipe.movies.library.FilmDetailDialog
import org.schabi.newpipe.movies.library.FilmDownload
import org.schabi.newpipe.movies.library.FilmDownloads
import org.schabi.newpipe.movies.library.FilmFormat
import org.schabi.newpipe.movies.library.FilmLibraryFragment
import org.schabi.newpipe.recommend.ui.ChipRow
import org.schabi.newpipe.recommend.ui.SingleViewAdapter
import org.schabi.newpipe.util.external_communication.ShareUtils

/**
 * The Movies tab: recent, recognisable films found through TMDB, each labelled with how it can
 * be watched in Australia (free, free with ads, subscription, rent or buy), with search, filters,
 * a watchlist and the films that were downloaded from the open-licence library.
 *
 * The app does not play the films of streaming services. It sends the user to the official
 * provider and plays itself only what it is allowed to: trailers, films distributors publish on
 * YouTube, and films with an open licence.
 */
class DiscoverFragment : BaseFragment() {
    private var binding: FragmentForYouBinding? = null
    private var header: HeaderDiscoverBinding? = null
    private lateinit var viewModel: DiscoverViewModel
    private lateinit var downloads: FilmDownloads
    private lateinit var adapter: DiscoverAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        viewModel = ViewModelProvider(this)[DiscoverViewModel::class.java]
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
        val header = HeaderDiscoverBinding.inflate(layoutInflater).also { this.header = it }

        adapter = DiscoverAdapter(::open)
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

        binding.swipeRefresh.setOnRefreshListener { refresh() }
        binding.emptyState.isVisible = false

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
        header.filterSummary.setOnClickListener { showFilters() }
        header.attributionText.setOnClickListener { showAbout() }
        header.setupAddKey.setOnClickListener { showKeyDialog() }
        header.setupGetKey.setOnClickListener {
            ShareUtils.openUrlInBrowser(requireContext(), ProviderLinks.TMDB_KEY_PAGE)
        }
        header.setupLibrary.setOnClickListener { openScreen(FilmLibraryFragment()) }

        // the dialogs of this tab report back through the child fragment manager
        childFragmentManager.setFragmentResultListener(TmdbKeyDialog.RESULT, viewLifecycleOwner) { _, _ ->
            viewModel.keyChanged()
        }
        childFragmentManager.setFragmentResultListener(MovieDetailDialog.RESULT, viewLifecycleOwner) { _, _ ->
            viewModel.watchlistChanged()
        }

        viewModel.state.observe(viewLifecycleOwner) { render() }
        downloads.live.observe(viewLifecycleOwner) { render() }
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

    private fun refresh() {
        val query = viewModel.state.value?.query.orEmpty()
        if (query.isBlank()) viewModel.load(true) else viewModel.search(query)
    }

    private fun cards(state: DiscoverViewModel.State, records: Map<String, FilmDownload>): List<DiscoverAdapter.Card> {
        return if (state.section == Section.DOWNLOADED && state.query.isBlank()) {
            records.values.map { DiscoverAdapter.Card.Downloaded(it.film, FilmFormat.status(requireContext(), it)) }
        } else {
            state.movies.map { DiscoverAdapter.Card.Online(it) }
        }
    }

    private fun render() {
        val binding = binding ?: return
        val header = header ?: return
        val state = viewModel.state.value ?: return
        val context = requireContext()
        val searching = state.query.isNotBlank()
        val cards = cards(state, downloads.live.value.orEmpty())

        binding.swipeRefresh.isRefreshing = state.loading
        adapter.submitList(cards)

        val chips = Section.entries.map { ChipRow.Chip(it, DiscoverFormat.section(context, it)) }
        ChipRow.bind(header.chips, chips, state.section, onSelect = ::select)
        header.chipsScroll.isVisible = !searching

        header.sectionTitle.text = if (searching) {
            getString(R.string.movies_results_for, state.query)
        } else {
            DiscoverFormat.section(context, state.section)
        }
        header.sectionSubtitle.text = DiscoverFormat.sectionSummary(context, state.section)
        header.sectionSubtitle.isVisible = !searching

        // the filters narrow what TMDB is asked for; the lists kept on the device have none
        header.filterSummary.text = DiscoverFormat.filters(context, state.filters)
        header.filterSummary.isVisible = searching || state.section.online

        val needsKey = (searching || state.section.online) &&
            (state.problem == TmdbProblem.NO_KEY || state.problem == TmdbProblem.INVALID_KEY)
        header.setupCard.isVisible = needsKey
        if (needsKey) {
            val rejected = state.problem == TmdbProblem.INVALID_KEY
            header.setupTitle.setText(
                if (rejected) R.string.discover_setup_invalid_title else R.string.discover_setup_title
            )
            header.setupMessage.setText(
                if (rejected) R.string.discover_problem_invalid_key else R.string.discover_setup_message
            )
        }

        val status = ArrayList<String>(2)
        val problem = state.problem
        if (problem != null && !needsKey) {
            status.add(DiscoverFormat.problem(context, problem))
            if (cards.isNotEmpty()) {
                status.add(getString(R.string.discover_showing_saved))
            }
        }
        if (state.hidden > 0 && searching && !state.loading) {
            status.add(resources.getQuantityString(R.plurals.discover_hidden, state.hidden, state.hidden))
        }
        header.statusText.text = status.joinToString(" ")
        header.statusText.isVisible = status.isNotEmpty()

        val empty = cards.isEmpty() && !state.loading && !needsKey && problem == null
        header.emptyText.isVisible = empty
        if (empty) {
            header.emptyText.setText(
                when {
                    searching -> R.string.discover_empty_search
                    state.section == Section.DOWNLOADED -> R.string.discover_empty_downloads
                    state.section == Section.WATCHLIST -> R.string.discover_empty_watchlist
                    else -> R.string.discover_empty
                }
            )
        }
    }

    private fun select(section: Section) {
        val header = header
        if (header != null && !header.searchInput.text.isNullOrEmpty()) {
            header.searchInput.text?.clear()
        }
        viewModel.select(section)
        binding?.itemsList?.scrollToPosition(0)
    }

    private fun open(card: DiscoverAdapter.Card) {
        when (card) {
            is DiscoverAdapter.Card.Online ->
                MovieDetailDialog.newInstance(card.listed).show(childFragmentManager, DETAIL_TAG)

            is DiscoverAdapter.Card.Downloaded ->
                FilmDetailDialog.newInstance(card.film).show(childFragmentManager, DETAIL_TAG)
        }
    }

    private fun openScreen(fragment: Fragment) {
        fm.beginTransaction()
            .replace(R.id.fragment_holder, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun showKeyDialog() {
        TmdbKeyDialog().show(childFragmentManager, KEY_TAG)
    }

    private fun showFilters() {
        val state = viewModel.state.value ?: return
        DiscoverFilterDialog.show(requireContext(), layoutInflater, state.filters, viewModel::setFilters)
    }

    private fun showAbout() {
        val context = requireContext()
        AlertDialog.Builder(context)
            .setTitle(R.string.discover_menu_about)
            .setMessage(R.string.discover_about_message)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.discover_about_tmdb) { _, _ ->
                ShareUtils.openUrlInBrowser(context, ProviderLinks.TMDB_HOME)
            }
            .setNeutralButton(R.string.discover_about_justwatch) { _, _ ->
                ShareUtils.openUrlInBrowser(context, ProviderLinks.JUSTWATCH_HOME)
            }
            .show()
    }

    // ---------------------------------------------------------------------- menu

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_discover, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_discover_refresh -> refresh()
            R.id.action_discover_filters -> showFilters()
            R.id.action_discover_key -> showKeyDialog()
            R.id.action_discover_library -> openScreen(FilmLibraryFragment())
            R.id.action_discover_youtube -> openScreen(MoviesFragment())
            R.id.action_discover_about -> showAbout()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    companion object {
        private const val POSTER_MIN_WIDTH_DP = 118
        private const val GRID_SIDE_PADDING_DP = 8
        private const val DETAIL_TAG = "movie_detail"
        private const val KEY_TAG = "tmdb_key"
    }
}
