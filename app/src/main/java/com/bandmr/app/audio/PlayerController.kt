package com.bandmr.app.audio

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.bandmr.app.data.Song
import com.bandmr.app.data.Stem
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * AI OFF: ExoPlayer + MrAudioProcessor(실시간 DSP) / AI ON: StemMixPlayer(스템 믹서).
 * 모드 전환 시 재생 위치를 유지한다.
 */
class PlayerController(private val context: Context) {

    private var exo: ExoPlayer? = null
    private var mixer: StemMixPlayer? = null

    @Volatile
    private var aiMode = false

    private var currentSong: Song? = null

    val isPlaying = MutableStateFlow(false)
    val durationMs = MutableStateFlow(0L)

    private var pendingSeekMs = -1L
    private var wasAutoEnded = false

    // ---------- 로딩 ----------

    fun ensureLoaded(song: Song, aiOn: Boolean, muteMask: Int, semitones: Int) {
        DspBus.muteMask = muteMask
        val modeChanged = aiOn != aiMode || currentSong?.id != song.id
        val separated = song.separatedTier != null && song.stemsDir != null
        if (!modeChanged && !needsReload(song, aiOn)) {
            applyParams(muteMask, semitones)
            return
        }
        val wasPlaying = activeIsPlaying()
        val pos = activePositionMs()

        releaseEngines()
        aiMode = aiOn && separated
        currentSong = song

        if (aiMode) {
            val dir = File(song.stemsDir!!)
            val files = buildMap {
                Stem.entries.forEach { stem ->
                    val f = File(dir, "${stem.fileName}.wav")
                    if (f.exists()) put(stem, f)
                }
            }
            mixer = StemMixPlayer(onEndedCallback = {
                wasAutoEnded = true
                isPlaying.value = false
            }).also {
                it.load(files)
                it.semitones = semitones
                it.gains = gainArray(muteMask)
                durationMs.value = framesToMs(it.durationFrames)
                val seekTo = if (pendingSeekMs >= 0) pendingSeekMs else pos
                it.seekToFrame(msToFrames(seekTo))
                if (wasPlaying && !wasAutoEnded) it.play()
            }
        } else {
            val player = buildExo()
            exo = player
            player.setMediaItem(MediaItem.fromUri(android.net.Uri.parse(song.uri)))
            player.prepare()
            val seekTo = if (pendingSeekMs >= 0) pendingSeekMs else pos
            if (seekTo > 0) player.seekTo(seekTo)
            player.playbackParameters = PlaybackParameters(1f, pitchRatio(semitones))
            if (wasPlaying && !wasAutoEnded) player.play()
            durationMs.value = song.durationMs
        }
        pendingSeekMs = -1L
        wasAutoEnded = false
    }

    private fun needsReload(song: Song, aiOn: Boolean): Boolean {
        val separated = song.separatedTier != null && song.stemsDir != null
        return aiOn && separated && mixer == null
    }

    private fun buildExo(): ExoPlayer {
        val processor = MrAudioProcessor()
        val factory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(processor))
                    .build()
        }
        return ExoPlayer.Builder(context, factory).build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                    this@PlayerController.isPlaying.value = isPlayingNow
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        this@PlayerController.isPlaying.value = false
                        wasAutoEnded = true
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    this@PlayerController.isPlaying.value = false
                }
            })
        }
    }

    // ---------- 컨트롤 ----------

    fun playPause() {
        if (aiMode) mixer?.let {
            if (it.isPlaying) it.pause() else it.play()
            isPlaying.value = it.isPlaying
        } else exo?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    fun seekTo(ms: Long) {
        if (aiMode) mixer?.seekToFrame(msToFrames(ms))
        else exo?.seekTo(ms)
    }

    fun setMuteMask(mask: Int) {
        DspBus.muteMask = mask
        mixer?.gains = gainArray(mask)
    }

    fun setSemitones(n: Int) {
        if (aiMode) mixer?.semitones = n
        else exo?.playbackParameters = PlaybackParameters(1f, pitchRatio(n))
    }

    fun positionMs(): Long =
        if (aiMode) framesToMs(mixer?.positionFrames() ?: 0L)
        else exo?.currentPosition ?: 0L

    fun release() {
        releaseEngines()
        currentSong = null
        isPlaying.value = false
        durationMs.value = 0L
    }

    /** 화면 전환 시 위치 보존용 */
    fun stashPositionForReload() {
        pendingSeekMs = activePositionMs()
    }

    // ---------- 유틸 ----------

    private fun releaseEngines() {
        exo?.release(); exo = null
        mixer?.release(); mixer = null
    }

    private fun activeIsPlaying(): Boolean =
        if (aiMode) mixer?.isPlaying == true else exo?.isPlaying == true

    private fun activePositionMs(): Long = positionMs()

    private fun applyParams(muteMask: Int, semitones: Int) {
        setMuteMask(muteMask)
        setSemitones(semitones)
    }

    private fun gainArray(mask: Int): FloatArray =
        FloatArray(Stem.entries.size) { i -> if (mask and Stem.entries[i].bit != 0) 0f else 1f }

    private fun msToFrames(ms: Long): Long = ms * sampleRateCompat() / 1000

    private fun framesToMs(frames: Long): Long =
        if (frames <= 0) 0 else frames * 1000 / sampleRateCompat()

    private fun sampleRateCompat(): Long = 44100L

    companion object {
        fun pitchRatio(semitones: Int): Float = Math.pow(2.0, semitones / 12.0).toFloat()
    }
}
