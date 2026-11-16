/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.library

import java.io.IOException
import java.net.URLDecoder
import java.util.Locale
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl

/**
 * Films from Wikimedia Commons, through the documented
 * [MediaWiki Action API](https://www.mediawiki.org/wiki/API:Main_page): a search for large
 * video files in the category tree of film videos, with `prop=videoinfo` for the running time,
 * the licence (`extmetadata`) and the transcoded renditions of each file.
 */
open class CommonsSource(private val http: FilmHttp) {
    /** @param nextOffset where the next page of the search starts, or `null` at the end */
    class Page(val verdicts: List<Verdict>, val nextOffset: Int?)

    @Throws(IOException::class)
    open fun search(text: String?, offset: Int): Page {
        val pages = LinkedHashMap<Long, JsonObject>()
        var continuation = emptyMap<String, String>()
        var nextOffset: Int? = null
        // one search page can take several requests: the API hands out the file details in batches
        var batches = 0
        while (batches++ < MAX_BATCHES) {
            val root = parse(http.get(searchUrl(text, offset, continuation)))
            (root["error"] as? JsonObject)?.let { error ->
                throw IOException("Wikimedia Commons: " + (error["info"].string() ?: error["code"].string()))
            }
            collect(root, pages)
            val next = root["continue"] as? JsonObject
            val complete = (root["batchcomplete"] as? JsonPrimitive)?.booleanOrNull == true
            if (complete || next == null) {
                nextOffset = (next?.get("gsroffset") as? JsonPrimitive)?.intOrNull
                break
            }
            continuation = next.mapValues { it.value.string().orEmpty() }
        }
        val ordered = pages.values.sortedBy { (it["index"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE }
        return Page(ordered.map(::audit), nextOffset)
    }

    /** Audits a single file again, e.g. right before it is downloaded. */
    @Throws(IOException::class)
    open fun verify(pageId: String): Verdict {
        val root = parse(http.get(detailsUrl(pageId)))
        val page = ((root["query"] as? JsonObject)?.get("pages") as? JsonArray)?.firstOrNull() as? JsonObject
        return if (page == null) Verdict.Rejected(key(pageId), Rejection.UNAVAILABLE) else audit(page)
    }

    companion object {
        const val HOST = "commons.wikimedia.org"
        const val PAGE_SIZE = 30

        /** The category tree Commons files videos of films under (by decade, then by year). */
        const val FILM_VIDEOS_CATEGORY = "Videos of films by decade"

        /** In kilobytes. Full-length films are large; this keeps clips out of the results. */
        private const val MIN_FILE_SIZE_KB = 100_000
        private const val MAX_BATCHES = 8
        private const val CREATOR_LENGTH = 120
        private val EXTMETADATA = listOf(
            "License",
            "LicenseUrl",
            "LicenseShortName",
            "Categories",
            "ObjectName",
            "DateTimeOriginal",
            "Artist",
            "ImageDescription"
        )
        private val DISPUTED = Regex(
            "deletion request|speedy deletion|copyright violation|missing permission|" +
                "without (a )?licen[sc]e|unknown copyright|disputed|licen[sc]e review needed|" +
                "needing licen[sc]e review|possibly unfree|missing source",
            RegexOption.IGNORE_CASE
        )
        private val json = Json { ignoreUnknownKeys = true }

        fun key(pageId: String): String = FilmSource.WIKIMEDIA_COMMONS.name + ":" + pageId

        fun searchExpression(text: String?): String {
            val words = FilmRules.queryWords(text.orEmpty())
            val base = "filetype:video filesize:>$MIN_FILE_SIZE_KB deepcat:\"$FILM_VIDEOS_CATEGORY\""
            return if (words.isEmpty()) base else base + " " + words.joinToString(" ")
        }

        private fun api(): HttpUrl.Builder {
            return HttpUrl.Builder().scheme("https").host(HOST)
                .addPathSegments("w/api.php")
                .addQueryParameter("action", "query")
                .addQueryParameter("format", "json")
                .addQueryParameter("formatversion", "2")
                .addQueryParameter("prop", "videoinfo")
                .addQueryParameter("viprop", "url|size|mime|mediatype|extmetadata|derivatives|sha1")
                .addQueryParameter("viurlwidth", "400")
                .addQueryParameter("viextmetadatafilter", EXTMETADATA.joinToString("|"))
        }

        fun detailsUrl(pageId: String): String = api().addQueryParameter("pageids", pageId).build().toString()

        fun searchUrl(text: String?, offset: Int, continuation: Map<String, String> = emptyMap()): String {
            val url = api()
                .addQueryParameter("generator", "search")
                .addQueryParameter("gsrnamespace", "6")
                .addQueryParameter("gsrlimit", PAGE_SIZE.toString())
                .addQueryParameter("gsroffset", offset.toString())
                .addQueryParameter("gsrsearch", searchExpression(text))
            for ((key, value) in continuation) {
                if (key != "gsroffset") {
                    url.setQueryParameter(key, value)
                }
            }
            return url.build().toString()
        }

        @Throws(IOException::class)
        private fun parse(body: String): JsonObject {
            try {
                return json.parseToJsonElement(body) as? JsonObject
                    ?: throw IOException("Unexpected response from Wikimedia Commons")
            } catch (e: SerializationException) {
                throw IOException("Unreadable response from Wikimedia Commons", e)
            }
        }

        /** Remembers the pages of a response that already carry their file details. */
        private fun collect(root: JsonObject, pages: MutableMap<Long, JsonObject>) {
            val list = (root["query"] as? JsonObject)?.get("pages") as? JsonArray ?: return
            for (element in list) {
                val page = element as? JsonObject ?: continue
                val id = (page["pageid"] as? JsonPrimitive)?.longOrNull ?: continue
                if (page["videoinfo"] is JsonArray || id !in pages) {
                    pages[id] = page
                }
            }
        }

        /** Audits one file page of a `prop=videoinfo` response. */
        fun audit(page: JsonObject): Verdict {
            val id = (page["pageid"] as? JsonPrimitive)?.longOrNull?.toString().orEmpty()
            val reject = { reason: Rejection -> Verdict.Rejected(key(id), reason) }
            val info = (page["videoinfo"] as? JsonArray)?.firstOrNull() as? JsonObject
            val pageUrl = info?.get("descriptionurl").string()
            if (id.isEmpty() || info == null || pageUrl == null) {
                return reject(Rejection.UNAVAILABLE)
            }
            if (info["mediatype"].string() != "VIDEO") {
                return reject(Rejection.NOT_A_MOVIE)
            }
            val duration = (info["duration"] as? JsonPrimitive)?.doubleOrNull?.toLong() ?: 0L
            if (!FilmRules.isFullLength(duration)) {
                return reject(Rejection.NOT_FULL_LENGTH)
            }
            val metadata = info["extmetadata"] as? JsonObject
            val value = { key: String -> (metadata?.get(key) as? JsonObject)?.get("value").string() }
            val categories = value("Categories").orEmpty().split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (categories.any { DISPUTED.containsMatchIn(it) }) {
                return reject(Rejection.DISPUTED)
            }
            val licence = LicenceAudit.fromCommons(value("License"), value("LicenseUrl"), value("LicenseShortName"))
                ?: return reject(Rejection.NO_ACCEPTED_LICENCE)
            val fileTitle = page["title"].string().orEmpty().removePrefix("File:").substringBeforeLast('.')
            val rawTitle = value("ObjectName") ?: fileTitle
            FilmRules.rejectByText(rawTitle, categories)?.let { return reject(it) }
            val files = files(info, duration)
            if (files.isEmpty()) {
                return reject(Rejection.NO_PLAYABLE_FILE)
            }
            val year = FilmRules.year(rawTitle, fileTitle, value("DateTimeOriginal"))
            return Verdict.Accepted(
                Film(
                    source = FilmSource.WIKIMEDIA_COMMONS,
                    id = id,
                    title = FilmRules.cleanTitle(rawTitle),
                    year = year,
                    durationSeconds = duration,
                    genres = FilmRules.genres(categories, year),
                    posterUrl = info["thumburl"].string()?.substringBefore('?'),
                    pageUrl = pageUrl,
                    licence = licence,
                    creator = value("Artist")?.let(FilmRules::plainText)?.take(CREATOR_LENGTH),
                    description = value("ImageDescription")?.let(FilmRules::plainText),
                    files = files
                )
            )
        }

        private fun files(info: JsonObject, duration: Long): List<FilmFile> {
            val files = ArrayList<FilmFile>()
            val originalUrl = info["url"].string()?.substringBefore('?')
            val mime = info["mime"].string().orEmpty()
            if (originalUrl != null && (mime == "video/webm" || mime == "video/mp4")) {
                files.add(
                    FilmFile(
                        url = originalUrl,
                        fileName = fileName(originalUrl),
                        height = (info["height"] as? JsonPrimitive)?.intOrNull ?: 0,
                        container = if (mime == "video/mp4") "MP4" else "WEBM",
                        sizeBytes = (info["size"] as? JsonPrimitive)?.longOrNull ?: -1,
                        sha1 = info["sha1"].string()?.lowercase(Locale.ROOT),
                        original = true
                    )
                )
            }
            for (element in (info["derivatives"] as? JsonArray).orEmpty()) {
                val derivative = element as? JsonObject ?: continue
                val url = derivative["src"].string()?.substringBefore('?') ?: continue
                if (derivative["type"].string()?.startsWith("video/webm") != true || !url.endsWith(".webm")) {
                    continue
                }
                val bandwidth = (derivative["bandwidth"] as? JsonPrimitive)?.longOrNull ?: 0
                files.add(
                    FilmFile(
                        url = url,
                        fileName = fileName(url),
                        height = (derivative["height"] as? JsonPrimitive)?.intOrNull ?: 0,
                        container = "WEBM",
                        sizeBytes = if (bandwidth > 0) bandwidth * duration / 8 else -1,
                        sizeEstimated = true
                    )
                )
            }
            return files
                .sortedWith(compareByDescending<FilmFile> { it.height }.thenByDescending { it.original })
                .distinctBy { it.height }
        }

        private fun fileName(url: String): String {
            return URLDecoder.decode(url.substringAfterLast('/'), "UTF-8")
        }
    }
}
