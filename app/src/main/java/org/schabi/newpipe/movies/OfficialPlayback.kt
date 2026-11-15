/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.util.external_communication.ShareUtils

/**
 * Hands content over to the official YouTube player.
 *
 * Purchased, rented and other DRM protected films can only be played by a licensed player that
 * the user is signed in to. This app neither has such a player nor tries to get around the
 * protection: it opens the film in the official YouTube app when that is installed, and in the
 * browser otherwise, where the user's own account and the platform's DRM take over.
 */
object OfficialPlayback {
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    const val PURCHASES_URL = "https://www.youtube.com/purchases"
    const val STORE_URL = "https://www.youtube.com/feed/storefront"

    fun open(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            .setPackage(YOUTUBE_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            ShareUtils.openUrlInBrowser(context, url)
        } catch (e: SecurityException) {
            ShareUtils.openUrlInBrowser(context, url)
        }
    }

    /** @return the url if it points to a YouTube video, `null` otherwise */
    fun normalizeYoutubeUrl(input: String): String? {
        val url = input.trim()
        return try {
            val factory = ServiceList.YouTube.streamLHFactory
            if (factory.acceptUrl(url)) factory.fromUrl(url).url else null
        } catch (e: Exception) {
            null
        }
    }
}
