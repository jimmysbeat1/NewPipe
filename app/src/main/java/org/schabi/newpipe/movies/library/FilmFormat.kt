/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.library

import android.content.Context
import android.text.format.Formatter
import org.schabi.newpipe.R

/** Turns film data into the texts the user interface shows. */
object FilmFormat {
    /** E.g. "1926 • 1 h 18 min". */
    fun details(context: Context, film: Film): String {
        val parts = ArrayList<String>(2)
        film.year?.let { parts.add(it.toString()) }
        val hours = film.durationSeconds / 3600
        val minutes = film.durationSeconds % 3600 / 60
        parts.add(
            if (hours > 0) {
                context.getString(R.string.movies_duration_hours, hours, minutes)
            } else {
                context.getString(R.string.movies_duration_minutes, minutes)
            }
        )
        return parts.joinToString(" • ")
    }

    /** E.g. "Internet Archive • Public domain". */
    fun origin(film: Film): String = film.source.displayName + " • " + film.licence.name

    /** E.g. "480p • MP4 • 310 MB", with "about" in front of sizes that are estimates. */
    fun quality(context: Context, file: FilmFile): String {
        val size = when {
            file.sizeBytes <= 0 -> context.getString(R.string.film_size_unknown)
            file.sizeEstimated -> context.getString(R.string.film_size_estimated, bytes(context, file.sizeBytes))
            else -> bytes(context, file.sizeBytes)
        }
        return file.label + " • " + file.container + " • " + size
    }

    fun bytes(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    /** The short state shown on a poster and in the details, or `null` without a download. */
    fun status(context: Context, download: FilmDownload?): String? {
        val record = download ?: return null
        return when (record.state) {
            DownloadState.DONE -> context.getString(R.string.film_state_downloaded)
            DownloadState.QUEUED -> context.getString(R.string.film_state_queued)
            DownloadState.PAUSED -> context.getString(R.string.film_state_paused)
            DownloadState.FAILED -> context.getString(R.string.film_state_failed)
            DownloadState.RUNNING -> if (record.percent >= 0) "${record.percent}%" else "…"
        }
    }
}
