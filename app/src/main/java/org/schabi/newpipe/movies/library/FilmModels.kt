/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.library

import kotlinx.serialization.Serializable
import org.schabi.newpipe.movies.Genre

enum class FilmSource(val displayName: String) {
    INTERNET_ARCHIVE("Internet Archive"),
    WIKIMEDIA_COMMONS("Wikimedia Commons")
}

/**
 * The licences the library accepts. Every one of them permits anyone to download and keep a copy
 * of the work, which is the permission the library relies on.
 */
enum class LicenceKind(val shortName: String) {
    PUBLIC_DOMAIN("Public domain"),
    CC0("CC0"),
    CC_BY("CC BY"),
    CC_BY_SA("CC BY-SA"),
    CC_BY_NC("CC BY-NC"),
    CC_BY_NC_SA("CC BY-NC-SA"),
    CC_BY_ND("CC BY-ND"),
    CC_BY_NC_ND("CC BY-NC-ND")
}

/**
 * @param name what to show, e.g. "CC BY-SA 4.0"
 * @param url the licence deed, if the source names one
 * @param evidence the raw value the source published, kept so that the decision can be audited
 */
@Serializable
data class FilmLicence(
    val kind: LicenceKind,
    val name: String,
    val url: String? = null,
    val evidence: String = ""
)

/**
 * One downloadable rendition of a film.
 *
 * @param sizeBytes the size, or -1 if the source does not say
 * @param sizeEstimated whether [sizeBytes] was computed from the bitrate instead of published
 * @param sha1 the checksum published by the source, if any; downloads are verified against it
 */
@Serializable
data class FilmFile(
    val url: String,
    val fileName: String,
    val height: Int,
    val container: String,
    val sizeBytes: Long = -1,
    val sizeEstimated: Boolean = false,
    val sha1: String? = null,
    val original: Boolean = false
) {
    val label: String
        get() = (if (height > 0) "${height}p" else container) + if (original) " (original)" else ""
}

/** A full-length film whose source states a licence that permits downloading it. */
@Serializable
data class Film(
    val source: FilmSource,
    val id: String,
    val title: String,
    val year: Int? = null,
    val durationSeconds: Long,
    val genres: Set<Genre> = emptySet(),
    val posterUrl: String? = null,
    val pageUrl: String,
    val licence: FilmLicence,
    val creator: String? = null,
    val description: String? = null,
    val files: List<FilmFile> = emptyList(),
    val popularity: Long = 0
) {
    val key: String
        get() = source.name + ":" + id
}

/** Why a candidate was left out of the library. */
enum class Rejection {
    UNAVAILABLE,
    NOT_A_MOVIE,
    NO_ACCEPTED_LICENCE,
    ACCESS_RESTRICTED,
    NOT_FULL_LENGTH,
    TRAILER_OR_EXCERPT,
    TV_OR_SERIAL,
    SEVERAL_FILMS,
    NO_PLAYABLE_FILE,
    ADULT,
    DISPUTED
}

sealed class Verdict {
    class Accepted(val film: Film) : Verdict()

    /** @param key the [Film.key] the candidate would have had */
    class Rejected(val key: String, val reason: Rejection) : Verdict()
}
