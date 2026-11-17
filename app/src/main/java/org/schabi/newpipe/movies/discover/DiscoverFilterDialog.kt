/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.discover

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.DialogDiscoverFiltersBinding

/** Lets the user choose how films are filtered and sorted. */
object DiscoverFilterDialog {
    private val ACCESS = listOf(
        R.id.accessFree to AccessFilter.FREE_ONLY,
        R.id.accessSubscription to AccessFilter.FREE_AND_SUBSCRIPTION,
        R.id.accessAny to AccessFilter.ANYTHING_WATCHABLE
    )
    private val YEARS = listOf(
        R.id.yearsLatest to Years.LATEST,
        R.id.years2020 to Years.FROM_2020,
        R.id.yearsAny to Years.ANY
    )
    private val SORT = listOf(
        R.id.sortRecommended to SortOrder.RECOMMENDED,
        R.id.sortPopular to SortOrder.POPULAR,
        R.id.sortNewest to SortOrder.NEWEST,
        R.id.sortRating to SortOrder.TOP_RATED
    )
    private val RATING = listOf(
        R.id.ratingAny to 0,
        R.id.rating6 to 6,
        R.id.rating7 to 7
    )

    private fun <T> idOf(options: List<Pair<Int, T>>, value: T): Int {
        return (options.firstOrNull { it.second == value } ?: options.first()).first
    }

    private fun <T> valueOf(options: List<Pair<Int, T>>, id: Int, fallback: T): T {
        return options.firstOrNull { it.first == id }?.second ?: fallback
    }

    fun show(context: Context, inflater: LayoutInflater, current: Filters, onChosen: (Filters) -> Unit): AlertDialog {
        val binding = DialogDiscoverFiltersBinding.inflate(inflater)
        binding.accessGroup.check(idOf(ACCESS, current.access))
        binding.yearsGroup.check(idOf(YEARS, current.years))
        binding.sortGroup.check(idOf(SORT, current.sort))
        binding.ratingGroup.check(idOf(RATING, current.minimumRating))

        return AlertDialog.Builder(context)
            .setTitle(R.string.discover_filters)
            .setView(binding.root)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.discover_filters_reset) { _, _ -> onChosen(Filters()) }
            .setPositiveButton(R.string.discover_filters_apply) { _, _ ->
                onChosen(
                    Filters(
                        years = valueOf(YEARS, binding.yearsGroup.checkedRadioButtonId, current.years),
                        access = valueOf(ACCESS, binding.accessGroup.checkedRadioButtonId, current.access),
                        sort = valueOf(SORT, binding.sortGroup.checkedRadioButtonId, current.sort),
                        minimumRating = valueOf(RATING, binding.ratingGroup.checkedRadioButtonId, current.minimumRating)
                    )
                )
            }
            .show()
    }
}
