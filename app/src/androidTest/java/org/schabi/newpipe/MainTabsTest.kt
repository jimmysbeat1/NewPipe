/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe

import android.view.View
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.download.DownloadsTabFragment
import org.schabi.newpipe.fragments.MainFragment
import org.schabi.newpipe.local.subscription.SubscriptionFragment
import org.schabi.newpipe.movies.MoviesFragment
import org.schabi.newpipe.recommend.ui.ForYouFragment
import us.shandian.giga.ui.fragment.MissionsFragment

/** Launches the real app and walks through the main navigation. */
@RunWith(AndroidJUnit4::class)
class MainTabsTest {

    private fun MainActivity.mainFragment(): MainFragment {
        return supportFragmentManager.fragments.filterIsInstance<MainFragment>().single()
    }

    private fun MainActivity.tabLayout(): TabLayout = findViewById(R.id.main_tab_layout)

    private fun ActivityScenario<MainActivity>.selectTab(index: Int) {
        onActivity { it.tabLayout().getTabAt(index)!!.select() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private inline fun <reified T : Fragment> MainActivity.visibleTab(): T {
        return mainFragment().childFragmentManager.fragments
            .filterIsInstance<T>()
            .single { it.isResumed }
    }

    @Test
    fun freshInstallShowsTheFourSectionsInABottomBar() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val tabs = activity.tabLayout()
                val labels = (0 until tabs.tabCount).map { tabs.getTabAt(it)!!.text.toString() }
                assertThat(labels).containsExactly("Home", "Movies", "Subscriptions", "Downloads")
                assertThat((0 until tabs.tabCount).map { tabs.getTabAt(it)!!.icon }).doesNotContainNull()

                // the bar sits below the content
                val pager = activity.findViewById<View>(R.id.pager)
                assertThat(tabs.top).isGreaterThanOrEqualTo(pager.bottom)
            }
        }
    }

    @Test
    fun everySectionOpens() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { activity ->
                val home = activity.visibleTab<ForYouFragment>()
                assertThat(home.requireView().findViewById<RecyclerView>(R.id.itemsList).adapter)
                    .isNotNull()
            }

            scenario.selectTab(1)
            scenario.onActivity { activity ->
                val view = activity.visibleTab<MoviesFragment>().requireView()
                // the header with search box and the purchases entry is always part of the list
                val list = view.findViewById<RecyclerView>(R.id.itemsList)
                assertThat(list.adapter!!.itemCount).isGreaterThanOrEqualTo(1)
            }

            scenario.selectTab(2)
            scenario.onActivity { activity ->
                assertThat(activity.visibleTab<SubscriptionFragment>().view).isNotNull()
            }

            scenario.selectTab(3)
            scenario.onActivity { activity ->
                val downloads = activity.visibleTab<DownloadsTabFragment>()
                // the stock download manager list is attached while the tab is visible ...
                assertThat(downloads.childFragmentManager.fragments.filterIsInstance<MissionsFragment>())
                    .hasSize(1)
            }

            scenario.selectTab(0)
            scenario.onActivity { activity ->
                activity.visibleTab<ForYouFragment>()
                // ... and detached again when leaving, so that download notifications work
                val downloads = activity.mainFragment().childFragmentManager.fragments
                    .filterIsInstance<DownloadsTabFragment>()
                assertThat(downloads.flatMap { it.childFragmentManager.fragments }).isEmpty()
            }
        }
    }

    @Test
    fun moviesHeaderOffersSearchAndPurchases() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.selectTab(1)
            scenario.onActivity { activity ->
                val view = activity.visibleTab<MoviesFragment>().requireView()
                view.findViewById<RecyclerView>(R.id.itemsList).apply {
                    measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                    )
                    layout(left, top, right, bottom)
                }
                assertThat(view.findViewById<View>(R.id.searchInput).isShown).isTrue()
                assertThat(view.findViewById<View>(R.id.purchasesButton).isShown).isTrue()
            }
        }
    }
}
