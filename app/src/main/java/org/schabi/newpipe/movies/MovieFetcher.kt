/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies

import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.util.image.ImageStrategy

/**
 * Loads films through NewPipe Extractor with anonymous requests. All functions block and throw
 * on failure; call them from a background thread.
 */
open class MovieFetcher {
    /** One page of a channel's uploads and where to continue. */
    class ChannelPage(val movies: List<Movie>, val next: Continuation?)

    class Continuation(val handler: ListLinkHandler, val page: Page)

    private val service
        get() = ServiceList.YouTube

    /** Loads the newest full-length uploads of a distributor. */
    open fun channel(distributor: Distributor): ChannelPage {
        val handler = service.channelTabLHFactory
            .fromQuery("channel/" + distributor.channelId, listOf(ChannelTabs.VIDEOS), "")
        val info = ChannelTabInfo.getInfo(service, handler)
        return ChannelPage(
            toMovies(info.relatedItems, distributor),
            info.nextPage?.let { Continuation(handler, it) }
        )
    }

    open fun more(distributor: Distributor, continuation: Continuation): ChannelPage {
        val page = ChannelTabInfo.getMoreItems(service, continuation.handler, continuation.page)
        return ChannelPage(
            toMovies(page.items, distributor),
            page.nextPage?.let { Continuation(continuation.handler, it) }
        )
    }

    /** Searches the service for full-length films matching [query]. */
    open fun search(query: String): List<Movie> {
        val handler = service.searchQHFactory.fromQuery("$query full movie", listOf("videos"), "")
        val info = SearchInfo.getInfo(service, handler)
        val items = ArrayList(info.relatedItems)
        info.nextPage?.let { items.addAll(SearchInfo.getMoreItems(service, handler, it).items) }
        return toMovies(items, null)
    }

    private fun toMovies(items: List<*>, distributor: Distributor?): List<Movie> {
        return items.filterIsInstance<StreamInfoItem>().mapNotNull { item ->
            MovieCatalog.toMovie(
                serviceId = item.serviceId,
                url = item.url,
                rawTitle = item.name.orEmpty(),
                channel = distributor?.name ?: item.uploaderName.orEmpty(),
                // uploads listed on a channel page do not always carry the channel's url
                channelUrl = distributor?.let { "https://www.youtube.com/channel/" + it.channelId }
                    ?: item.uploaderUrl,
                durationSeconds = item.duration,
                viewCount = item.viewCount,
                uploadEpochMillis = item.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli(),
                thumbnailUrl = ImageStrategy.imageListToDbUrl(item.thumbnails)
            )
        }.distinctBy { it.url }
    }
}
