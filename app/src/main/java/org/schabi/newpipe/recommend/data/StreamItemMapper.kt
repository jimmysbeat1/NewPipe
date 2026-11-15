/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.recommend.data

import java.time.Instant
import java.time.ZoneOffset
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.recommend.engine.Candidate
import org.schabi.newpipe.recommend.engine.CandidateSource
import org.schabi.newpipe.util.image.ImageStrategy

fun StreamInfoItem.toCandidate(source: CandidateSource, seed: String? = null): Candidate {
    return Candidate(
        serviceId = serviceId,
        url = url,
        title = name.orEmpty(),
        uploader = uploaderName.orEmpty(),
        uploaderUrl = uploaderUrl,
        durationSeconds = duration,
        viewCount = viewCount,
        uploadEpochMillis = uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli(),
        thumbnailUrl = ImageStrategy.imageListToDbUrl(thumbnails),
        streamType = streamType.name,
        source = source,
        seed = seed,
        isShort = isShortFormContent
    )
}

fun Candidate.toStreamInfoItem(): StreamInfoItem {
    val type = runCatching { StreamType.valueOf(streamType) }.getOrDefault(StreamType.VIDEO_STREAM)
    val item = StreamInfoItem(serviceId, url, title, type)
    item.duration = durationSeconds
    item.uploaderName = uploader
    item.uploaderUrl = uploaderUrl
    item.viewCount = viewCount
    item.thumbnails = ImageStrategy.dbUrlToImageList(thumbnailUrl)
    item.isShortFormContent = isShort
    uploadEpochMillis?.let {
        item.uploadDate = DateWrapper(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC), true)
    }
    return item
}
