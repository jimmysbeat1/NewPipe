/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.library

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import java.io.File
import kotlinx.serialization.json.Json
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.DialogFilmDetailBinding
import org.schabi.newpipe.util.external_communication.ShareUtils

/**
 * Everything about one film: what it is, where it comes from and under which licence, and the
 * controls to stream it, download it in a chosen quality, follow the download and play or
 * delete the downloaded file.
 */
class FilmDetailDialog : DialogFragment() {
    private var binding: DialogFilmDetailBinding? = null
    private lateinit var film: Film
    private lateinit var downloads: FilmDownloads

    // asked for when a download starts, so that its progress notification can be shown;
    // the download does not depend on the answer
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        film = JSON.decodeFromString(Film.serializer(), requireArguments().getString(ARGUMENT_FILM).orEmpty())
        downloads = FilmDownloads.get(requireContext())
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogFilmDetailBinding.inflate(requireActivity().layoutInflater).also { this.binding = it }
        val context = requireContext()
        with(binding) {
            filmTitle.text = film.title
            filmDetails.text = FilmFormat.details(context, film)
            filmCreator.text = film.creator
            filmCreator.isVisible = !film.creator.isNullOrBlank()
            filmDescription.text = film.description
            filmDescription.isVisible = !film.description.isNullOrBlank()
            filmPoster.clipToOutline = true
            FilmPosters.load(filmPoster, film.posterUrl)

            filmLicence.text = getString(R.string.film_licence, film.licence.name)
            val licenceUrl = film.licence.url
            filmLicence.isEnabled = licenceUrl != null
            filmLicence.setOnClickListener { licenceUrl?.let { ShareUtils.openUrlInBrowser(context, it) } }
            filmSource.text = getString(R.string.film_view_source, film.source.displayName)
            filmSource.setOnClickListener { ShareUtils.openUrlInBrowser(context, film.pageUrl) }

            filmPlay.setOnClickListener { play() }
            filmDownload.setOnClickListener { downloadOrPause() }
            filmDelete.setOnClickListener { confirmDelete() }
        }
        // observed with the fragment as owner: a dialog fragment has no view lifecycle here
        downloads.live.observe(this) { render(it[film.key]) }
        return AlertDialog.Builder(context)
            .setView(binding.root)
            .setNegativeButton(R.string.film_close, null)
            .create()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun render(download: FilmDownload?) {
        val binding = binding ?: return
        val state = download?.state
        with(binding) {
            filmPlay.setText(if (state == DownloadState.DONE) R.string.film_play_offline else R.string.film_stream)
            filmDownload.isVisible = state != DownloadState.DONE
            filmDownload.setText(
                when (state) {
                    DownloadState.QUEUED, DownloadState.RUNNING -> R.string.pause
                    DownloadState.PAUSED -> R.string.film_resume
                    DownloadState.FAILED -> R.string.retry
                    else -> R.string.download
                }
            )
            filmDelete.isVisible = download != null

            filmProgress.isVisible = download != null && state != DownloadState.DONE
            if (download != null) {
                filmProgress.isIndeterminate = download.percent < 0 && download.active
                filmProgress.progress = download.percent.coerceAtLeast(0)
            }
            filmStatus.isVisible = download != null
            filmStatus.text = when {
                download == null -> null
                state == DownloadState.FAILED -> download.error ?: getString(R.string.film_state_failed)
                state == DownloadState.DONE -> doneText(download)
                else -> progressText(download)
            }
        }
    }

    private fun doneText(download: FilmDownload): String {
        return getString(
            if (download.verified) R.string.film_done_verified else R.string.film_done,
            download.file.label,
            FilmFormat.bytes(requireContext(), download.bytesDone)
        )
    }

    private fun progressText(download: FilmDownload): String {
        val context = requireContext()
        val status = FilmFormat.status(context, download).orEmpty()
        val done = FilmFormat.bytes(context, download.bytesDone)
        return if (download.bytesTotal > 0) {
            getString(
                R.string.film_progress_detail,
                status,
                done,
                FilmFormat.bytes(context, download.bytesTotal),
                download.file.label
            )
        } else {
            status + " • " + done + " • " + download.file.label
        }
    }

    // ------------------------------------------------------------------- actions

    private fun play() {
        val download = downloads.get(film.key)
        val context = requireContext()
        if (download?.state == DownloadState.DONE && File(download.path).isFile) {
            startActivity(FilmPlayerActivity.offline(context, download))
            return
        }
        // streaming: the best rendition up to 720p, which starts quickly on any connection
        val file = film.files.filter { it.height in 1..STREAM_MAX_HEIGHT }.maxByOrNull { it.height }
            ?: film.files.minByOrNull { if (it.height > 0) it.height else Int.MAX_VALUE }
            ?: return
        startActivity(FilmPlayerActivity.stream(context, film, file))
    }

    private fun downloadOrPause() {
        val download = downloads.get(film.key)
        when {
            download == null -> chooseQuality()
            download.active -> FilmDownloadService.pause(requireContext(), film.key)
            else -> start(download.file)
        }
    }

    private fun chooseQuality() {
        val context = requireContext()
        val labels = film.files.map { FilmFormat.quality(context, it) }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.film_choose_quality)
            .setItems(labels) { _, which -> confirm(film.files[which]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Asks before a large download is started on a connection that may cost money. */
    private fun confirm(file: FilmFile) {
        val context = requireContext()
        val metered = context.getSystemService<ConnectivityManager>()?.isActiveNetworkMetered == true
        if (!metered) {
            start(file)
            return
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.film_metered_title)
            .setMessage(getString(R.string.film_metered_message, FilmFormat.quality(context, file)))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.download) { _, _ -> start(file) }
            .show()
    }

    private fun start(file: FilmFile) {
        val context = requireContext()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        FilmDownloadService.start(context, film, file)
    }

    private fun confirmDelete() {
        AlertDialog.Builder(requireContext())
            .setTitle(film.title)
            .setMessage(R.string.film_delete_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> downloads.remove(film.key) }
            .show()
    }

    companion object {
        private const val ARGUMENT_FILM = "film"
        private const val STREAM_MAX_HEIGHT = 720
        private val JSON = Json { ignoreUnknownKeys = true }

        fun newInstance(film: Film): FilmDetailDialog {
            return FilmDetailDialog().apply {
                arguments = bundleOf(ARGUMENT_FILM to JSON.encodeToString(Film.serializer(), film))
            }
        }
    }
}
