/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.download

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.schabi.newpipe.BaseFragment
import org.schabi.newpipe.R
import org.schabi.newpipe.util.PermissionHelper
import us.shandian.giga.ui.fragment.MissionsFragment

/**
 * Shows NewPipe's download manager as a main tab.
 *
 * The actual list is the unmodified [MissionsFragment] that [DownloadActivity] uses. It is only
 * attached while this tab is the visible one, because it silences the download notifications
 * for as long as it exists, and tabs next to the visible one are kept alive in the background.
 */
class DownloadsTabFragment : BaseFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return FrameLayout(requireContext()).apply {
            id = R.id.downloads_tab_container
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (childFragmentManager.findFragmentByTag(MISSIONS_TAG) == null &&
            PermissionHelper.checkStoragePermissions(
                requireActivity(),
                PermissionHelper.DOWNLOADS_REQUEST_CODE
            )
        ) {
            childFragmentManager.beginTransaction()
                .replace(R.id.downloads_tab_container, MissionsFragment(), MISSIONS_TAG)
                .commitNowAllowingStateLoss()
        }
    }

    override fun onPause() {
        childFragmentManager.findFragmentByTag(MISSIONS_TAG)?.let {
            childFragmentManager.beginTransaction().remove(it).commitNowAllowingStateLoss()
        }
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        // the items MissionsFragment looks up to show its grid/start/pause/clear actions
        inflater.inflate(R.menu.download_menu, menu)
    }

    companion object {
        private const val MISSIONS_TAG = "missions"
    }
}
