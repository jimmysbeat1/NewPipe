/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import java.io.File
import org.schabi.newpipe.R

/**
 * Plays a film of the library: the downloaded file when there is one (works offline), the
 * stream from the source otherwise. Remembers where playback stopped.
 *
 * This is a small player of its own on purpose. NewPipe's player is built around the video
 * services of NewPipe Extractor and is left untouched.
 */
class FilmPlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: StyledPlayerView
    private lateinit var errorView: TextView
    private lateinit var key: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_film_player)
        playerView = findViewById(R.id.filmPlayerView)
        errorView = findViewById(R.id.filmPlayerError)
        key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        errorView.setOnClickListener { start() }
    }

    override fun onStart() {
        super.onStart()
        start()
    }

    override fun onStop() {
        release()
        super.onStop()
    }

    private fun start() {
        release()
        val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        if (uri == null) {
            showError(getString(R.string.film_player_error, ""))
            return
        }
        errorView.isVisible = false
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(FilmNetwork.USER_AGENT)
            .setAllowCrossProtocolRedirects(false)
        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setHandleAudioBecomingNoisy(true)
            .build()
        val audio = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        exoPlayer.setAudioAttributes(audio, true)
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                showError(getString(R.string.film_player_error, error.errorCodeName))
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    positions().edit { remove(key) }
                }
            }
        })
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        val position = positions().getLong(key, 0)
        if (position > 0) {
            exoPlayer.seekTo(position)
        }
        exoPlayer.playWhenReady = true
        exoPlayer.prepare()
        playerView.player = exoPlayer
        player = exoPlayer
    }

    private fun release() {
        val exoPlayer = player ?: return
        val position = exoPlayer.currentPosition
        val duration = exoPlayer.duration
        if (exoPlayer.playbackState != Player.STATE_ENDED && position > MIN_RESUME_MILLIS) {
            // close to the end counts as finished
            if (duration == C.TIME_UNSET || position < duration - END_MARGIN_MILLIS) {
                positions().edit { putLong(key, position) }
            } else {
                positions().edit { remove(key) }
            }
        }
        playerView.player = null
        exoPlayer.release()
        player = null
    }

    private fun showError(message: String) {
        errorView.text = message
        errorView.visibility = View.VISIBLE
    }

    private fun positions() = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    companion object {
        private const val EXTRA_URI = "uri"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_TITLE = "title"
        private const val PREFERENCES = "film_positions"
        private const val MIN_RESUME_MILLIS = 30_000L
        private const val END_MARGIN_MILLIS = 60_000L

        /** Plays the downloaded file of [download], which works without a connection. */
        fun offline(context: Context, download: FilmDownload): Intent {
            return intent(context, download.film, Uri.fromFile(File(download.path)))
        }

        /** Streams [file] from its source. */
        fun stream(context: Context, film: Film, file: FilmFile): Intent {
            return intent(context, film, Uri.parse(file.url))
        }

        private fun intent(context: Context, film: Film, uri: Uri): Intent {
            return Intent(context, FilmPlayerActivity::class.java)
                .putExtra(EXTRA_URI, uri.toString())
                .putExtra(EXTRA_KEY, film.key)
                .putExtra(EXTRA_TITLE, film.title)
        }
    }
}
