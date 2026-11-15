/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.recommend.data

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.recommend.engine.Candidate
import org.schabi.newpipe.recommend.engine.CandidateSource

/**
 * Fetches recommendation candidates through NewPipe Extractor, i.e. with the very same anonymous
 * requests NewPipe makes when the user searches or opens a video. No account, no cookies and no
 * identifier are involved; the service only ever sees individual, unlinked queries.
 *
 * All functions block and throw on failure; call them from a background thread.
 */
open class CandidateFetcher {

    /** Searches for videos about [query]. */
    open fun search(
        serviceId: Int,
        query: String,
        source: CandidateSource = CandidateSource.TOPIC
    ): List<Candidate> {
        val service = NewPipe.getService(serviceId)
        val filters = if (serviceId == ServiceList.YouTube.serviceId) listOf("videos") else emptyList()
        val handler = service.searchQHFactory.fromQuery(query, filters, "")
        return SearchInfo.getInfo(service, handler).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .map { it.toCandidate(source, query) }
    }

    /** Loads the streams the service lists next to the stream at [url]. */
    open fun related(serviceId: Int, url: String, seedTitle: String): List<Candidate> {
        return StreamInfo.getInfo(NewPipe.getService(serviceId), url).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .map { it.toCandidate(CandidateSource.RELATED, seedTitle) }
    }

    /**
     * Loads what is currently popular, taking a few items from each chart the service offers so
     * that no single category (e.g. gaming) dominates. Charts that fail to load are skipped;
     * an exception is only thrown if none could be loaded.
     */
    open fun trending(serviceId: Int): List<Candidate> {
        val service = NewPipe.getService(serviceId)
        val kiosks = service.kioskList
        val ids = (listOf(kiosks.defaultKioskId) + kiosks.availableKiosks.sorted())
            .distinct()
            .filter { !it.equals("live", ignoreCase = true) }
            .ifEmpty { listOf(kiosks.defaultKioskId) }

        val charts = ArrayList<List<Candidate>>()
        var lastError: Exception? = null
        for (id in ids) {
            if (charts.size == MAX_CHARTS) {
                break
            }
            try {
                val url = kiosks.getListLinkHandlerFactoryByType(id).fromId(id).url
                val items = KioskInfo.getInfo(service, url).relatedItems
                    .filterIsInstance<StreamInfoItem>()
                    .take(ITEMS_PER_CHART)
                    .map { it.toCandidate(CandidateSource.TRENDING) }
                if (items.isNotEmpty()) {
                    charts.add(items)
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (charts.isEmpty() && lastError != null) {
            throw lastError
        }

        // interleave the charts
        val result = ArrayList<Candidate>()
        for (i in 0 until ITEMS_PER_CHART) {
            charts.forEach { chart -> chart.getOrNull(i)?.let(result::add) }
        }
        return result
    }

    companion object {
        private const val MAX_CHARTS = 3
        private const val ITEMS_PER_CHART = 20
    }
}
