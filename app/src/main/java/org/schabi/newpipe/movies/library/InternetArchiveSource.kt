/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.library

import java.io.IOException
import java.util.Locale
import kotlin.math.abs
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.HttpUrl
import org.schabi.newpipe.movies.Genre

/**
 * Films from the Internet Archive, through its documented public APIs:
 * the [advanced search](https://archive.org/advancedsearch.php) to find candidates in the
 * "Feature Films" collection and the
 * [metadata API](https://archive.org/developers/md-read.html) to audit each of them.
 */
open class InternetArchiveSource(private val http: FilmHttp) {
    class Hit(val identifier: String, val downloads: Long)

    /** Finds candidates, most downloaded first. They still have to pass [verify]. */
    @Throws(IOException::class)
    open fun search(text: String?, genre: Genre?, page: Int): List<Hit> {
        return parseSearch(http.get(searchUrl(text, genre, page)))
    }

    /** Loads the metadata of an item and decides whether it belongs in the library. */
    @Throws(IOException::class)
    open fun verify(hit: Hit): Verdict {
        val url = HttpUrl.Builder().scheme("https").host(HOST)
            .addPathSegment("metadata")
            .addPathSegment(hit.identifier)
            .build()
        return parseItem(hit.identifier, http.get(url.toString()), hit.downloads)
    }

    private class VideoFile(
        val name: String,
        val group: String,
        val original: Boolean,
        val extension: String,
        val seconds: Long?,
        val height: Int,
        val size: Long,
        val sha1: String?,
        val hidden: Boolean
    )

    companion object {
        const val HOST = "archive.org"
        const val PAGE_SIZE = 40

        /** Curated by the Internet Archive as its collection of feature films. */
        const val FEATURE_FILMS = "feature_films"

        private val EXCLUDED_COLLECTIONS = setOf(
            "short_films",
            "classic_tv",
            "television",
            "tvarchive",
            "classic_cartoons",
            "movie_trailers",
            "newsandpublicaffairs"
        )
        private val GENRE_COLLECTIONS = mapOf(
            Genre.COMEDY to listOf("Comedy_Films"),
            Genre.CRIME to listOf("Film_Noir"),
            Genre.HORROR to listOf("SciFi_Horror"),
            Genre.SCI_FI to listOf("SciFi_Horror", "film_scifi"),
            Genre.CLASSICS to listOf("silent_films")
        )
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "webm", "mkv", "mpeg", "mpg", "avi", "ogv", "mov", "wmv", "flv", "divx"
        )

        /** Containers Android plays without extra software. */
        private val PLAYABLE_EXTENSIONS = setOf("mp4", "m4v", "webm", "mkv")
        private const val SIMILAR_LENGTH = 0.03
        private const val LONG_SECONDS = 20 * 60L

        private val json = Json { ignoreUnknownKeys = true }

        fun key(identifier: String): String = FilmSource.INTERNET_ARCHIVE.name + ":" + identifier

        fun query(text: String?, genre: Genre?): String {
            val query = StringBuilder()
            query.append("mediatype:movies AND collection:").append(FEATURE_FILMS)
            // only items that declare a Creative Commons licence or public-domain mark
            query.append(" AND licenseurl:http*creativecommons.org*")
            query.append(" AND -collection:(").append(EXCLUDED_COLLECTIONS.joinToString(" OR ")).append(')')
            val words = FilmRules.queryWords(text.orEmpty())
            if (words.isNotEmpty()) {
                val all = words.joinToString(" AND ")
                query.append(" AND (title:($all) OR creator:($all) OR subject:($all))")
            }
            if (genre != null) {
                val subjects = genre.keywords.joinToString(" OR ") { "\"$it\"" }
                query.append(" AND (subject:($subjects)")
                GENRE_COLLECTIONS[genre]?.let { collections ->
                    query.append(" OR collection:(").append(collections.joinToString(" OR ")).append(')')
                }
                query.append(')')
            }
            return query.toString()
        }

        fun searchUrl(text: String?, genre: Genre?, page: Int): String {
            return HttpUrl.Builder().scheme("https").host(HOST)
                .addPathSegment("advancedsearch.php")
                .addQueryParameter("q", query(text, genre))
                .addQueryParameter("fl[]", "identifier")
                .addQueryParameter("fl[]", "downloads")
                .addQueryParameter("sort[]", "downloads desc")
                .addQueryParameter("rows", PAGE_SIZE.toString())
                .addQueryParameter("page", page.toString())
                .addQueryParameter("output", "json")
                .build()
                .toString()
        }

        @Throws(IOException::class)
        fun parseSearch(body: String): List<Hit> {
            try {
                val root = json.parseToJsonElement(body) as? JsonObject
                val docs = (root?.get("response") as? JsonObject)?.get("docs") as? JsonArray
                    ?: throw IOException("Unexpected search response")
                return docs.mapNotNull { doc ->
                    val fields = doc as? JsonObject ?: return@mapNotNull null
                    val identifier = fields["identifier"].string() ?: return@mapNotNull null
                    Hit(identifier, fields["downloads"].string()?.toLongOrNull() ?: 0)
                }
            } catch (e: SerializationException) {
                throw IOException("Unreadable search response", e)
            }
        }

        /** Audits the metadata API response of one item. */
        @Throws(IOException::class)
        fun parseItem(identifier: String, body: String, downloads: Long = 0): Verdict {
            val root = try {
                json.parseToJsonElement(body) as? JsonObject
            } catch (e: SerializationException) {
                throw IOException("Unreadable metadata response", e)
            }
            val metadata = root?.get("metadata") as? JsonObject
            val dark = (root?.get("is_dark") as? JsonPrimitive)?.booleanOrNull == true
            if (root == null || metadata == null || dark) {
                return Verdict.Rejected(key(identifier), Rejection.UNAVAILABLE)
            }
            return audit(identifier, root, metadata, downloads)
        }

        private fun audit(identifier: String, root: JsonObject, metadata: JsonObject, downloads: Long): Verdict {
            val reject = { reason: Rejection -> Verdict.Rejected(key(identifier), reason) }
            val collections = metadata["collection"].strings()
            if (metadata["mediatype"].string() != "movies" || FEATURE_FILMS !in collections) {
                return reject(Rejection.NOT_A_MOVIE)
            }
            if (collections.any { it in EXCLUDED_COLLECTIONS }) {
                return reject(Rejection.TV_OR_SERIAL)
            }
            val licence = LicenceAudit.fromUrl(metadata["licenseurl"].string())
                ?: return reject(Rejection.NO_ACCEPTED_LICENCE)
            if (metadata["access-restricted-item"].string() == "true") {
                return reject(Rejection.ACCESS_RESTRICTED)
            }
            val rawTitle = metadata["title"].string() ?: identifier
            val subjects = metadata["subject"].strings()
                .flatMap { it.split(';', ',') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            FilmRules.rejectByText(rawTitle, subjects)?.let { return reject(it) }

            val videos = videoFiles(root["files"] as? JsonArray)
            if (videos.isEmpty()) {
                return reject(Rejection.NOT_A_MOVIE)
            }
            val groups = videos.groupBy { it.group }.values.toList()
            val fallback = FilmRules.parseDuration(metadata["runtime"].string()).takeIf { groups.size == 1 }
            val lengths = groups.map { group -> group.mapNotNull { it.seconds }.maxOrNull() ?: fallback ?: 0L }
            val longest = lengths.maxOrNull() ?: 0L
            if (!FilmRules.isFullLength(longest)) {
                return reject(Rejection.NOT_FULL_LENGTH)
            }
            // several long videos of different lengths: a series, a serial or a bundle of films
            val long = lengths.filter { it >= LONG_SECONDS }
            if (long.any { abs(it - longest) > longest * SIMILAR_LENGTH }) {
                return reject(Rejection.SEVERAL_FILMS)
            }
            val candidates = groups.filterIndexed { index, _ -> lengths[index] >= LONG_SECONDS }
            val files = candidates
                .map { group -> playable(identifier, group) }
                .maxByOrNull { it.size }
                .orEmpty()
            if (files.isEmpty()) {
                return reject(Rejection.NO_PLAYABLE_FILE)
            }

            val year = FilmRules.year(metadata["year"].string(), metadata["date"].string(), rawTitle)
            val page = HttpUrl.Builder().scheme("https").host(HOST)
                .addPathSegment("details")
                .addPathSegment(identifier)
                .build()
            val poster = HttpUrl.Builder().scheme("https").host(HOST)
                .addPathSegments("services/img")
                .addPathSegment(identifier)
                .build()
            return Verdict.Accepted(
                Film(
                    source = FilmSource.INTERNET_ARCHIVE,
                    id = identifier,
                    title = FilmRules.cleanTitle(rawTitle),
                    year = year,
                    durationSeconds = longest,
                    genres = FilmRules.genres(subjects + collections, year),
                    posterUrl = poster.toString(),
                    pageUrl = page.toString(),
                    licence = licence,
                    creator = (metadata["director"].string() ?: metadata["creator"].string())?.take(120),
                    description = metadata["description"].string()?.let(FilmRules::plainText),
                    files = files,
                    popularity = downloads
                )
            )
        }

        private fun videoFiles(files: JsonArray?): List<VideoFile> {
            return files.orEmpty().mapNotNull { element ->
                val file = element as? JsonObject ?: return@mapNotNull null
                val name = file["name"].string() ?: return@mapNotNull null
                val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (extension !in VIDEO_EXTENSIONS) {
                    return@mapNotNull null
                }
                val original = file["source"].string() == "original"
                VideoFile(
                    name = name,
                    group = if (original) name else file["original"].string() ?: name,
                    original = original,
                    extension = extension,
                    seconds = FilmRules.parseDuration(file["length"].string()),
                    height = file["height"].string()?.toIntOrNull() ?: 0,
                    size = file["size"].string()?.toLongOrNull() ?: -1,
                    sha1 = file["sha1"].string()?.lowercase(Locale.ROOT),
                    hidden = file["private"].string() == "true"
                )
            }
        }

        private fun playable(identifier: String, group: List<VideoFile>): List<FilmFile> {
            return group
                .filter { it.extension in PLAYABLE_EXTENSIONS && !it.hidden && it.size > 0 }
                .sortedWith(compareByDescending<VideoFile> { it.height }.thenByDescending { it.size })
                .distinctBy { it.height to it.extension }
                .map { file ->
                    val url = HttpUrl.Builder().scheme("https").host(HOST)
                        .addPathSegment("download")
                        .addPathSegment(identifier)
                        .addPathSegments(file.name)
                        .build()
                    FilmFile(
                        url = url.toString(),
                        fileName = file.name.substringAfterLast('/'),
                        height = file.height,
                        container = if (file.extension == "m4v") "MP4" else file.extension.uppercase(Locale.ROOT),
                        sizeBytes = file.size,
                        sha1 = file.sha1,
                        original = file.original
                    )
                }
        }
    }
}
