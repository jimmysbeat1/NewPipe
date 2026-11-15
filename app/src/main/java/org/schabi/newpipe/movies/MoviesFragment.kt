/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies

import android.content.Context
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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.IOException
import org.schabi.newpipe.BaseFragment
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.DialogAddPurchaseBinding
import org.schabi.newpipe.databinding.FragmentForYouBinding
import org.schabi.newpipe.databinding.HeaderMoviesBinding
import org.schabi.newpipe.error.ErrorUtil
import org.schabi.newpipe.recommend.ui.ChipRow
import org.schabi.newpipe.recommend.ui.SingleViewAdapter
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.external_communication.ShareUtils

/**
 * Browse, search and collect free full-length films from trusted distributors, and hand
 * purchased or rented titles over to the official player.
 */
class MoviesFragment : BaseFragment() {
    private var binding: FragmentForYouBinding? = null
    private var header: HeaderMoviesBinding? = null
    private lateinit var viewModel: MoviesViewModel
    private lateinit var gridAdapter: MoviePosterAdapter
    private lateinit var watchlistAdapter: MoviePosterAdapter
    private lateinit var purchasesAdapter: MoviePosterAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        viewModel = ViewModelProvider(this)[MoviesViewModel::class.java]
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
        // inflated without a parent: the list has no layout manager yet, and the adapter
        // assigns the layout params anyway
        val header = HeaderMoviesBinding.inflate(layoutInflater).also { this.header = it }

        gridAdapter = MoviePosterAdapter(null, ::open, ::showMenu)
        watchlistAdapter = MoviePosterAdapter(ROW_ITEM_WIDTH_DP, ::open, ::showMenu)
        purchasesAdapter = MoviePosterAdapter(ROW_ITEM_WIDTH_DP, ::open, ::showMenu)
        header.watchlistList.layoutManager =
            LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        header.watchlistList.adapter = watchlistAdapter
        header.purchasesList.layoutManager =
            LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        header.purchasesList.adapter = purchasesAdapter

        val spanCount = (resources.configuration.screenWidthDp / POSTER_MIN_WIDTH_DP).coerceIn(2, 8)
        val layoutManager = GridLayoutManager(requireContext(), spanCount)
        val concat = ConcatAdapter(
            ConcatAdapter.Config.Builder().setIsolateViewTypes(false).build(),
            SingleViewAdapter(header.root),
            gridAdapter
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
        header.purchasesButton.setOnClickListener { showPurchasesDialog() }

        viewModel.state.observe(viewLifecycleOwner, ::render)
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

    // -------------------------------------------------------------------- render

    private fun render(state: MoviesViewModel.State) {
        val binding = binding ?: return
        val header = header ?: return
        val searching = state.query.isNotBlank()

        binding.swipeRefresh.isRefreshing = state.loading
        gridAdapter.submitList(state.movies)
        watchlistAdapter.submitList(state.watchlist)
        purchasesAdapter.submitList(state.purchases)

        val chips = listOf(ChipRow.Chip<Genre?>(null, getString(R.string.movies_genre_all))) +
            state.genres.map { ChipRow.Chip<Genre?>(it, genreLabel(requireContext(), it)) }
        ChipRow.bind(header.chips, chips, state.genre, onSelect = viewModel::selectGenre)
        header.chipsScroll.isVisible = !searching && state.genres.isNotEmpty()

        header.watchlistTitle.isVisible = !searching && state.watchlist.isNotEmpty()
        header.watchlistList.isVisible = !searching && state.watchlist.isNotEmpty()
        header.purchasesTitle.isVisible = !searching && state.purchases.isNotEmpty()
        header.purchasesList.isVisible = !searching && state.purchases.isNotEmpty()
        header.purchasesButton.isVisible = !searching

        header.sectionTitle.text = when {
            searching -> getString(R.string.movies_results_for, state.query)
            state.genre != null -> genreLabel(requireContext(), state.genre)
            else -> getString(R.string.movies_section_free)
        }
        header.sectionSubtitle.isVisible = !searching

        val empty = state.movies.isEmpty() && !state.loading
        // the header (with the search box) has to stay reachable, so the message goes below it
        header.emptyText.isVisible = empty
        binding.emptyState.isVisible = false
        if (empty) {
            val error = state.error
            header.emptyText.text = when {
                error is IOException -> getString(R.string.network_error)
                error != null -> getString(R.string.movies_error)
                searching && viewModel.includeUnverified -> getString(R.string.movies_no_results)
                searching -> getString(R.string.movies_no_trusted_results)
                else -> getString(R.string.movies_empty)
            }
        }
    }

    // ------------------------------------------------------------------- actions

    private fun open(movie: Movie) {
        if (movie.officialOnly) {
            OfficialPlayback.open(requireContext(), movie.url)
            return
        }
        try {
            NavigationHelper.openVideoDetailFragment(
                requireContext(),
                fm,
                movie.serviceId,
                movie.url,
                movie.title,
                null,
                false
            )
        } catch (e: Exception) {
            ErrorUtil.showUiErrorSnackbar(this, "Opening video detail fragment", e)
        }
    }

    private fun showMenu(movie: Movie, anchor: View) {
        val state = viewModel.state.value ?: return
        val purchased = state.purchases.any { it.url == movie.url }
        val popup = PopupMenu(requireContext(), anchor)
        if (purchased) {
            popup.menu.add(0, MENU_REMOVE_PURCHASE, 0, R.string.movies_remove_purchase)
        } else {
            popup.menu.add(
                0,
                MENU_WATCHLIST,
                0,
                if (movie.url in state.watchlistUrls) {
                    R.string.movies_watchlist_remove
                } else {
                    R.string.movies_watchlist_add
                }
            )
        }
        popup.menu.add(0, MENU_OFFICIAL, 1, R.string.movies_open_official)
        popup.menu.add(0, MENU_SHARE, 2, R.string.share)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_WATCHLIST -> viewModel.toggleWatchlist(movie)
                MENU_REMOVE_PURCHASE -> viewModel.removePurchase(movie)
                MENU_OFFICIAL -> OfficialPlayback.open(requireContext(), movie.url)
                MENU_SHARE -> ShareUtils.shareText(requireContext(), movie.title, movie.url)
            }
            true
        }
        popup.show()
    }

    private fun showPurchasesDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.movies_purchases_title)
            .setMessage(R.string.movies_purchases_message)
            .setPositiveButton(R.string.movies_purchases_open) { _, _ ->
                OfficialPlayback.open(requireContext(), OfficialPlayback.PURCHASES_URL)
            }
            .setNeutralButton(R.string.movies_purchases_add) { _, _ -> showAddPurchaseDialog() }
            .setNegativeButton(R.string.movies_purchases_store) { _, _ ->
                OfficialPlayback.open(requireContext(), OfficialPlayback.STORE_URL)
            }
            .show()
    }

    private fun showAddPurchaseDialog() {
        val dialogBinding = DialogAddPurchaseBinding.inflate(layoutInflater)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.movies_purchases_add)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val url = OfficialPlayback.normalizeYoutubeUrl(dialogBinding.urlInput.text.toString())
                val title = dialogBinding.titleInput.text.toString().trim()
                if (url == null || title.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.movies_purchase_invalid, Toast.LENGTH_LONG)
                        .show()
                } else {
                    viewModel.addPurchase(title, url)
                }
            }
            .show()
    }

    // ---------------------------------------------------------------------- menu

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_movies, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        menu.findItem(R.id.action_movies_unverified)?.isChecked = viewModel.includeUnverified
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_movies_refresh -> viewModel.load(true)

            R.id.action_movies_unverified -> {
                if (item.isChecked) {
                    viewModel.includeUnverified = false
                    item.isChecked = false
                } else {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.movies_include_unverified)
                        .setMessage(R.string.movies_include_unverified_message)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.ok) { _, _ ->
                            viewModel.includeUnverified = true
                            requireActivity().invalidateOptionsMenu()
                        }
                        .show()
                }
            }

            R.id.action_movies_about ->
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.movies_about)
                    .setMessage(
                        getString(
                            R.string.movies_about_message,
                            MovieCatalog.DISTRIBUTORS.joinToString(", ") { it.name }
                        )
                    )
                    .setPositiveButton(R.string.ok, null)
                    .show()

            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    companion object {
        private const val POSTER_MIN_WIDTH_DP = 118
        private const val ROW_ITEM_WIDTH_DP = 124
        private const val GRID_SIDE_PADDING_DP = 8
        private const val MENU_WATCHLIST = 1
        private const val MENU_REMOVE_PURCHASE = 2
        private const val MENU_OFFICIAL = 3
        private const val MENU_SHARE = 4

        fun genreLabel(context: Context, genre: Genre): String {
            return context.getString(
                when (genre) {
                    Genre.ACTION -> R.string.movies_genre_action
                    Genre.ADVENTURE -> R.string.movies_genre_adventure
                    Genre.ANIMATION -> R.string.movies_genre_animation
                    Genre.CLASSICS -> R.string.movies_genre_classics
                    Genre.COMEDY -> R.string.movies_genre_comedy
                    Genre.CRIME -> R.string.movies_genre_crime
                    Genre.DOCUMENTARY -> R.string.movies_genre_documentary
                    Genre.DRAMA -> R.string.movies_genre_drama
                    Genre.FAMILY -> R.string.movies_genre_family
                    Genre.FANTASY -> R.string.movies_genre_fantasy
                    Genre.HORROR -> R.string.movies_genre_horror
                    Genre.MYSTERY -> R.string.movies_genre_mystery
                    Genre.ROMANCE -> R.string.movies_genre_romance
                    Genre.SCI_FI -> R.string.movies_genre_sci_fi
                    Genre.THRILLER -> R.string.movies_genre_thriller
                    Genre.WAR -> R.string.movies_genre_war
                    Genre.WESTERN -> R.string.movies_genre_western
                }
            )
        }
    }
}
