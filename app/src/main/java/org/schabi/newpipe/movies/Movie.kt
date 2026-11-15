/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies

import kotlinx.serialization.Serializable

enum class Genre(vararg val keywords: String) {
    ACTION("action", "martial arts", "kung fu"),
    ADVENTURE("adventure"),
    ANIMATION("animation", "animated", "anime", "cartoon"),
    CLASSICS("classic", "classics", "silent film"),
    COMEDY("comedy", "comedies"),
    CRIME("crime", "gangster", "heist", "noir", "true crime"),
    DOCUMENTARY("documentary", "documentaries", "docuseries"),
    DRAMA("drama"),
    FAMILY("family", "kids", "children"),
    FANTASY("fantasy"),
    HORROR("horror", "zombie", "zombies", "vampire", "slasher", "haunted"),
    MYSTERY("mystery", "detective", "whodunit"),
    ROMANCE("romance", "romantic", "rom-com", "love story"),
    SCI_FI("sci-fi", "scifi", "science fiction", "alien", "aliens"),
    THRILLER("thriller", "suspense"),
    WAR("war", "wwii", "ww2"),
    WESTERN("western", "westerns", "cowboy", "spaghetti western")
}

/**
 * A full-length film that can be watched for free.
 *
 * @param title the cleaned up title, see [MovieCatalog.cleanTitle]
 * @param trusted whether the uploader is one of the [MovieCatalog.DISTRIBUTORS]
 * @param officialOnly whether the film can only be played by the official YouTube player
 * (purchased, rented or otherwise DRM protected content)
 */
@Serializable
data class Movie(
    val serviceId: Int,
    val url: String,
    val title: String,
    val originalTitle: String,
    val channel: String,
    val channelUrl: String?,
    val durationSeconds: Long,
    val viewCount: Long = -1,
    val uploadEpochMillis: Long? = null,
    val thumbnailUrl: String? = null,
    val year: Int? = null,
    val genres: Set<Genre> = emptySet(),
    val trusted: Boolean = false,
    val officialOnly: Boolean = false
)

/** A channel known to publish films it holds the rights to (or that are in the public domain). */
class Distributor(val channelId: String, val name: String, vararg val genres: Genre)
