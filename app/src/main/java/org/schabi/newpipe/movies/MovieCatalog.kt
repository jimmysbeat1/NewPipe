/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies

import java.util.Locale

/**
 * The rules that decide what the Movies tab shows.
 *
 * "Free movie" searches on YouTube are full of re-uploads by people who do not hold the rights.
 * To only surface films that are legitimately available, the tab is built from a curated list of
 * [DISTRIBUTORS]: channels run by film distributors, studios, archives and broadcasters that
 * publish complete films themselves. Uploads by anyone else are hidden unless the user
 * explicitly opts in, and are then labelled as unverified.
 */
object MovieCatalog {
    /** Films shorter than this are not considered full-length. */
    const val MIN_DURATION_SECONDS = 40 * 60L

    /** The channel YouTube uses for films it sells, rents or shows with its own DRM player. */
    const val YOUTUBE_MOVIES_CHANNEL_ID = "UClgRkhTL3_hImCAmdLfDE4g"

    /**
     * Channel ids were verified against the live service; channels are identified by id and
     * never by name, since names can be imitated.
     */
    val DISTRIBUTORS = listOf(
        Distributor("UCVFYikepF-avelvuIaQ_lHA", "Popcornflix"),
        Distributor("UC8IHAQMuiJdY6ALuhG7iU8Q", "FilmRise Movies"),
        Distributor("UC2u3R3pjOiPZu4LtTlKkxdw", "Maverick Movies"),
        Distributor("UCpHaAKu74UHvcYCi2g_PvBQ", "Shout! Studios"),
        Distributor("UCGBzBkV-MinlBvHBzZawfLQ", "Movie Central"),
        Distributor("UCOwzRfgVa2VkvdPEM1XVW4Q", "Flick Vault"),
        Distributor("UCIbCd95HIkwZx6DqlWSeDcA", "Free Movies By CONtv"),
        Distributor("UCu0bxC7vG_HKWJ7ijO9QKwg", "Kings of Horror", Genre.HORROR),
        Distributor("UCyJYCQ6WaEMhdZAuPo799bA", "Grjngo - Western Movies", Genre.WESTERN),
        Distributor("UCOg0aMAXmF3o5m243PxhE5g", "Timeless Classic Movies", Genre.CLASSICS),
        Distributor("UCycDFnpMeWzaITQSD1dWsOA", "Cult Cinema Classics", Genre.CLASSICS),
        Distributor("UCE34fwgW7kWr7tc7YiOWtRw", "PizzaFlix", Genre.CLASSICS),
        Distributor("UCvH6u_Qzn5RQdz9W198umDw", "Korean Classic Film", Genre.CLASSICS),
        Distributor("UCu4XcDBdnZkV6-5z2f16M0g", "Real Stories", Genre.DOCUMENTARY),
        Distributor("UC88lvyJe7aHZmcvzvubDFRg", "Timeline", Genre.DOCUMENTARY),
        Distributor("UCijcd0GR0fkxCAZwkiuWqtQ", "Free Documentary", Genre.DOCUMENTARY),
        Distributor("UCW39zufHfsuGgpLviKh297Q", "DW Documentary", Genre.DOCUMENTARY)
    )

    private val DISTRIBUTORS_BY_ID = DISTRIBUTORS.associateBy { it.channelId }

    private val CHANNEL_ID = Regex("/channel/(UC[A-Za-z0-9_-]{22})")
    private val BRACKETS = Regex("\\[[^\\]]*]|【[^】]*】")
    private val YEAR = Regex("[(\\[]\\s*((?:18|19|20)\\d{2})\\s*[)\\]]")
    private val FULL_MOVIE = Regex(
        "\\s*[-–—:(]?\\s*\\b(full|free|official|complete)\\b[^|]*?" +
            "\\b(movie|movies|film|documentary|feature)\\b.*$",
        RegexOption.IGNORE_CASE
    )
    private val TRAILING_NOISE = Regex(
        "\\s*[-–—:(]?\\s*\\b(4k|hd|uhd|1080p|english|subtitles|eng sub|remastered|restored)\\b.*$",
        RegexOption.IGNORE_CASE
    )
    private val WORD = Regex("[\\p{L}\\p{N}]+(?:-[\\p{L}\\p{N}]+)*")

    fun channelId(channelUrl: String?): String? {
        return channelUrl?.let { CHANNEL_ID.find(it)?.groupValues?.get(1) }
    }

    fun distributor(channelUrl: String?): Distributor? = DISTRIBUTORS_BY_ID[channelId(channelUrl)]

    fun isFeatureLength(durationSeconds: Long): Boolean = durationSeconds >= MIN_DURATION_SECONDS

    /** @return the release year if the title states one in brackets, e.g. "Charade (1963)" */
    fun year(rawTitle: String): Int? {
        return YEAR.findAll(rawTitle).lastOrNull()?.groupValues?.get(1)?.toInt()
    }

    /**
     * Strips the decoration distributors add to their upload titles, e.g.
     * `"The Curse | HORROR | Full Movie in English"` becomes `"The Curse"`.
     */
    fun cleanTitle(rawTitle: String): String {
        var title = rawTitle
        val year = YEAR.find(title)
        if (year != null && title.substring(0, year.range.first).any(Char::isLetterOrDigit)) {
            // "TAGLINE <symbol> Title (1961) cast": the title is what directly precedes the year
            title = BRACKETS.replace(title.substring(0, year.range.first), " ")
            val symbol = title.indexOfLast(::isSymbol)
            if (symbol >= 0 && title.substring(symbol + 1).any(Char::isLetterOrDigit)) {
                title = title.substring(symbol + 1)
            }
        } else {
            title = BRACKETS.replace(title, " ")
        }
        title = title.substringBefore('|').substringBefore('｜')
        title = FULL_MOVIE.replace(title, "")
        title = TRAILING_NOISE.replace(title, "")
        // an emoji or similar symbol usually separates the title from a tagline
        val symbol = title.indexOfFirst(::isSymbol)
        if (symbol > 2) {
            title = title.substring(0, symbol)
        }
        title = title.replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—', ':', ',', '.', '(', '"')
        if (title.length < 2) {
            return rawTitle.trim()
        }
        if (title.none(Char::isLowerCase) && title.any(Char::isUpperCase)) {
            title = title.lowercase(Locale.ROOT).split(' ').joinToString(" ") {
                it.replaceFirstChar { c -> c.titlecase(Locale.ROOT) }
            }
        }
        return title
    }

    private fun isSymbol(char: Char): Boolean {
        val type = Character.getType(char).toByte()
        return type == Character.OTHER_SYMBOL || type == Character.SURROGATE ||
            type == Character.NON_SPACING_MARK && char == '\uFE0F'
    }

    /** Derives genres from the words distributors put into titles, plus the channel's focus. */
    fun genres(rawTitle: String, distributor: Distributor?): Set<Genre> {
        val words = WORD.findAll(rawTitle.lowercase(Locale.ROOT)).map { it.value }.toList()
        val text = " " + words.joinToString(" ") + " "
        val genres = sortedSetOf<Genre>()
        for (genre in Genre.entries) {
            if (genre.keywords.any { " $it " in text }) {
                genres.add(genre)
            }
        }
        distributor?.genres?.let(genres::addAll)
        val year = year(rawTitle)
        if (year != null && year < 1970) {
            genres.add(Genre.CLASSICS)
        }
        return genres
    }

    /**
     * Turns an upload into a [Movie].
     *
     * @return `null` if it is not a full-length film
     */
    fun toMovie(
        serviceId: Int,
        url: String,
        rawTitle: String,
        channel: String,
        channelUrl: String?,
        durationSeconds: Long,
        viewCount: Long,
        uploadEpochMillis: Long?,
        thumbnailUrl: String?
    ): Movie? {
        val official = channelId(channelUrl) == YOUTUBE_MOVIES_CHANNEL_ID
        if (!official && !isFeatureLength(durationSeconds)) {
            return null
        }
        val distributor = distributor(channelUrl)
        return Movie(
            serviceId = serviceId,
            url = url,
            title = cleanTitle(rawTitle),
            originalTitle = rawTitle,
            channel = distributor?.name ?: channel,
            channelUrl = channelUrl,
            durationSeconds = durationSeconds,
            viewCount = viewCount,
            uploadEpochMillis = uploadEpochMillis,
            thumbnailUrl = thumbnailUrl,
            year = year(rawTitle),
            genres = genres(rawTitle, distributor),
            trusted = distributor != null || official,
            officialOnly = official
        )
    }

    /** @return the movies whose title or channel contains every word of [query] */
    fun search(movies: Collection<Movie>, query: String): List<Movie> {
        val words = WORD.findAll(query.lowercase(Locale.ROOT)).map { it.value }.toList()
        if (words.isEmpty()) {
            return emptyList()
        }
        return movies.filter { movie ->
            val haystack = (movie.originalTitle + " " + movie.channel).lowercase(Locale.ROOT)
            words.all { it in haystack }
        }
    }

    /**
     * Orders a catalog for browsing: newest first within a channel, channels taking turns, so
     * that the first screen shows a bit of everything instead of one distributor's uploads.
     */
    fun arrange(movies: Collection<Movie>): List<Movie> {
        val byChannel = movies.groupBy { it.channelUrl ?: it.channel }
            .toSortedMap()
            .values
            .map { list -> list.sortedByDescending { it.uploadEpochMillis ?: 0 } }
        val result = ArrayList<Movie>(movies.size)
        var index = 0
        while (result.size < movies.size) {
            byChannel.forEach { list -> list.getOrNull(index)?.let(result::add) }
            index++
        }
        return result
    }
}
