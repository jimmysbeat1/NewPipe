/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.library

import java.util.Locale
import org.jsoup.Jsoup
import org.schabi.newpipe.movies.Genre

/** The rules, shared by all sources, for what counts as a full-length film. */
object FilmRules {
    /**
     * The usual threshold for a feature film (used by the Academy, the AFI and the BFI): a
     * running time of more than 40 minutes.
     */
    const val MIN_DURATION_SECONDS = 40 * 60L

    private const val DESCRIPTION_LENGTH = 600
    private const val MAX_QUERY_WORDS = 8
    private const val MIXED_COLLECTION = " scifi horror "
    private val OPERATORS = setOf("AND", "OR", "NOT", "TO")

    private val TRAILER = Regex(
        "\\b(trailers?|teasers?|previews?|promos?|clips?|excerpts?|outtakes?|bloopers?|" +
            "featurettes?|tv spots?|commercials?|sample)\\b",
        RegexOption.IGNORE_CASE
    )
    private val SERIAL = Regex(
        "\\b(episodes?|ep\\.? ?\\d+|s\\d{1,2} ?e\\d{1,3}|season \\d+|series \\d+|" +
            "chapters? \\d+|part \\d+|pt\\.? ?\\d+|reel \\d+|tv series|tv show|" +
            "television|newsreels?|serials?)\\b",
        RegexOption.IGNORE_CASE
    )
    private val SERIAL_LABEL = Regex(
        "\\b(episodes?|tv series|tv shows?|television (series|shows?|programs?|programmes?|episodes?)|" +
            "newsreels?|film serials?|serials)\\b",
        RegexOption.IGNORE_CASE
    )
    private val SHORT = Regex(
        "\\bshort (films?|subjects?|movies?)\\b|\\bshorts\\b|^short$",
        RegexOption.IGNORE_CASE
    )
    private val ADULT = Regex(
        "\\b(porn\\w*|erotic\\w*|hentai|xxx|adult (film|movie|video)s?|sex films?|stag films?|" +
            "nudist\\w*|nudie\\w*|sexploitation)\\b",
        RegexOption.IGNORE_CASE
    )
    private val HOURS_MINUTES = Regex("(\\d+) ?(?:h|hr|hrs|hours?)\\b")
    private val MINUTES = Regex("(\\d+(?:\\.\\d+)?) ?(?:m|min|mins|minutes?)\\b")
    private val SECONDS = Regex("(\\d+(?:\\.\\d+)?) ?(?:s|sec|secs|seconds?)\\b")
    private val CLOCK = Regex("^(\\d+)[:.](\\d{1,2})(?:[:.](\\d{1,2}))?$")
    private val NUMBER = Regex("^\\d+(?:\\.\\d+)?$")
    private val YEAR = Regex("\\b(1[89]\\d{2}|20\\d{2})\\b")
    private val WORD = Regex("[\\p{L}\\p{N}]+(?:-[\\p{L}\\p{N}]+)*")

    fun isFullLength(durationSeconds: Long): Boolean = durationSeconds >= MIN_DURATION_SECONDS

    /**
     * Checks titles, subjects and categories for signs that an item is not a complete film.
     *
     * @return the reason to reject it, or `null`
     */
    fun rejectByText(title: String, labels: Collection<String>): Rejection? {
        val all = labels + title
        return when {
            all.any { ADULT.containsMatchIn(it) } -> Rejection.ADULT
            all.any { TRAILER.containsMatchIn(it) } -> Rejection.TRAILER_OR_EXCERPT
            SERIAL.containsMatchIn(title) -> Rejection.TV_OR_SERIAL
            labels.any { SERIAL_LABEL.containsMatchIn(it) } -> Rejection.TV_OR_SERIAL
            all.any { SHORT.containsMatchIn(it) } -> Rejection.NOT_FULL_LENGTH
            else -> null
        }
    }

    /**
     * Parses the running times found in the wild: `3117.52` (seconds), `1:18:44`, `60:00`,
     * `1:46.08`, `75 min`, `1 h 20 min`, `20 minutes 33 seconds`.
     *
     * @return the duration in seconds, or `null` if the text is not understood
     */
    fun parseDuration(text: String?): Long? {
        val value = text?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (value.isEmpty()) {
            return null
        }
        if (NUMBER.matches(value)) {
            return value.toDouble().toLong()
        }
        CLOCK.find(value)?.let { match ->
            val (first, second, third) = match.destructured
            return if (third.isEmpty()) {
                first.toLong() * 60 + second.toLong()
            } else {
                first.toLong() * 3600 + second.toLong() * 60 + third.toLong()
            }
        }
        val hours = HOURS_MINUTES.find(value)?.groupValues?.get(1)?.toLong()
        val minutes = MINUTES.find(value)?.groupValues?.get(1)?.toDouble()
        val seconds = SECONDS.find(value)?.groupValues?.get(1)?.toDouble()
        if (hours == null && minutes == null && seconds == null) {
            return null
        }
        return (hours ?: 0) * 3600 + ((minutes ?: 0.0) * 60).toLong() + (seconds ?: 0.0).toLong()
    }

    /** @return the first plausible year in [texts], preferring earlier entries */
    fun year(vararg texts: String?): Int? {
        for (text in texts) {
            val year = text?.let { YEAR.find(it) }?.value?.toInt()
            if (year != null) {
                return year
            }
        }
        return null
    }

    /** Derives genres from the subjects, collections or categories a source assigns to a film. */
    fun genres(labels: Collection<String>, year: Int?): Set<Genre> {
        val genres = sortedSetOf<Genre>()
        for (label in labels) {
            val words = WORD.findAll(label.lowercase(Locale.ROOT).replace('_', ' ')).map { it.value }
            val text = " " + words.joinToString(" ") + " "
            if (text == MIXED_COLLECTION) {
                // the "Sci-Fi / Horror" collection says nothing about which of the two a film is
                continue
            }
            for (genre in Genre.entries) {
                if (genre.keywords.any { " $it " in text }) {
                    genres.add(genre)
                }
            }
            if (" silent " in text || " silents " in text) {
                genres.add(Genre.CLASSICS)
            }
            if (" film noir " in text) {
                genres.add(Genre.CRIME)
            }
        }
        if (year != null && year < 1970) {
            genres.add(Genre.CLASSICS)
        }
        return genres
    }

    /** `"The General (1926)"` becomes `"The General"`; underscores become spaces. */
    fun cleanTitle(raw: String): String {
        var title = raw.replace('_', ' ').trim()
        title = title.replace(Regex("\\s*[(\\[]\\s*(1[89]\\d{2}|20\\d{2})(\\s+film)?\\s*[)\\]]"), " ")
        title = title.replace(Regex("\\s*[(\\[]\\s*(film|full movie|complete|hd|720p|1080p)\\s*[)\\]]", RegexOption.IGNORE_CASE), " ")
        title = title.replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—', ':', ',')
        return title.ifEmpty { raw.trim() }
    }

    /** @return the films whose title or creator contains every word of [query] */
    fun search(films: Collection<Film>, query: String): List<Film> {
        val words = WORD.findAll(query.lowercase(Locale.ROOT)).map { it.value }.toList()
        if (words.isEmpty()) {
            return emptyList()
        }
        return films.filter { film ->
            val haystack = (film.title + " " + film.creator.orEmpty() + " " + (film.year ?: ""))
                .lowercase(Locale.ROOT)
            words.all { it in haystack }
        }
    }

    /** Turns the HTML some metadata fields contain into a short plain text. */
    fun plainText(html: String): String = Jsoup.parse(html).text().trim().take(DESCRIPTION_LENGTH)

    /** @return the words of a user query, safe to embed in a search expression */
    fun queryWords(query: String): List<String> {
        val words = WORD.findAll(query).map { it.value }.toList()
        return words
            .filter { it.length > 1 || it.any(Char::isDigit) }
            .filterNot { it.uppercase(Locale.ROOT) in OPERATORS }
            .take(MAX_QUERY_WORDS)
    }
}
