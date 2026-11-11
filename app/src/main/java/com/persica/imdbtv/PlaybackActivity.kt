package com.persica.imdbtv

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class PlaybackActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerView = PlayerView(this).apply {
            useController = true
        }
        setContentView(playerView)

        val movie = intent.getSerializableExtra("movie") as? Movie
        val url = movie?.streamUrl?.ifEmpty { null }
            ?: "https://vidsrc.to/embed/movie/${movie?.id ?: "tt0111161"}"

        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            playerView?.player = exoPlayer
            val mediaItem = MediaItem.fromUri(url)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
