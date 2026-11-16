/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.library

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.BuildConfig

/** Fetches API responses. Implementations block and throw [IOException] on failure. */
fun interface FilmHttp {
    @Throws(IOException::class)
    fun get(url: String): String
}

/**
 * The HTTP client of the film library. It is separate from the one NewPipe uses for the video
 * services: requests carry no cookies and identify the app honestly, as the API guidelines of
 * the Internet Archive and of Wikimedia ask for.
 */
object FilmNetwork {
    val USER_AGENT = "PipeHome/" + BuildConfig.VERSION_NAME +
        " (https://github.com/jimmysbeat1/NewPipe; film library)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    /** How the sources are reached. Tests replace it to stay offline. */
    @Volatile
    var http = FilmHttp { url ->
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP " + response.code + " from " + request.url.host)
            }
            response.body.string()
        }
    }
}

/** The text values of a JSON field that may be absent, a single value or a list of values. */
internal fun JsonElement?.strings(): List<String> {
    return when (this) {
        null, is JsonNull -> emptyList()
        is JsonArray -> flatMap { it.strings() }
        is JsonPrimitive -> listOf(content)
        else -> emptyList()
    }
}

internal fun JsonElement?.string(): String? = strings().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
