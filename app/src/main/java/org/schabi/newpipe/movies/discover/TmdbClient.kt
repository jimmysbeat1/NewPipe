/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.discover

import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.BuildConfig

/** The answer of the TMDB API to one request. */
class TmdbResponse(val code: Int, val body: String)

/**
 * Sends a GET request to the TMDB API. Implementations block and throw [IOException] when the
 * service cannot be reached.
 */
fun interface TmdbHttp {
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String>): TmdbResponse
}

/** Why a request to TMDB did not produce a result. */
enum class TmdbProblem {
    /** No API key has been entered yet. */
    NO_KEY,

    /** TMDB rejected the key. */
    INVALID_KEY,

    /** Too many requests; TMDB asks to slow down. */
    RATE_LIMITED,

    /** The film or page does not exist. */
    NOT_FOUND,

    /** TMDB answered with an error or with something that could not be understood. */
    SERVICE,

    /** TMDB could not be reached. */
    NETWORK
}

/**
 * A failed request. The message never contains the request URL, because the URL of a request
 * made with a version 3 key contains that key.
 */
class TmdbException(val problem: TmdbProblem, detail: String? = null) :
    IOException("TMDB: " + problem.name + if (detail != null) " ($detail)" else "")

/**
 * The key the user obtained from TMDB. TMDB issues two kinds, and both work:
 * a version 3 "API key" (32 hexadecimal characters), which is sent as a query parameter, and a
 * version 4 "API read access token" (a JSON web token), which is sent in a header.
 */
class TmdbCredential private constructor(val secret: String, val bearer: Boolean) {
    companion object {
        private val V3_KEY = Regex("[0-9a-fA-F]{32}")
        private val V4_TOKEN = Regex("[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}")

        /** @return the credential, or `null` if the text is neither kind of TMDB key */
        fun parse(input: String?): TmdbCredential? {
            val text = input?.trim().orEmpty()
            return when {
                V3_KEY.matches(text) -> TmdbCredential(text, false)
                V4_TOKEN.matches(text) -> TmdbCredential(text, true)
                else -> null
            }
        }
    }
}

/**
 * The parts of the TMDB API the Movies section uses: finding films, their details, and where
 * they can be watched. The "where to watch" data is supplied to TMDB by JustWatch.
 *
 * All functions block and throw [TmdbException].
 *
 * @param credential the key to use right now, or `null` if none has been entered
 */
open class TmdbClient(
    private val http: TmdbHttp,
    private val credential: () -> TmdbCredential?
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Checks that TMDB accepts the given key, without storing it. */
    @Throws(TmdbException::class)
    open fun validate(candidate: TmdbCredential) {
        request("configuration", emptyMap(), candidate)
    }

    /** Films matching the filters in [parameters], see [DiscoverQuery]. */
    @Throws(TmdbException::class)
    open fun discover(parameters: Map<String, String>, page: Int): TmdbPage {
        return parsePage(request("discover/movie", parameters + ("page" to page.toString())))
    }

    @Throws(TmdbException::class)
    open fun search(query: String, language: String, region: String, page: Int): TmdbPage {
        val parameters = mapOf(
            "query" to query,
            "include_adult" to "false",
            "language" to language,
            "region" to region,
            "page" to page.toString()
        )
        return parsePage(request("search/movie", parameters))
    }

    /** Where the film can be watched in [region]. */
    @Throws(TmdbException::class)
    open fun availability(movieId: Int, region: String, now: Long): Availability {
        val root = request("movie/$movieId/watch/providers", emptyMap())
        return TmdbParser.availability(root["results"].asObject()?.get(region).asObject(), region, now)
    }

    /** The film with its running time, genres, trailers and where it can be watched. */
    @Throws(TmdbException::class)
    open fun details(movieId: Int, language: String, region: String, now: Long): MovieDetails {
        val parameters = mapOf("language" to language, "append_to_response" to "videos,watch/providers")
        val root = request("movie/$movieId", parameters)
        val movie = TmdbParser.movie(root) ?: throw TmdbException(TmdbProblem.SERVICE, "no film in the answer")
        val offers = root["watch/providers"].asObject()?.get("results").asObject()?.get(region).asObject()
        return MovieDetails(
            movie = movie,
            runtimeMinutes = root["runtime"].int() ?: 0,
            genres = root["genres"].asArray().mapNotNull { it.asObject()?.get("name").text() },
            trailers = TmdbParser.trailers(root["videos"].asObject()?.get("results").asArray()),
            availability = TmdbParser.availability(offers, region, now)
        )
    }

    private fun parsePage(root: JsonObject): TmdbPage {
        return TmdbPage(
            page = root["page"].int() ?: 1,
            totalPages = root["total_pages"].int() ?: 1,
            movies = root["results"].asArray().mapNotNull { TmdbParser.movie(it.asObject()) }
        )
    }

    private fun request(
        path: String,
        parameters: Map<String, String>,
        candidate: TmdbCredential? = null
    ): JsonObject {
        val key = candidate ?: credential() ?: throw TmdbException(TmdbProblem.NO_KEY)
        val all = if (key.bearer) parameters else parameters + ("api_key" to key.secret)
        val query = all.entries.joinToString("&") { (name, value) ->
            URLEncoder.encode(name, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }
        val url = BASE_URL + path + if (query.isEmpty()) "" else "?$query"
        val headers = HashMap<String, String>()
        headers["Accept"] = "application/json"
        if (key.bearer) {
            headers["Authorization"] = "Bearer " + key.secret
        }

        val response = try {
            http.get(url, headers)
        } catch (e: TmdbException) {
            throw e
        } catch (e: IOException) {
            // the message of the original exception may contain the URL, and with it the key
            throw TmdbException(TmdbProblem.NETWORK, e.javaClass.simpleName)
        }
        when (response.code) {
            in 200..299 -> Unit
            401, 403 -> throw TmdbException(TmdbProblem.INVALID_KEY)
            404 -> throw TmdbException(TmdbProblem.NOT_FOUND)
            429 -> throw TmdbException(TmdbProblem.RATE_LIMITED)
            else -> throw TmdbException(TmdbProblem.SERVICE, "HTTP " + response.code)
        }
        return try {
            json.parseToJsonElement(response.body) as? JsonObject
                ?: throw TmdbException(TmdbProblem.SERVICE, "unexpected answer")
        } catch (e: SerializationException) {
            throw TmdbException(TmdbProblem.SERVICE, "unreadable answer")
        } catch (e: IllegalArgumentException) {
            throw TmdbException(TmdbProblem.SERVICE, "unreadable answer")
        }
    }

    companion object {
        const val BASE_URL = "https://api.themoviedb.org/3/"
    }
}

/** Turns the JSON of the TMDB API into the models of the app. */
object TmdbParser {
    // the names TMDB uses for the ways a film is offered; anything else is ignored, so that an
    // offer of an unknown kind can never be mistaken for a free one
    private val ACCESS = linkedMapOf(
        "free" to Access.FREE,
        "ads" to Access.FREE_WITH_ADS,
        "flatrate" to Access.SUBSCRIPTION,
        "rent" to Access.RENT,
        "buy" to Access.BUY
    )

    fun movie(item: JsonObject?): TmdbMovie? {
        val id = item?.get("id").int() ?: return null
        val title = item?.get("title").text() ?: item?.get("original_title").text() ?: return null
        return TmdbMovie(
            id = id,
            title = title,
            originalTitle = item?.get("original_title").text(),
            overview = item?.get("overview").text(),
            releaseDate = item?.get("release_date").text(),
            posterPath = item?.get("poster_path").text(),
            genreIds = item?.get("genre_ids").asArray().mapNotNull { it.int() },
            voteAverage = item?.get("vote_average").double() ?: 0.0,
            voteCount = item?.get("vote_count").int() ?: 0,
            popularity = item?.get("popularity").double() ?: 0.0,
            originalLanguage = item?.get("original_language").text()
        )
    }

    /** @param country the entry of one country in a "watch providers" answer, if there is one */
    fun availability(country: JsonObject?, region: String, now: Long): Availability {
        val offers = ArrayList<Offer>()
        for ((name, access) in ACCESS) {
            for (entry in country?.get(name).asArray()) {
                val provider = entry.asObject() ?: continue
                val id = provider["provider_id"].int() ?: continue
                val providerName = provider["provider_name"].text() ?: continue
                offers.add(Offer(access, Provider(id, providerName)))
            }
        }
        return Availability(
            region = region,
            offers = offers,
            link = country?.get("link").text()?.takeIf { it.startsWith("https://") },
            fetchedAt = now
        )
    }

    /** The trailers on YouTube, official ones first. */
    fun trailers(videos: List<JsonElement>): List<Trailer> {
        return videos.mapNotNull { video ->
            val item = video.asObject() ?: return@mapNotNull null
            val key = item["key"].text()?.takeIf { YOUTUBE_ID.matches(it) } ?: return@mapNotNull null
            if (item["site"].text() != "YouTube" || item["type"].text() != "Trailer") {
                return@mapNotNull null
            }
            Trailer(key, item["name"].text() ?: "Trailer", item["official"].boolean() ?: false)
        }.sortedByDescending { it.official }
    }

    private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{6,20}")
}

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArray(): List<JsonElement> = (this as? JsonArray).orEmpty()

private fun JsonElement?.primitive(): JsonPrimitive? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }

internal fun JsonElement?.text(): String? {
    return primitive()?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun JsonElement?.int(): Int? = primitive()?.intOrNull

internal fun JsonElement?.double(): Double? = primitive()?.doubleOrNull

internal fun JsonElement?.boolean(): Boolean? = primitive()?.booleanOrNull

/**
 * How the app reaches TMDB. The client is separate from the one NewPipe uses for the video
 * services: it carries no cookies, and it never logs requests, whose URLs can contain the key.
 */
object TmdbNetwork {
    private val USER_AGENT = "PipeHome/" + BuildConfig.VERSION_NAME +
        " (https://github.com/jimmysbeat1/NewPipe; movie discovery)"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    /** Tests replace it to stay offline. */
    @Volatile
    var http = TmdbHttp { url, headers ->
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        for ((name, value) in headers) {
            request.header(name, value)
        }
        client.newCall(request.build()).execute().use { response ->
            TmdbResponse(response.code, response.body.string())
        }
    }
}
