/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.library

import android.widget.ImageView
import coil3.imageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.error
import coil3.request.placeholder
import coil3.request.target
import org.schabi.newpipe.R
import org.schabi.newpipe.util.image.ImageStrategy

/**
 * Loads the artwork of films. Wikimedia refuses image requests that do not identify the
 * application, so these requests carry the user agent of the film library.
 */
object FilmPosters {
    private val HEADERS = NetworkHeaders.Builder().set("User-Agent", FilmNetwork.USER_AGENT).build()

    fun load(target: ImageView, url: String?) {
        // respects the setting to not load images at all
        val taken = url?.takeIf { it.isNotEmpty() && ImageStrategy.shouldLoadImages() }
        val request = ImageRequest.Builder(target.context)
            .data(taken)
            .httpHeaders(HEADERS)
            .placeholder(R.drawable.placeholder_thumbnail_video)
            .error(R.drawable.placeholder_thumbnail_video)
            .target(target)
            .build()
        target.context.imageLoader.enqueue(request)
    }
}
