/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.recommend.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.snackbar.Snackbar
import java.io.IOException
import org.schabi.newpipe.BaseFragment
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentForYouBinding
import org.schabi.newpipe.databinding.HeaderForYouBinding
import org.schabi.newpipe.error.ErrorUtil
import org.schabi.newpipe.info_list.dialog.InfoItemDialog
import org.schabi.newpipe.recommend.data.toStreamInfoItem
import org.schabi.newpipe.recommend.engine.Feedback
import org.schabi.newpipe.recommend.engine.FeedbackKind
import org.schabi.newpipe.recommend.engine.Recommendation
import org.schabi.newpipe.recommend.engine.channelKey
import org.schabi.newpipe.util.NavigationHelper

/**
 * The personalised home screen: a feed of recommendations computed on the device from the local
 * watch history, searches and subscriptions, with topic chips to narrow it down.
 */
class ForYouFragment : BaseFragment() {
    private var binding: FragmentForYouBinding? = null
    private var header: HeaderForYouBinding? = null
    private lateinit var viewModel: ForYouViewModel
    private lateinit var adapter: ForYouAdapter
    private var resumedBefore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        viewModel = ViewModelProvider(this)[ForYouViewModel::class.java]
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
        val header = HeaderForYouBinding.inflate(layoutInflater).also { this.header = it }

        adapter = ForYouAdapter(::open, ::showItemMenu)
        val spanCount = spanCount()
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

        binding.swipeRefresh.setOnRefreshListener { viewModel.load(true) }
        binding.retryButton.setOnClickListener { viewModel.load(true) }

        viewModel.state.observe(viewLifecycleOwner, ::render)
    }

    private fun spanCount(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return (widthDp / CARD_MIN_WIDTH_DP).coerceIn(1, 4)
    }

    override fun onResume() {
        super.onResume()
        // coming back from watching something: drop what was just seen, without reloading
        if (resumedBefore) {
            viewModel.rerank()
        }
        resumedBefore = true
    }

    override fun onDestroyView() {
        binding?.itemsList?.adapter = null
        binding = null
        header = null
        super.onDestroyView()
    }

    // -------------------------------------------------------------------- render

    private fun render(state: ForYouViewModel.State) {
        val binding = binding ?: return
        val header = header ?: return

        binding.swipeRefresh.isRefreshing = state.loading
        adapter.submitList(state.items)

        val chips = listOf(ChipRow.Chip<String?>(null, getString(R.string.for_you_chip_all))) +
            state.topics.map { ChipRow.Chip<String?>(it, it.replaceFirstChar(Char::uppercase)) }
        ChipRow.bind(
            header.chips,
            chips,
            state.selectedTopic,
            onSelect = { topic ->
                if (topic != state.selectedTopic) {
                    binding.itemsList.scrollToPosition(0)
                    viewModel.selectTopic(topic)
                }
            },
            onLongPress = { topic ->
                if (topic != null) {
                    confirmBlockTopic(topic)
                }
                topic != null
            }
        )
        header.chipsScroll.isVisible = state.topics.isNotEmpty()
        header.coldStartHint.isVisible = state.coldStart && state.items.isNotEmpty()

        val empty = state.items.isEmpty() && !state.loading
        binding.emptyState.isVisible = empty
        if (empty) {
            val error = state.error
            binding.emptyText.text = when {
                error is IOException -> getString(R.string.network_error)
                error != null -> getString(R.string.for_you_error)
                state.selectedTopic != null -> getString(R.string.for_you_empty_topic)
                else -> getString(R.string.for_you_empty)
            }
        }
    }

    // ------------------------------------------------------------------- actions

    private fun open(recommendation: Recommendation) {
        val candidate = recommendation.candidate
        try {
            NavigationHelper.openVideoDetailFragment(
                requireContext(),
                fm,
                candidate.serviceId,
                candidate.url,
                candidate.title,
                null,
                false
            )
        } catch (e: Exception) {
            ErrorUtil.showUiErrorSnackbar(this, "Opening video detail fragment", e)
        }
    }

    private fun showItemMenu(recommendation: Recommendation, anchor: View) {
        val candidate = recommendation.candidate
        val channel = channelKey(candidate.uploaderUrl, candidate.uploader)
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, MENU_NOT_INTERESTED, 0, R.string.for_you_not_interested)
        if (candidate.uploader.isNotBlank()) {
            popup.menu.add(
                0,
                MENU_BLOCK_CHANNEL,
                1,
                getString(R.string.for_you_block_channel, candidate.uploader)
            )
        }
        popup.menu.add(0, MENU_MORE, 2, R.string.for_you_more_options)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_NOT_INTERESTED -> giveFeedback(
                    Feedback(FeedbackKind.VIDEO, candidate.url, candidate.title, channel),
                    getString(R.string.for_you_feedback_saved)
                )

                MENU_BLOCK_CHANNEL -> giveFeedback(
                    Feedback(FeedbackKind.CHANNEL, channel, candidate.uploader),
                    getString(R.string.for_you_channel_blocked, candidate.uploader)
                )

                MENU_MORE -> showStreamDialog(recommendation)
            }
            true
        }
        popup.show()
    }

    private fun showStreamDialog(recommendation: Recommendation) {
        val item = recommendation.candidate.toStreamInfoItem()
        try {
            InfoItemDialog.Builder(requireActivity(), requireContext(), this, item).create().show()
        } catch (e: IllegalArgumentException) {
            InfoItemDialog.Builder.reportErrorDuringInitialization(e, item)
        }
    }

    private fun confirmBlockTopic(topic: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(topic.replaceFirstChar(Char::uppercase))
            .setMessage(R.string.for_you_block_topic_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.for_you_not_interested) { _, _ ->
                giveFeedback(
                    Feedback(FeedbackKind.TOPIC, topic),
                    getString(R.string.for_you_feedback_saved)
                )
            }
            .show()
    }

    private fun giveFeedback(feedback: Feedback, message: String) {
        viewModel.addFeedback(feedback)
        val root = binding?.root ?: return
        Snackbar.make(root, message, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) { viewModel.removeFeedback(feedback) }
            .show()
    }

    // ---------------------------------------------------------------------- menu

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_for_you, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        menu.findItem(R.id.action_hide_shorts)?.isChecked = viewModel.hideShorts
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_refresh_recommendations -> viewModel.load(true)

            R.id.action_hide_shorts -> {
                viewModel.hideShorts = !item.isChecked
                item.isChecked = !item.isChecked
            }

            R.id.action_reset_recommendations ->
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.for_you_reset)
                    .setMessage(R.string.for_you_reset_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.ok) { _, _ -> viewModel.reset() }
                    .show()

            R.id.action_about_recommendations ->
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.for_you_about)
                    .setMessage(R.string.for_you_about_message)
                    .setPositiveButton(R.string.ok, null)
                    .show()

            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    companion object {
        private const val CARD_MIN_WIDTH_DP = 340
        private const val MENU_NOT_INTERESTED = 1
        private const val MENU_BLOCK_CHANNEL = 2
        private const val MENU_MORE = 3
    }
}
