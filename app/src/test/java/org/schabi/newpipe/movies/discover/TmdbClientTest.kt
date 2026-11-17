/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TmdbClientTest {
    private val tmdb = FakeTmdb()
    private var credential: TmdbCredential? = TmdbCredential.parse(TmdbFixtures.V3_KEY)
    private val client = TmdbClient(tmdb) { credential }

    private fun problemOf(block: () -> Unit): TmdbException {
        try {
            block()
        } catch (e: TmdbException) {
            return e
        }
        fail("Expected a TmdbException")
        throw AssertionError()
    }

    // ------------------------------------------------------------------- the key

    @Test
    fun recognisesBothKindsOfTmdbKeyAndNothingElse() {
        assertFalse(TmdbCredential.parse(TmdbFixtures.V3_KEY)!!.bearer)
        assertTrue(TmdbCredential.parse("  " + TmdbFixtures.V4_TOKEN + "\n")!!.bearer)
        assertNull(TmdbCredential.parse(null))
        assertNull(TmdbCredential.parse(""))
        assertNull(TmdbCredential.parse("my key"))
        assertNull(TmdbCredential.parse(TmdbFixtures.V3_KEY.dropLast(1)))
        assertNull(TmdbCredential.parse(TmdbFixtures.V3_KEY + "&append_to_response=x"))
        assertNull(TmdbCredential.parse("https://api.themoviedb.org/3/movie/550"))
    }

    @Test
    fun aVersion3KeyIsSentAsParameterAndAVersion4TokenAsHeader() {
        tmdb.json("configuration", "{}")

        client.validate(TmdbCredential.parse(TmdbFixtures.V3_KEY)!!)
        assertEquals(TmdbFixtures.V3_KEY, tmdb.calls[0].query["api_key"])
        assertNull(tmdb.calls[0].headers["Authorization"])

        client.validate(TmdbCredential.parse(TmdbFixtures.V4_TOKEN)!!)
        assertNull(tmdb.calls[1].query["api_key"])
        assertEquals("Bearer " + TmdbFixtures.V4_TOKEN, tmdb.calls[1].headers["Authorization"])
        assertFalse(tmdb.calls[1].url.contains(TmdbFixtures.V4_TOKEN))
    }

    @Test
    fun requestsOnlyGoToTheTmdbApiOverHttps() {
        tmdb.json("discover/movie", TmdbFixtures.pageJson(1, 1))
        tmdb.json("search/movie", TmdbFixtures.pageJson(1, 1))
        client.discover(mapOf("sort_by" to "popularity.desc"), 1)
        client.search("dune & co", "en-AU", "AU", 1)
        assertTrue(tmdb.calls.all { it.url.startsWith("https://api.themoviedb.org/3/") })
        // the query is encoded, so that it cannot add parameters of its own
        assertEquals("dune & co", tmdb.calls[1].query["query"])
        assertEquals("false", tmdb.calls[1].query["include_adult"])
    }

    @Test
    fun withoutAKeyNothingIsRequested() {
        credential = null
        assertEquals(TmdbProblem.NO_KEY, problemOf { client.discover(emptyMap(), 1) }.problem)
        assertTrue(tmdb.calls.isEmpty())
    }

    // -------------------------------------------------------------------- errors

    @Test
    fun errorsAreToldApart() {
        tmdb.on("discover/movie") { TmdbResponse(401, """{"status_code":7,"status_message":"Invalid API key"}""") }
        assertEquals(TmdbProblem.INVALID_KEY, problemOf { client.discover(emptyMap(), 1) }.problem)

        tmdb.on("discover/movie") { TmdbResponse(429, "") }
        assertEquals(TmdbProblem.RATE_LIMITED, problemOf { client.discover(emptyMap(), 1) }.problem)

        tmdb.on("discover/movie") { TmdbResponse(503, "Service unavailable") }
        assertEquals(TmdbProblem.SERVICE, problemOf { client.discover(emptyMap(), 1) }.problem)

        tmdb.on("discover/movie") { TmdbResponse(200, "<html>captive portal</html>") }
        assertEquals(TmdbProblem.SERVICE, problemOf { client.discover(emptyMap(), 1) }.problem)

        tmdb.on("discover/movie") { TmdbResponse(200, "[1,2,3]") }
        assertEquals(TmdbProblem.SERVICE, problemOf { client.discover(emptyMap(), 1) }.problem)

        assertEquals(TmdbProblem.NOT_FOUND, problemOf { client.availability(1, "AU", 0) }.problem)

        tmdb.offline = true
        assertEquals(TmdbProblem.NETWORK, problemOf { client.discover(emptyMap(), 1) }.problem)
    }

    @Test
    fun theKeyNeverAppearsInAnErrorMessage() {
        // the exception of the network layer names the URL, which contains the key
        tmdb.offline = true
        val offline = problemOf { client.discover(emptyMap(), 1) }
        assertFalse(offline.message!!.contains(TmdbFixtures.V3_KEY))
        assertFalse(offline.toString().contains(TmdbFixtures.V3_KEY))
        assertNull(offline.cause)

        tmdb.offline = false
        tmdb.on("discover/movie") { call -> TmdbResponse(500, "Failed: " + call.url) }
        val failed = problemOf { client.discover(emptyMap(), 1) }
        assertFalse(failed.message!!.contains(TmdbFixtures.V3_KEY))
    }

    // ------------------------------------------------------------------- parsing

    @Test
    fun readsAPageOfFilmsAndSkipsBrokenEntries() {
        tmdb.json(
            "discover/movie",
            TmdbFixtures.pageJson(
                2,
                7,
                TmdbFixtures.movieJson(11, "Dune: Part Two", "2024-02-27", 6000, 8.1),
                """{"title":"No id"}""",
                """{"id":12}""",
                "null",
                """{"id":13,"title":"Undated","release_date":"","poster_path":"/p.jpg","vote_average":null}"""
            )
        )
        val page = client.discover(mapOf("with_genres" to "878"), 2)

        assertEquals("2", tmdb.calls[0].query["page"])
        assertEquals("878", tmdb.calls[0].query["with_genres"])
        assertEquals(2, page.page)
        assertEquals(7, page.totalPages)
        assertEquals(listOf(11, 13), page.movies.map { it.id })
        val dune = page.movies[0]
        assertEquals("Dune: Part Two", dune.title)
        assertEquals(2024, dune.year)
        assertEquals(8.1, dune.voteAverage, 0.001)
        assertEquals(6000, dune.voteCount)
        assertEquals(listOf(28, 878), dune.genreIds)
        assertNull(dune.posterPath)
        val undated = page.movies[1]
        assertNull(undated.year)
        assertEquals("/p.jpg", undated.posterPath)
        assertEquals(0.0, undated.voteAverage, 0.001)
    }

    @Test
    fun availabilityIsReadForTheRequestedCountryOnly() {
        tmdb.json(
            "movie/5/watch/providers",
            TmdbFixtures.providersJson(
                5,
                "AU",
                mapOf("flatrate" to listOf("Netflix", "Stan"), "ads" to listOf("Tubi TV"), "buy" to listOf("Apple TV"))
            )
        )
        val availability = client.availability(5, "AU", 1234)

        assertEquals("AU", availability.region)
        assertEquals(1234L, availability.fetchedAt)
        assertEquals("https://www.themoviedb.org/movie/5/watch?locale=AU", availability.link)
        assertEquals(Access.FREE_WITH_ADS, availability.best)
        assertTrue(availability.isFree)
        assertEquals(listOf("Tubi TV"), availability.bestProviders())
        assertEquals(listOf("Netflix", "Stan"), availability.providers(Access.SUBSCRIPTION))
        assertEquals(listOf("Apple TV"), availability.providers(Access.BUY))
        assertTrue(availability.providers(Access.FREE).isEmpty())
        // "Pluto TV" offers the film for free in the United States only
        assertFalse(availability.offers.any { it.provider.name == "Pluto TV" })
    }

    @Test
    fun aFilmThatIsOnlyOfferedElsewhereIsNotAvailable() {
        tmdb.json("movie/6/watch/providers", TmdbFixtures.providersJson(6, "AU", emptyMap()))
        val availability = client.availability(6, "AU", 0)
        assertNull(availability.best)
        assertFalse(availability.isFree)
        assertTrue(availability.offers.isEmpty())
        assertNull(availability.link)
    }

    @Test
    fun paidAndUnknownOffersAreNeverReadAsFree() {
        tmdb.json(
            "movie/7/watch/providers",
            """{"id":7,"results":{"AU":{"link":"http://insecure.example/","rent":[{"provider_id":2,"provider_name":"Apple TV"}],""" +
                """"buy":[{"provider_id":3,"provider_name":"Google Play Movies"}],""" +
                """"free_trial":[{"provider_id":9,"provider_name":"Mystery"}],""" +
                """"free":"yes","ads":[{"provider_name":"No id"},{"provider_id":4}]}}}"""
        )
        val availability = client.availability(7, "AU", 0)
        assertEquals(Access.RENT, availability.best)
        assertFalse(availability.isFree)
        assertEquals(setOf(Access.RENT, Access.BUY), availability.offers.map { it.access }.toSet())
        // only https links are passed on
        assertNull(availability.link)
    }

    @Test
    fun detailsCombineFilmTrailersAndAvailability() {
        tmdb.json("movie/42", TmdbFixtures.detailsJson(42, "Interstellar Two", "AU", mapOf("free" to listOf("SBS On Demand"))))
        val details = client.details(42, "en-AU", "AU", 99)

        assertEquals("videos,watch/providers", tmdb.calls[0].query["append_to_response"])
        assertEquals("Interstellar Two", details.movie.title)
        assertEquals(127, details.runtimeMinutes)
        assertEquals(listOf("Action", "Science Fiction"), details.genres)
        // trailers on YouTube only, the official one first; teasers and other sites are left out
        assertEquals(listOf("official001", "fanmade0001"), details.trailers.map { it.key })
        assertEquals("https://www.youtube.com/watch?v=official001", details.trailers[0].url)
        assertEquals(Access.FREE, details.availability.best)
        assertEquals(listOf("SBS On Demand"), details.availability.bestProviders())
        assertNotNull(details.availability.link)
    }
}
