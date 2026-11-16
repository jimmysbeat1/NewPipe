/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.library

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Random
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Downloads from a local HTTP server that behaves like the file servers of the sources. */
class FilmDownloaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val content = ByteArray(700_000).also { Random(7).nextBytes(it) }
    private val sha1 = MessageDigest.getInstance("SHA-1").digest(content).joinToString("") { "%02x".format(it) }
    private val downloader = FilmDownloader(OkHttpClient(), "test-agent")
    private lateinit var server: ServerSocket
    private lateinit var target: File
    private val requests = ArrayList<String>()

    /** How the server treats range requests. */
    private var ranges = true
    private var failAfterBytes = -1
    private var status = 200

    @Before
    fun setUp() {
        target = File(folder.root, "films/film.mp4")
        server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            try {
                while (true) {
                    server.accept().use(::serve)
                }
            } catch (e: IOException) {
                // the server was closed
            }
        }
    }

    @After
    fun tearDown() {
        server.close()
    }

    /** A minimal HTTP/1.1 file server: one request per connection. */
    private fun serve(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val headers = HashMap<String, String>()
        reader.readLine() ?: return
        while (true) {
            val line = reader.readLine()
            if (line.isNullOrEmpty()) {
                break
            }
            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        val range = headers["range"]
        synchronized(requests) { requests.add(range ?: "full") }
        val output = socket.getOutputStream()
        val start = if (ranges && range != null) range.removePrefix("bytes=").removeSuffix("-").toInt() else 0
        val head = StringBuilder()
        var body = ByteArray(0)
        when {
            headers["user-agent"] != "test-agent" -> head.append("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n")

            status != 200 -> head.append("HTTP/1.1 $status Error\r\nContent-Length: 0\r\n")

            start >= content.size -> {
                head.append("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\n")
                head.append("Content-Range: bytes */${content.size}\r\n")
            }

            else -> {
                head.append(if (start > 0) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                head.append("Content-Length: ${content.size - start}\r\n")
                if (start > 0) {
                    head.append("Content-Range: bytes $start-${content.size - 1}/${content.size}\r\n")
                }
                val end = if (failAfterBytes >= 0) minOf(content.size, start + failAfterBytes) else content.size
                body = content.copyOfRange(start, end)
            }
        }
        head.append("Connection: close\r\n\r\n")
        try {
            output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            output.write(body)
            output.flush()
        } catch (e: IOException) {
            // the client went away
        }
    }

    private val url: String
        get() = "http://127.0.0.1:" + server.localPort + "/film.mp4"

    private fun download(expected: String? = sha1, cancelled: () -> Boolean = { false }): Long {
        return downloader.download(url, target, expected, cancelled) { _, _ -> }
    }

    @Test
    fun `downloads, verifies the checksum and reports progress`() {
        val progress = ArrayList<Pair<Long, Long>>()
        val size = downloader.download(url, target, sha1, { false }) { done, total -> progress.add(done to total) }
        assertEquals(content.size.toLong(), size)
        assertArrayEquals(content, target.readBytes())
        assertFalse(FilmDownloader.partFile(target).exists())
        assertEquals(content.size.toLong() to content.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a.first <= b.first })
        assertEquals(listOf("full"), requests)
    }

    @Test
    fun `resumes an interrupted download where it stopped`() {
        failAfterBytes = 250_000
        assertThrows(IOException::class.java) { download() }
        val part = FilmDownloader.partFile(target)
        assertFalse(target.exists())
        val received = part.length()
        assertTrue("received $received", received in 1..250_000)

        failAfterBytes = -1
        assertEquals(content.size.toLong(), download())
        assertArrayEquals(content, target.readBytes())
        assertEquals(listOf("full", "bytes=$received-"), requests)
        assertEquals(received, downloader.resumedFrom)
    }

    @Test
    fun `a cancelled download keeps its partial file and can be continued`() {
        var polls = 0
        assertThrows(FilmDownloader.Cancelled::class.java) { download(cancelled = { ++polls > 3 }) }
        val part = FilmDownloader.partFile(target)
        val received = part.length()
        assertTrue("received $received", received in 1 until content.size)
        assertFalse(target.exists())

        download()
        assertArrayEquals(content, target.readBytes())
        assertEquals(listOf("full", "bytes=$received-"), requests)
    }

    @Test
    fun `starts over when the server does not support ranges`() {
        ranges = false
        FilmDownloader.partFile(target).apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(1000))
        download()
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun `finishes a partial file that is already complete`() {
        FilmDownloader.partFile(target).apply { parentFile!!.mkdirs() }.writeBytes(content)
        assertEquals(content.size.toLong(), download())
        assertArrayEquals(content, target.readBytes())
        assertEquals(listOf("bytes=700000-"), requests)
    }

    @Test
    fun `discards a partial file that does not belong to the download`() {
        // larger than the real file: the server answers 416 with a different size
        FilmDownloader.partFile(target).apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(900_000))
        download()
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun `rejects data that does not match the published checksum`() {
        assertThrows(FilmDownloader.Corrupt::class.java) { download(expected = "0".repeat(40)) }
        assertFalse(target.exists())
        assertFalse(FilmDownloader.partFile(target).exists())
    }

    @Test
    fun `downloads without a checksum when the source publishes none`() {
        download(expected = null)
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun `fails on http errors without leaving a file behind`() {
        status = 404
        val error = assertThrows(IOException::class.java) { download() }
        assertEquals("HTTP 404", error.message)
        assertFalse(target.exists())
    }
}
