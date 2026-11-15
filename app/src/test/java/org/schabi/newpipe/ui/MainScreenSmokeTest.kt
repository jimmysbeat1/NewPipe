/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.ui

import android.app.Application
import android.content.ComponentName
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.room3.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import java.time.OffsetDateTime
import kotlinx.serialization.builtins.ListSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.history.model.StreamHistoryEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.stream.model.StreamStateEntity
import org.schabi.newpipe.download.DownloadsTabFragment
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.fragments.MainFragment
import org.schabi.newpipe.local.subscription.SubscriptionFragment
import org.schabi.newpipe.movies.Movie
import org.schabi.newpipe.movies.MovieCatalog
import org.schabi.newpipe.movies.MovieCatalogCache
import org.schabi.newpipe.movies.MoviesFragment
import org.schabi.newpipe.player.PlayerService
import org.schabi.newpipe.recommend.data.CachedCandidate
import org.schabi.newpipe.recommend.data.CandidateCache
import org.schabi.newpipe.recommend.data.PersonalStore
import org.schabi.newpipe.recommend.engine.Candidate
import org.schabi.newpipe.recommend.engine.CandidateSource
import org.schabi.newpipe.recommend.engine.Feedback
import org.schabi.newpipe.recommend.ui.ForYouFragment
import us.shandian.giga.service.DownloadManagerService

/**
 * Starts the real main screen on the JVM (Robolectric) and uses its sections like a user would,
 * so that crashes and wiring mistakes in the screens are caught without a device.
 *
 * The caches are filled beforehand and marked as fresh, so the tests need no network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainScreenSmokeTest {
    private lateinit var application: Application
    private lateinit var database: AppDatabase
    private lateinit var store: PersonalStore
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        // like a phone: without touch mode, views grab the focus when others are removed
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)

        // the bundled SQLite driver is a native Android library; use the platform one instead
        database = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        NewPipeDatabase::class.java.getDeclaredField("databaseInstance").apply {
            isAccessible = true
            set(NewPipeDatabase, database)
        }

        // Robolectric has no real services to bind to; without this it would hand the app a
        // null binder, which no real device does
        for (service in listOf(PlayerService::class.java, DownloadManagerService::class.java)) {
            shadowOf(application).declareComponentUnbindable(ComponentName(application, service))
        }

        // every test gets its own storage directory, so drop the store of the previous test
        PersonalStore::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
        store = PersonalStore.get(application)
        // fresh but empty caches: nothing is fetched unless a test wants it to be
        store.write("candidates", CandidateCache.serializer(), CandidateCache(now, emptyList()))
        store.write("movie_catalog", MovieCatalogCache.serializer(), MovieCatalogCache(now, emptyList()))
    }

    @After
    fun tearDown() {
        database.close()
    }

    private companion object {
        /** Tall enough for every item of the lists used here to be laid out at once. */
        const val LIST_HEIGHT = 40_000
    }

    // ------------------------------------------------------------------- helpers

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Lets the main thread and the background work run until [condition] holds. */
    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            idle()
            if (condition()) {
                return
            }
            assertTrue("Timed out waiting for: $what", System.currentTimeMillis() < deadline)
            Thread.sleep(20)
        }
    }

    private fun MainActivity.tabs(): TabLayout = findViewById(R.id.main_tab_layout)

    private fun MainActivity.mainFragment(): MainFragment {
        return supportFragmentManager.fragments.filterIsInstance<MainFragment>().single()
    }

    private fun MainActivity.visibleTab(): Fragment {
        return mainFragment().childFragmentManager.fragments.single { it.isResumed }
    }

    private fun MainActivity.selectTab(index: Int): Fragment {
        tabs().getTabAt(index)!!.select()
        idle()
        return visibleTab()
    }

    /** Runs a layout pass so that the list creates and binds its item views. */
    private fun RecyclerView.layoutNow(): RecyclerView {
        measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(LIST_HEIGHT, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, 1080, LIST_HEIGHT)
        return this
    }

    private fun Fragment.list(): RecyclerView = requireView().findViewById(R.id.itemsList)

    private fun View.text(id: Int): String = findViewById<TextView>(id).text.toString()

    private fun ViewGroup.children(): List<View> = (0 until childCount).map(::getChildAt)

    private fun RecyclerView.texts(id: Int): List<String> {
        return layoutNow().children().mapNotNull { it.findViewById<TextView>(id)?.text?.toString() }
    }

    private fun launch(block: (MainActivity) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            idle()
            scenario.onActivity(block)
        }
    }

    // --------------------------------------------------------------- navigation

    @Test
    fun mainScreenShowsTheFourSectionsInABottomBar() = launch { activity ->
        val tabs = activity.tabs()
        val labels = (0 until tabs.tabCount).map { tabs.getTabAt(it)!!.text.toString() }
        assertEquals(listOf("Home", "Movies", "Subscriptions", "Downloads"), labels)
        assertTrue((0 until tabs.tabCount).all { tabs.getTabAt(it)!!.icon != null })

        assertTrue(activity.visibleTab() is ForYouFragment)
        assertTrue(activity.selectTab(1) is MoviesFragment)
        assertTrue(activity.selectTab(2) is SubscriptionFragment)
        assertTrue(activity.selectTab(3) is DownloadsTabFragment)
        assertTrue(activity.selectTab(0) is ForYouFragment)
        assertNotNull(activity.visibleTab().view)
    }

    // --------------------------------------------------------------------- home

    private fun watch(index: Int, title: String) {
        val uid = database.streamDAO().insert(
            StreamEntity(
                serviceId = 0,
                url = "https://www.youtube.com/watch?v=watched$index",
                title = title,
                streamType = StreamType.VIDEO_STREAM,
                duration = 600,
                uploader = "KeebLab",
                uploaderUrl = "https://www.youtube.com/channel/keeblab"
            )
        )
        database.streamHistoryDAO().insert(StreamHistoryEntity(uid, OffsetDateTime.now().minusDays(1), 1))
        database.streamStateDAO().insert(StreamStateEntity(uid, 590_000))
    }

    private fun candidate(title: String, uploader: String, source: CandidateSource) = Candidate(
        serviceId = 0,
        url = "https://www.youtube.com/watch?v=" + title.hashCode(),
        title = title,
        uploader = uploader,
        uploaderUrl = "https://www.youtube.com/channel/" + uploader.hashCode(),
        durationSeconds = 725,
        viewCount = 12_000,
        uploadEpochMillis = now - 2 * 86_400_000L,
        thumbnailUrl = null,
        streamType = "VIDEO_STREAM",
        source = source
    )

    private fun fillHome() {
        watch(1, "Mechanical keyboard build guide")
        watch(2, "Mechanical keyboard sound test")
        watch(3, "Lubing mechanical keyboard switches")
        val keyboards = (1..14).map {
            candidate("Mechanical keyboard review number $it", "Creator $it", CandidateSource.TOPIC)
        }
        val popular = (1..6).map {
            candidate("Viral cat compilation $it", "Cats $it", CandidateSource.TRENDING)
        }
        store.write(
            "candidates",
            CandidateCache.serializer(),
            CandidateCache(now, (popular + keyboards).map { CachedCandidate(it, now) })
        )
    }

    @Test
    fun homeIsEmptyAndExplainsItselfWithoutAnyData() = launch { activity ->
        val home = activity.visibleTab()
        val empty = home.requireView().findViewById<View>(R.id.emptyState)
        waitFor("empty state") { empty.visibility == View.VISIBLE }
        assertEquals(
            "Nothing to recommend yet. Pull down to refresh.",
            home.requireView().text(R.id.emptyText)
        )
    }

    @Test
    fun homeRanksByInterestExplainsWhyAndFiltersByTopic() {
        fillHome()
        launch { activity ->
            val home = activity.visibleTab()
            val list = home.list()
            // header + 20 recommendations
            waitFor("recommendations") { list.adapter!!.itemCount == 21 }

            val titles = list.texts(R.id.itemVideoTitleView)
            assertEquals(20, titles.size)
            assertTrue(titles.take(6).all { it.startsWith("Mechanical keyboard review") })
            // exploration slot: something outside the interests shows up early, but not on top
            assertTrue(titles[6].startsWith("Viral cat"))

            val first = list.children().first { it.findViewById<View>(R.id.itemVideoTitleView) != null }
            assertEquals("Because you watch mechanical keyboard", first.text(R.id.itemReasonView))
            assertTrue(first.text(R.id.itemDetailsView).startsWith("Creator "))
            assertTrue("12K views" in first.text(R.id.itemDetailsView))
            assertEquals("12:05", first.text(R.id.itemDurationView))
            val trending = list.children().first {
                it.findViewById<TextView>(R.id.itemVideoTitleView)?.text?.startsWith("Viral") == true
            }
            assertEquals("Popular right now", trending.text(R.id.itemReasonView))

            // topic chips: "For you" plus the learned topic
            val chips = list.findViewById<ViewGroup>(R.id.chips).children().map { it as TextView }
            assertEquals("For you", chips[0].text.toString())
            assertTrue(chips[0].isSelected)
            val keyboardChip = chips.single { it.text.toString() == "Mechanical keyboard" }

            keyboardChip.performClick()
            waitFor("topic filter") { list.adapter!!.itemCount == 15 }
            assertTrue(list.texts(R.id.itemVideoTitleView).all { it.startsWith("Mechanical keyboard") })
            assertTrue(keyboardChip.isSelected)

            chips[0].performClick()
            waitFor("all topics again") { list.adapter!!.itemCount == 21 }
        }
    }

    @Test
    fun notInterestedRemovesTheVideoAndIsRemembered() {
        fillHome()
        launch { activity ->
            val home = activity.visibleTab() as ForYouFragment
            val list = home.list()
            waitFor("recommendations") { list.adapter!!.itemCount == 21 }
            val disliked = list.texts(R.id.itemVideoTitleView).first()

            // open the card's menu and pick "Not interested"
            val card = list.children().first { it.findViewById<View>(R.id.itemMenuButton) != null }
            card.findViewById<View>(R.id.itemMenuButton).performClick()
            idle()
            val menuItem = shadowOf(application).latestPopupWindow?.contentView
                ?.let(::findTextViews).orEmpty()
                .firstOrNull { it.text.toString() == "Not interested" }
            assertNotNull("popup menu with 'Not interested'", menuItem)
            val popupList = generateSequence(menuItem as View?) { it.parent as? View }
                .filterIsInstance<android.widget.ListView>().first()
            popupList.performItemClick(popupList.getChildAt(0), 0, popupList.adapter.getItemId(0))

            waitFor("video removed") { list.adapter!!.itemCount == 20 }
            assertFalse(disliked in list.texts(R.id.itemVideoTitleView))

            val feedback = store.read("feedback", ListSerializer(Feedback.serializer()), emptyList())
            assertEquals(listOf(disliked), feedback.map { it.title })
        }
    }

    private fun findTextViews(view: View): List<TextView> {
        return when (view) {
            is TextView -> listOf(view)
            is ViewGroup -> view.children().flatMap(::findTextViews)
            else -> emptyList()
        }
    }

    // ------------------------------------------------------------------- movies

    private fun movie(title: String, distributor: Int = 0): Movie {
        val channel = MovieCatalog.DISTRIBUTORS[distributor]
        return MovieCatalog.toMovie(
            0,
            "https://www.youtube.com/watch?v=" + title.hashCode(),
            title,
            channel.name,
            "https://www.youtube.com/channel/" + channel.channelId,
            5_760,
            1_000,
            null,
            null
        )!!
    }

    private fun fillMovies(): List<Movie> {
        val movies = listOf(
            movie("Night of the Living Dead (1968) | Horror | Full Movie"),
            movie("The Four of the Apocalypse | 4K | Spaghetti Western"),
            movie("Charade (1963) | Comedy | Mystery | Full Movie", 1),
            movie("Django Rides Again | Western | Full Movie", 1)
        )
        store.write("movie_catalog", MovieCatalogCache.serializer(), MovieCatalogCache(now, movies))
        return movies
    }

    @Test
    fun moviesShowPostersGenresAndTheWatchlist() {
        val movies = fillMovies()
        store.write("movie_watchlist", ListSerializer(Movie.serializer()), listOf(movies[2]))
        launch { activity ->
            val fragment = activity.selectTab(1)
            val list = fragment.list()
            waitFor("catalog") { list.adapter!!.itemCount == 5 }
            list.layoutNow()

            // grid of posters with cleaned titles and details
            val grid = list.children().drop(1) // without the header
            assertEquals(4, grid.size)
            val dead = grid.single { it.text(R.id.titleView) == "Night of the Living Dead" }
            assertEquals("1968 • 1 h 36 min", dead.text(R.id.detailsView))
            assertEquals("Popcornflix", dead.text(R.id.channelView))
            assertEquals(View.GONE, dead.findViewById<View>(R.id.badgeView).visibility)

            // watchlist row
            val header = list.getChildAt(0)
            assertEquals(View.VISIBLE, header.findViewById<View>(R.id.watchlistTitle).visibility)
            val watchlist = header.findViewById<RecyclerView>(R.id.watchlistList)
            assertEquals(1, watchlist.adapter!!.itemCount)

            // genre chips only offer genres that exist in the catalog
            val chips = header.findViewById<ViewGroup>(R.id.chips).children().map { it as TextView }
            assertEquals(
                listOf("All", "Classics", "Comedy", "Horror", "Mystery", "Western"),
                chips.map { it.text.toString() }
            )
            chips.single { it.text.toString() == "Western" }.performClick()
            waitFor("genre filter") { list.adapter!!.itemCount == 3 }
            assertEquals("Western", list.layoutNow().getChildAt(0).text(R.id.sectionTitle))
            assertEquals(
                setOf("The Four of the Apocalypse", "Django Rides Again"),
                list.children().drop(1).map { it.text(R.id.titleView) }.toSet()
            )
        }
    }

    @Test
    fun purchasedTitlesAreAddedThroughTheDialogAndMarkedForTheOfficialPlayer() {
        fillMovies()
        launch { activity ->
            val fragment = activity.selectTab(1)
            val list = fragment.list()
            waitFor("catalog") { list.adapter!!.itemCount == 5 }
            val header = list.layoutNow().getChildAt(0)
            assertEquals(View.GONE, header.findViewById<View>(R.id.purchasesList).visibility)

            header.findViewById<View>(R.id.purchasesButton).performClick()
            idle()
            val info = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(info.isShowing)
            assertEquals("My purchases", info.getButton(DialogInterface.BUTTON_POSITIVE).text.toString())
            assertEquals("Store", info.getButton(DialogInterface.BUTTON_NEGATIVE).text.toString())
            info.getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
            idle()

            val add = ShadowDialog.getLatestDialog() as AlertDialog
            add.findViewById<EditText>(R.id.titleInput)!!.setText("My Blockbuster")
            add.findViewById<EditText>(R.id.urlInput)!!.setText("https://youtu.be/dQw4w9WgXcQ")
            add.getButton(DialogInterface.BUTTON_POSITIVE).performClick()

            val purchases = header.findViewById<RecyclerView>(R.id.purchasesList)
            waitFor("purchase listed") { purchases.adapter!!.itemCount == 1 }
            list.layoutNow()
            assertEquals(View.VISIBLE, purchases.visibility)
            val poster = purchases.getChildAt(0)
            assertEquals("My Blockbuster", poster.text(R.id.titleView))
            assertEquals("YouTube player", poster.text(R.id.badgeView))

            val stored = store.read("movie_purchases", ListSerializer(Movie.serializer()), emptyList())
            assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", stored.single().url)
            assertTrue(stored.single().officialOnly)
        }
    }
}
