/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.library

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads one file with support for resuming: the data goes to `<target>.part`, an interrupted
 * download continues from where it stopped (HTTP range request), and the file only gets its
 * final name once it is complete and, where the source publishes a SHA-1 checksum, verified.
 *
 * Has no Android dependencies. [download] blocks.
 */
class FilmDownloader(private val client: OkHttpClient, private val userAgent: String) {
    fun interface Progress {
        /** @param total the size of the complete file, or -1 if the server does not say */
        fun update(done: Long, total: Long)
    }

    /**
     * Where the last call of [download] continued an existing partial file, in bytes; 0 if it
     * started from the beginning. For diagnostics and tests.
     */
    @Volatile
    var resumedFrom = 0L
        private set

    /** The download was stopped on request. The partial file is kept for resuming. */
    class Cancelled : IOException("Download cancelled")

    /** The downloaded data does not match the checksum of the source. */
    class Corrupt : IOException("Checksum mismatch")

    /**
     * @param cancelled polled regularly; return `true` to stop
     * @return the size of the downloaded file
     */
    @Throws(IOException::class)
    fun download(
        url: String,
        target: File,
        expectedSha1: String?,
        cancelled: () -> Boolean,
        progress: Progress
    ): Long {
        val part = partFile(target)
        resumedFrom = 0
        val directory = target.absoluteFile.parentFile
        if (directory != null && !directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Could not create $directory")
        }
        if (!attempt(url, part, cancelled, progress)) {
            // the server would not continue the partial file: start over, once
            if (!part.delete() && part.exists()) {
                throw IOException("Could not delete $part")
            }
            if (!attempt(url, part, cancelled, progress)) {
                throw IOException("The server does not deliver the file")
            }
        }
        if (expectedSha1 != null && !sha1(part, cancelled).equals(expectedSha1, ignoreCase = true)) {
            part.delete()
            throw Corrupt()
        }
        if (target.exists() && !target.delete()) {
            throw IOException("Could not replace $target")
        }
        if (!part.renameTo(target)) {
            throw IOException("Could not move the download to $target")
        }
        return target.length()
    }

    /** @return `false` if the partial file cannot be continued and has to be discarded */
    private fun attempt(url: String, part: File, cancelled: () -> Boolean, progress: Progress): Boolean {
        val existing = if (part.isFile) part.length() else 0L
        val request = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            // the stored bytes must be the bytes of the file, not a compressed transfer of them
            .header("Accept-Encoding", "identity")
        if (existing > 0) {
            request.header("Range", "bytes=$existing-")
        }
        client.newCall(request.build()).execute().use { response ->
            val range = RANGE.find(response.header("Content-Range").orEmpty())
            val total: Long
            val append: Boolean
            when {
                response.code == HTTP_PARTIAL && existing > 0 -> {
                    if (range == null || range.groupValues[1].toLongOrNull() != existing) {
                        return false
                    }
                    total = range.groupValues[2].toLongOrNull() ?: -1
                    append = true
                    resumedFrom = existing
                }

                response.code == HTTP_RANGE_NOT_SATISFIABLE && existing > 0 -> {
                    // nothing left to fetch if the partial file already has the full size
                    val size = UNSATISFIED.find(response.header("Content-Range").orEmpty())
                        ?.groupValues?.get(1)?.toLongOrNull()
                    if (size == existing) {
                        progress.update(existing, existing)
                        return true
                    }
                    return false
                }

                response.code == HTTP_OK -> {
                    total = response.body.contentLength()
                    append = false
                }

                else -> throw IOException("HTTP " + response.code)
            }

            var done = if (append) existing else 0L
            var reported = 0L
            FileOutputStream(part, append).use { output ->
                val input = response.body.byteStream()
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    if (cancelled()) {
                        throw Cancelled()
                    }
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    output.write(buffer, 0, read)
                    done += read
                    val now = System.nanoTime()
                    if (now - reported >= PROGRESS_INTERVAL_NANOS) {
                        reported = now
                        progress.update(done, total)
                    }
                }
            }
            if (total >= 0 && total != done) {
                throw IOException("The connection was closed after $done of $total bytes")
            }
            progress.update(done, done)
            return true
        }
    }

    companion object {
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL = 206
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_INTERVAL_NANOS = 400_000_000L
        private val RANGE = Regex("^bytes (\\d+)-\\d+/(\\d+|\\*)$")
        private val UNSATISFIED = Regex("^bytes \\*/(\\d+)$")

        fun partFile(target: File): File = File(target.path + ".part")

        @Throws(IOException::class)
        fun sha1(file: File, cancelled: () -> Boolean = { false }): String {
            val digest = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    if (cancelled()) {
                        throw Cancelled()
                    }
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
