/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.library

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.recommend.data.PersonalStore

/**
 * Downloads films one after the other in the foreground, so that a download survives leaving
 * the app, and shows the progress in a notification. Progress is also published through
 * [FilmDownloads.live] for the user interface.
 */
class FilmDownloadService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger()
    private lateinit var downloads: FilmDownloads
    private lateinit var repository: FilmLibraryRepository
    private val downloader = FilmDownloader(FilmNetwork.client, FilmNetwork.USER_AGENT)

    @Volatile
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        downloads = FilmDownloads.get(this)
        repository = FilmLibraryRepository(
            PersonalStore.get(this),
            InternetArchiveSource(FilmNetwork.http),
            CommonsSource(FilmNetwork.http)
        )
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.film_downloads_channel))
                .build()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val key = intent?.getStringExtra(EXTRA_KEY)
        // a service started as a foreground service has to become one right away, in every case
        foreground(notification(key?.let(downloads::get)))
        if (intent?.action == ACTION_PAUSE && key != null) {
            pause(this, key)
        }
        if (intent?.action == ACTION_START && key != null) {
            pending.incrementAndGet()
            executor.execute {
                try {
                    run(key)
                } finally {
                    if (pending.decrementAndGet() == 0) {
                        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        } else if (pending.get() == 0) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** The system ends data-sync services that ran for too long (Android 15): pause cleanly. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopping = true
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        executor.shutdown()
        super.onDestroy()
    }

    private fun foreground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: IllegalStateException) {
            // the system does not allow it right now (app in the background): the download
            // still runs for as long as the process is kept
            Log.w(TAG, "Could not enter the foreground", e)
        }
    }

    // ------------------------------------------------------------------ download

    private fun cancelled(key: String): Boolean {
        return stopping || downloads.get(key)?.state != DownloadState.RUNNING
    }

    private fun run(key: String) {
        val queued = downloads.get(key)
        if (queued == null || queued.state != DownloadState.QUEUED) {
            return
        }
        var record = downloads.update(key) { it.copy(state = DownloadState.RUNNING, error = null) } ?: return
        foreground(notification(record))
        val wakeLock = getSystemService<PowerManager>()
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pipehome:film-download")
        wakeLock?.acquire(WAKE_LOCK_MILLIS)
        try {
            // the audit is repeated at the source right before downloading
            val film = repository.recheck(record.film)
            val file = film?.files?.firstOrNull { it.url == record.file.url }
            if (film == null || file == null) {
                fail(key, getString(R.string.film_error_withdrawn))
                return
            }
            record = downloads.update(key) { it.copy(film = film, file = file) } ?: return
            val target = File(record.path)
            val needed = file.sizeBytes - FilmDownloader.partFile(target).length()
            val free = (target.parentFile ?: target).let { if (it.exists() || it.mkdirs()) it.usableSpace else 0L }
            if (file.sizeBytes > 0 && free < needed + SPACE_RESERVE_BYTES) {
                fail(key, getString(R.string.film_error_space))
                return
            }
            transfer(key, file, target)
        } catch (e: FilmDownloader.Cancelled) {
            downloads.update(key) { if (it.state == DownloadState.RUNNING) it.copy(state = DownloadState.PAUSED) else it }
        } catch (e: FilmDownloader.Corrupt) {
            fail(key, getString(R.string.film_error_checksum))
        } catch (e: IOException) {
            fail(key, getString(R.string.film_error_network, e.message.orEmpty()))
        } catch (e: SecurityException) {
            fail(key, e.message ?: e.javaClass.simpleName)
        } finally {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        }
    }

    /** Downloads with a few automatic retries; every retry continues the partial file. */
    @Throws(IOException::class)
    private fun transfer(key: String, file: FilmFile, target: File) {
        var attempt = 0
        var lastNotified = -1
        while (true) {
            try {
                val size = downloader.download(file.url, target, file.sha1, { cancelled(key) }) { done, total ->
                    val known = if (total > 0) total else file.sizeBytes.takeIf { !file.sizeEstimated } ?: -1
                    val record = downloads.update(key, persist = false) {
                        it.copy(bytesDone = done, bytesTotal = known)
                    }
                    if (record != null && record.percent != lastNotified) {
                        lastNotified = record.percent
                        notify(NOTIFICATION_ID, notification(record))
                    }
                }
                val done = downloads.update(key) {
                    it.copy(
                        state = DownloadState.DONE,
                        bytesDone = size,
                        bytesTotal = size,
                        verified = file.sha1 != null,
                        error = null
                    )
                }
                if (done == null) {
                    // removed by the user in the meantime
                    target.delete()
                } else {
                    notify(key.hashCode(), finishedNotification(done))
                }
                return
            } catch (e: FilmDownloader.Cancelled) {
                throw e
            } catch (e: FilmDownloader.Corrupt) {
                throw e
            } catch (e: IOException) {
                if (++attempt > MAX_RETRIES || cancelled(key)) {
                    throw e
                }
                try {
                    Thread.sleep(RETRY_DELAY_MILLIS * attempt)
                } catch (interrupted: InterruptedException) {
                    throw FilmDownloader.Cancelled()
                }
            }
        }
    }

    private fun fail(key: String, message: String) {
        downloads.update(key) { it.copy(state = DownloadState.FAILED, error = message) }
    }

    // -------------------------------------------------------------- notifications

    private fun notify(id: Int, notification: Notification) {
        // without the notification permission this is silently ignored by the system
        getSystemService<NotificationManager>()?.notify(id, notification)
    }

    private fun openApp(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notification(record: FilmDownload?): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(record?.film?.title ?: getString(R.string.film_downloads_channel))
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (record != null) {
            val percent = record.percent
            builder.setContentText(
                if (percent >= 0) {
                    getString(R.string.film_download_progress, percent, record.file.label)
                } else {
                    record.file.label
                }
            )
            builder.setProgress(100, percent.coerceAtLeast(0), percent < 0)
            val pause = PendingIntent.getService(
                this,
                record.film.key.hashCode(),
                Intent(this, FilmDownloadService::class.java).setAction(ACTION_PAUSE)
                    .putExtra(EXTRA_KEY, record.film.key),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, getString(R.string.pause), pause)
        }
        return builder.build()
    }

    private fun finishedNotification(record: FilmDownload): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(record.film.title)
            .setContentText(getString(R.string.film_download_finished))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
    }

    companion object {
        private const val TAG = "FilmDownloadService"
        private const val CHANNEL_ID = "pipehomeFilmDownloads"
        private const val NOTIFICATION_ID = 7301
        private const val ACTION_START = "org.schabi.newpipe.movies.library.START"
        private const val ACTION_PAUSE = "org.schabi.newpipe.movies.library.PAUSE"
        private const val EXTRA_KEY = "key"
        private const val MAX_RETRIES = 4
        private const val RETRY_DELAY_MILLIS = 3000L
        private const val WAKE_LOCK_MILLIS = 6 * 60 * 60 * 1000L
        private const val SPACE_RESERVE_BYTES = 100L * 1024 * 1024

        /** Queues the download of [file] and starts the service. */
        fun start(context: Context, film: Film, file: FilmFile) {
            FilmDownloads.get(context).enqueue(film, file)
            val intent = Intent(context, FilmDownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_KEY, film.key)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stops a queued or running download; what was downloaded so far is kept. */
        fun pause(context: Context, key: String) {
            FilmDownloads.get(context).update(key) {
                if (it.active) it.copy(state = DownloadState.PAUSED) else it
            }
        }
    }
}
