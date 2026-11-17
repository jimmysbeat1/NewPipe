/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.discover

import android.content.Context
import org.schabi.newpipe.recommend.data.PersonalStore

/** Provides the parts of the Movies tab that are shared by its screens. */
object Discover {
    @Volatile
    private var instance: DiscoverRepository? = null

    fun repository(context: Context): DiscoverRepository {
        return instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }
    }

    fun client(context: Context): TmdbClient {
        val credentials = TmdbCredentials.get(context)
        // the network is looked up for every request, so that tests can replace it
        return TmdbClient(TmdbHttp { url, headers -> TmdbNetwork.http.get(url, headers) }, credentials::get)
    }

    private fun create(context: Context): DiscoverRepository {
        return DiscoverRepository(PersonalStore.get(context), client(context))
    }

    /** Drops the shared repository; it is created again when next needed. For tests. */
    fun reset() {
        synchronized(this) { instance = null }
    }
}
