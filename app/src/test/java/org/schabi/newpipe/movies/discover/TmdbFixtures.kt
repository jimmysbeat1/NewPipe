/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.discover

import java.io.IOException
import java.util.Collections

/** Answers modelled on what the TMDB API returns, and a stand-in for the API itself. */
object TmdbFixtures {
    const val V3_KEY = "0123456789abcdef0123456789abcdef"
    const val V4_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJleGFtcGxlIiwic3ViIjoiMTIzIn0.c2lnbmF0dXJlLW9mLXRoZS10b2tlbg"

    fun movieJson(id: Int, title: String, date: String = "2025-03-14", votes: Int = 900, score: Double = 7.4): String {
        return """{"adult":false,"genre_ids":[28,878],"id":$id,"original_language":"en",""" +
            """"original_title":"$title","overview":"About $title.","popularity":88.5,"poster_path":null,""" +
            """"release_date":"$date","title":"$title","video":false,"vote_average":$score,"vote_count":$votes}"""
    }

    fun pageJson(page: Int, totalPages: Int, vararg movies: String): String {
        return """{"page":$page,"results":[${movies.joinToString(",")}],"total_pages":$totalPages,"total_results":${movies.size}}"""
    }

    private fun providers(vararg names: String): String {
        return names.mapIndexed { index, name ->
            """{"logo_path":"/logo$index.jpg","provider_id":${name.hashCode() and 0xffff},"provider_name":"$name","display_priority":$index}"""
        }.joinToString(",", "[", "]")
    }

    /** The answer of `movie/{id}/watch/providers`: [offers] maps a TMDB category to providers. */
    fun providersJson(id: Int, region: String, offers: Map<String, List<String>>): String {
        val categories = offers.entries.joinToString("") { (category, names) ->
            ""","$category":${providers(*names.toTypedArray())}"""
        }
        val country = """"$region":{"link":"https://www.themoviedb.org/movie/$id/watch?locale=$region"$categories}"""
        // other countries are present in real answers and must not leak into the result
        val other = """"US":{"link":"https://www.themoviedb.org/movie/$id/watch?locale=US","free":${providers("Pluto TV")}}"""
        return """{"id":$id,"results":{${if (offers.isEmpty()) other else "$country,$other"}}}"""
    }

    fun detailsJson(id: Int, title: String, region: String, offers: Map<String, List<String>>): String {
        val movie = movieJson(id, title).dropLast(1)
        val videos = """{"results":[""" +
            """{"key":"teaser00001","name":"Teaser","site":"YouTube","type":"Teaser","official":true},""" +
            """{"key":"fanmade0001","name":"Trailer 2","site":"YouTube","type":"Trailer","official":false},""" +
            """{"key":"official001","name":"Official Trailer","site":"YouTube","type":"Trailer","official":true},""" +
            """{"key":"123456","name":"Vimeo trailer","site":"Vimeo","type":"Trailer","official":true}]}"""
        val providers = providersJson(id, region, offers)
        return """$movie,"runtime":127,"genres":[{"id":28,"name":"Action"},{"id":878,"name":"Science Fiction"}],""" +
            """"videos":$videos,"watch/providers":$providers}"""
    }
}

/**
 * Stands in for the TMDB API. Answers are chosen by the path of the request; requests are
 * recorded so that tests can check what was asked and how the key was sent.
 */
class FakeTmdb : TmdbHttp {
    class Call(val url: String, val headers: Map<String, String>) {
        val path: String
            get() = url.removePrefix(TmdbClient.BASE_URL).substringBefore('?')

        val query: Map<String, String>
            get() = url.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
                java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                    java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
    }

    val calls: MutableList<Call> = Collections.synchronizedList(ArrayList())
    private val answers = LinkedHashMap<String, (Call) -> TmdbResponse>()

    @Volatile
    var offline = false

    /** Answers requests whose path is [path] with what [answer] returns. */
    fun on(path: String, answer: (Call) -> TmdbResponse) {
        synchronized(answers) { answers[path] = answer }
    }

    fun json(path: String, body: String) = on(path) { TmdbResponse(200, body) }

    fun paths(): List<String> = synchronized(calls) { calls.map { it.path } }

    override fun get(url: String, headers: Map<String, String>): TmdbResponse {
        val call = Call(url, headers)
        calls.add(call)
        if (offline) {
            throw IOException("Unable to resolve host for $url")
        }
        val answer = synchronized(answers) { answers[call.path] }
        return answer?.invoke(call) ?: TmdbResponse(404, """{"status_code":34,"status_message":"Not found"}""")
    }
}
