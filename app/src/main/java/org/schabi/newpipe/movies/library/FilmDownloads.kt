/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.library

import android.content.Context
import android.os.Environment
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.schabi.newpipe.recommend.data.PersonalStore

enum class DownloadState { QUEUED, RUNNING, PAUSED, FAILED, DONE }

/**
 * A film that was, is being or is about to be downloaded.
 *
 * @param path where the finished file is stored
 * @param verified whether the file was checked against the checksum published by the source
 */
@Serializable
data class FilmDownload(
    val film: Film,
    val file: FilmFile,
    val path: String,
    val state: DownloadState,
    val bytesDone: Long = 0,
    val bytesTotal: Long = -1,
    val error: String? = null,
    val verified: Boolean = false
) {
    /** 0 to 100, or -1 while the size is not known. */
    val percent: Int
        get() = if (bytesTotal > 0) (bytesDone * 100 / bytesTotal).toInt().coerceIn(0, 100) else -1

    val active: Boolean
        get() = state == DownloadState.QUEUED || state == DownloadState.RUNNING
}

/**
 * The downloaded films and the progress of running downloads, kept across restarts. The files
 * live in the app's own storage area, so no storage permission is needed and nothing outside
 * the app is touched.
 */
class FilmDownloads(private val store: PersonalStore, private val directory: () -> File) {
    private val serializer = ListSerializer(FilmDownload.serializer())
    private val records = LinkedHashMap<String, FilmDownload>()
    private val mutableLive = MutableLiveData<Map<String, FilmDownload>>(emptyMap())

    /** The downloads by [Film.key]. Updated on every change, including progress. */
    val live: LiveData<Map<String, FilmDownload>> = mutableLive

    init {
        for (record in store.read(NAME, serializer, emptyList())) {
            // nothing is running when the app starts: what was in flight can be resumed
            records[record.film.key] = if (record.active) record.copy(state = DownloadState.PAUSED) else record
        }
        mutableLive.postValue(LinkedHashMap(records))
    }

    @Synchronized
    fun all(): Map<String, FilmDownload> = LinkedHashMap(records)

    @Synchronized
    fun get(key: String): FilmDownload? = records[key]

    /** Registers a download, or queues an existing unfinished one again. */
    @Synchronized
    fun enqueue(film: Film, file: FilmFile): FilmDownload {
        val existing = records[film.key]
        val record = if (existing != null && existing.file.url == file.url && existing.state != DownloadState.DONE) {
            existing.copy(state = DownloadState.QUEUED, error = null)
        } else {
            existing?.let(::deleteFiles)
            FilmDownload(film, file, File(directory(), fileName(film, file)).path, DownloadState.QUEUED)
        }
        records[film.key] = record
        publish(true)
        return record
    }

    /**
     * @param persist `false` for progress updates, which are frequent and not worth a disk write
     */
    @Synchronized
    fun update(key: String, persist: Boolean = true, change: (FilmDownload) -> FilmDownload): FilmDownload? {
        val record = records[key] ?: return null
        val changed = change(record)
        records[key] = changed
        publish(persist)
        return changed
    }

    /** Forgets a download and deletes its file. */
    @Synchronized
    fun remove(key: String) {
        records.remove(key)?.let(::deleteFiles)
        publish(true)
    }

    private fun deleteFiles(record: FilmDownload) {
        val file = File(record.path)
        file.delete()
        FilmDownloader.partFile(file).delete()
    }

    private fun publish(persist: Boolean) {
        mutableLive.postValue(LinkedHashMap(records))
        if (persist) {
            store.write(NAME, serializer, records.values.toList())
        }
    }

    companion object {
        private const val NAME = "film_downloads"
        private const val MAX_NAME_LENGTH = 80

        @Volatile
        private var instance: FilmDownloads? = null

        fun get(context: Context): FilmDownloads {
            return instance ?: synchronized(this) {
                val application = context.applicationContext
                instance ?: FilmDownloads(PersonalStore.get(application)) {
                    application.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                        ?: File(application.filesDir, "films")
                }.also { instance = it }
            }
        }

        /** A file name that is safe on every file system and unique per film. */
        fun fileName(film: Film, file: FilmFile): String {
            val title = film.title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().take(MAX_NAME_LENGTH)
            val year = film.year?.let { " ($it)" }.orEmpty()
            val id = abs(film.key.hashCode()).toString(36)
            val extension = file.fileName.substringAfterLast('.', file.container).lowercase(Locale.ROOT)
            return "$title$year [${file.height}p] $id.$extension"
        }
    }
}
