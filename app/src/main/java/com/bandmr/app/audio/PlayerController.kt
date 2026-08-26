package com.bandmr.app.audio

import android.content.Context
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.content.BroadcastReceiver
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
 * 오디오 포커스 요청과 이어폰 분리(BECOMING_NOISY) 시 일시정지를 처리한다.
 */
class PlayerController(private val context: Context) {

    private var exo: ExoPlayer? = null
    private var mixer: StemMixPlayer? = null

    @Volatile
    private var aiMode = false

    private var currentSong: Song? = null

    val isPlaying = MutableStateFlow(false)
    val durationMs = MutableStateFlow(0L)

    /** 알림 등에 표시할 현재 곡 제목 */
    val nowPlayingTitle = MutableStateFlow<String?>(null)

    private var wasAutoEnded = false

    // ---------- 오디오 포커스 / 이어폰 분리 ----------

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var noisyReceiver: BroadcastReceiver? = null

    private fun requestFocus(): Boolean {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .build()
            .also { focusRequest = it }
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    private fun registerNoisyReceiver() {
        if (noisyReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    pauseAll()
                }
            }
        }
        noisyReceiver = receiver
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun unregisterNoisyReceiver() {
        noisyReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        noisyReceiver = null
    }

    private fun pauseAll() {
        if (aiMode) mixer?.pause() else exo?.pause()
        isPlaying.value = false
        abandonFocus()
    }

    // ---------- 로딩 ----------

    fun ensureLoaded(song: Song, aiOn: Boolean, muteMask: Int, semitones: Int) {
        DspBus.muteMask = muteMask
        val modeChanged = aiOn != aiMode || currentSong?.id != song.id
        if (!modeChanged && !needsReload(song, aiOn)) {
            applyParams(muteMask, semitones)
            return
        }
        val wasPlaying = activeIsPlaying()
        val pos = positionMs()

        releaseEngines()
        aiMode = aiOn && song.isSeparated
        currentSong = song
        nowPlayingTitle.value = song.title
        registerNoisyReceiver()

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
                abandonFocus()
            }).also {
                it.load(files)
                it.semitones = semitones
                it.gains = Stem.gainArray(muteMask)
                durationMs.value = framesToMs(it.durationFrames)
                it.seekToFrame(msToFrames(pos))
                if (wasPlaying && !wasAutoEnded) it.play()
            }
        } else {
            val player = buildExo()
            exo = player
            player.setMediaItem(MediaItem.fromUri(android.net.Uri.parse(song.uri)))
            player.prepare()
            if (pos > 0) player.seekTo(pos)
            player.playbackParameters = PlaybackParameters(1f, pitchRatio(semitones))
            if (wasPlaying && !wasAutoEnded) player.play()
            durationMs.value = song.durationMs
        }
        wasAutoEnded = false
    }

    private fun needsReload(song: Song, aiOn: Boolean): Boolean =
        aiOn && song.isSeparated && mixer == null

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
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ false,
            )
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                    this@PlayerController.isPlaying.value = isPlayingNow
                    if (!isPlayingNow && !wasAutoEnded) abandonFocus()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        this@PlayerController.isPlaying.value = false
                        wasAutoEnded = true
                        abandonFocus()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    this@PlayerController.isPlaying.value = false
                    abandonFocus()
                }
            })
        }
    }

    // ---------- 컨트롤 ----------

    fun playPause() {
        val willPlay = !(if (aiMode) mixer?.isPlaying == true else exo?.isPlaying == true)
        if (willPlay && !requestFocus()) return // 포커스 거부 시 재생하지 않음
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
        mixer?.gains = Stem.gainArray(mask)
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
        unregisterNoisyReceiver()
        abandonFocus()
        currentSong = null
        nowPlayingTitle.value = null
        isPlaying.value = false
        durationMs.value = 0L
    }

    // ---------- 유틸 ----------

    private fun releaseEngines() {
        exo?.release(); exo = null
        mixer?.release(); mixer = null
    }

    private fun activeIsPlaying(): Boolean =
        if (aiMode) mixer?.isPlaying == true else exo?.isPlaying == true

    private fun applyParams(muteMask: Int, semitones: Int) {
        setMuteMask(muteMask)
        setSemitones(semitones)
    }

    private fun msToFrames(ms: Long): Long = ms * sampleRateCompat() / 1000

    private fun framesToMs(frames: Long): Long =
        if (frames <= 0) 0 else frames * 1000 / sampleRateCompat()

    private fun sampleRateCompat(): Long = 44100L

    companion object {
        fun pitchRatio(semitones: Int): Float = Math.pow(2.0, semitones / 12.0).toFloat()
    }
}
