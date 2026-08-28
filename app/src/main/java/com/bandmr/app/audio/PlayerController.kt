package com.bandmr.app.audio

import android.content.Context
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.content.BroadcastReceiver
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.bandmr.app.data.Song
import com.bandmr.app.data.Stem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AI ON: StemMixPlayer(스템 믹서) / AI OFF: SourceWavPlayer(원본 WAV 캐시 + 실시간 DSP).
 * 모드 전환 시 재생 위치를 유지한다.
 * 오디오 포커스 요청과 이어폰 분리(BECOMING_NOISY) 시 일시정지를 처리한다.
 *
 * AI OFF는 일부 기기에서 MediaCodec 스트리밍 디코딩이 깨지는 문제(무음·노이즈)가 있어
 * 압축 원본을 직접 스트리밍하지 않고, [MixCache]로 미리 디코딩해 둔 44.1kHz WAV를
 * AudioTrack으로 재생한다. 캐시는 곡 추가/앱 시작 때 백그라운드로 만들어지고,
 * 없는 상태로 재생하면 그 자리에서 준비한 뒤([preparingSongId]) 자동으로 이어 재생한다.
 */
class PlayerController(private val context: Context) {

    private var mixer: StemMixPlayer? = null
    private var source: SourceWavPlayer? = null

    @Volatile
    private var aiMode = false

    private var currentSong: Song? = null

    val isPlaying = MutableStateFlow(false)
    val durationMs = MutableStateFlow(0L)

    /** 알림 등에 표시할 현재 곡 제목 */
    val nowPlayingTitle = MutableStateFlow<String?>(null)

    /** 원본 WAV 캐시 생성 중인 곡 id (없으면 null) */
    val preparingSongId = MutableStateFlow<Long?>(null)

    /** 원본 WAV 캐시 준비에 실패한 곡 id (재생 버튼으로 재시도 가능) */
    val prepareFailedSongId = MutableStateFlow<Long?>(null)

    private var wasAutoEnded = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
        if (aiMode) mixer?.pause() else source?.pause()
        isPlaying.value = false
        abandonFocus()
    }

    /** 곡이 끝까지 재생되어 엔진이 스스로 멈췄을 때 (양쪽 엔진 공용) */
    private fun onAutoEnded() {
        wasAutoEnded = true
        isPlaying.value = false
        abandonFocus()
    }

    // ---------- 로딩 ----------

    fun ensureLoaded(song: Song, aiOn: Boolean, muteMask: Int, semitones: Int, speed: Float) {
        lastMask = muteMask
        lastSemitones = semitones
        lastSpeed = PlaybackSpeed.snap(speed)
        val newAiMode = aiOn && song.isSeparated
        val modeChanged = newAiMode != aiMode || currentSong?.id != song.id
        if (!modeChanged && engineExists()) {
            applyParams(muteMask, semitones, lastSpeed)
            return
        }
        val wasPlaying = activeIsPlaying()
        val pos = positionMs()

        releaseEngines()
        aiMode = newAiMode
        currentSong = song
        nowPlayingTitle.value = song.title
        registerNoisyReceiver()

        if (aiMode) {
            loadMixer(song, muteMask, semitones, lastSpeed, wasPlaying, pos)
        } else {
            loadSource(song, muteMask, semitones, lastSpeed, wasPlaying, pos)
        }
        wasAutoEnded = false
    }

    private fun loadMixer(song: Song, mask: Int, semi: Int, speed: Float, wasPlaying: Boolean, pos: Long) {
        val dir = File(song.stemsDir!!)
        val files = buildMap {
            Stem.entries.forEach { stem ->
                val f = File(dir, "${stem.fileName}.wav")
                if (f.exists()) put(stem, f)
            }
        }
        mixer = StemMixPlayer(onEndedCallback = ::onAutoEnded).also {
            it.load(files)
            it.semitones = semi
            it.speed = speed
            it.gains = Stem.gainArray(mask)
            durationMs.value = framesToMs(it.durationFrames)
            it.seekToFrame(msToFrames(pos))
            if (wasPlaying && !wasAutoEnded) it.play()
        }
    }

    /** AI OFF 엔진 구성. 캐시가 없으면 준비 후 자동으로 이어 재생한다 */
    private fun loadSource(song: Song, mask: Int, semi: Int, speed: Float, wasPlaying: Boolean, pos: Long) {
        val f = MixCache.cacheFile(context, song.id)
        val player = if (f.exists()) runCatching { newSourcePlayer(f) }.getOrNull() else null
        if (player == null) {
            // 준비 중 재입장 시 사용자가 저장한 재생 의도를 덮어쓰지 않는다
            if (pendingResumeSongId != song.id) {
                pendingResume(song.id, wasPlaying && !wasAutoEnded, pos)
            }
            beginPrepare(song.id, song.uri.toUri())
            durationMs.value = song.durationMs
            return
        }
        attachSource(player, mask, semi, speed, wasPlaying && !wasAutoEnded, pos)
        clearPendingResume(song.id)
    }

    private fun newSourcePlayer(file: File): SourceWavPlayer =
        SourceWavPlayer(file, onEndedCallback = ::onAutoEnded)

    private fun attachSource(player: SourceWavPlayer, mask: Int, semi: Int, speed: Float, play: Boolean, pos: Long) {
        player.muteMask = mask
        player.semitones = semi
        player.speed = speed
        player.vocalStrength = vocalStrength
        source = player
        durationMs.value = framesToMs(player.durationFrames)
        player.seekToFrame(msToFrames(pos))
        if (play) player.play()
    }

    // 캐시 준비 중 저장해 둔 재생 의도 (준비 완료 직후 자동 반영)
    private var pendingResumeSongId: Long? = null
    private var pendingResumePlay = false
    private var pendingResumePosMs = 0L

    private fun pendingResume(songId: Long, play: Boolean, posMs: Long) {
        pendingResumeSongId = songId
        pendingResumePlay = play
        pendingResumePosMs = posMs
    }

    private fun clearPendingResume(songId: Long) {
        if (pendingResumeSongId == songId) {
            pendingResumeSongId = null
            pendingResumePlay = false
            pendingResumePosMs = 0L
        }
    }

    /** 원본 WAV 캐시 생성 (중복 호출 안전). 완료 시 대기 중이던 재생을 이어간다 */
    private fun beginPrepare(songId: Long, uri: android.net.Uri) {
        if (preparingSongId.value == songId) return
        preparingSongId.value = songId
        if (prepareFailedSongId.value == songId) prepareFailedSongId.value = null
        scope.launch(Dispatchers.IO) {
            val ok = runCatching { MixCache.prepare(context, songId, uri) }.isSuccess
            withContext(Dispatchers.Main) {
                if (preparingSongId.value == songId) preparingSongId.value = null
                if (!ok) {
                    // 실패를 노출하고 저장해 둔 재생 의도도 폐기 (스테일 자동 재생 방지)
                    prepareFailedSongId.value = songId
                    clearPendingResume(songId)
                    abandonFocus() // 재생 대기 중 잡아둔 포커스 반납
                    return@withContext
                }
                val cur = currentSong ?: return@withContext
                if (cur.id == songId && !aiMode) {
                    val f = MixCache.cacheFile(context, songId)
                    val player = runCatching { newSourcePlayer(f) }.getOrNull()
                    if (player != null) {
                        val resume = pendingResumeSongId == songId
                        attachSource(
                            player,
                            lastMask,
                            lastSemitones,
                            lastSpeed,
                            resume && pendingResumePlay,
                            if (resume) pendingResumePosMs else 0L,
                        )
                        isPlaying.value = player.isPlaying
                    }
                    clearPendingResume(songId)
                }
            }
        }
    }

    private var lastMask = 0
    private var lastSemitones = 0
    private var lastSpeed = PlaybackSpeed.DEFAULT
    private var vocalStrength = 1f

    private fun engineExists(): Boolean =
        if (aiMode) mixer != null else source != null

    private fun activeIsPlaying(): Boolean =
        if (aiMode) mixer?.isPlaying == true else source?.isPlaying == true

    private fun applyParams(muteMask: Int, semitones: Int, speed: Float) {
        setMuteMask(muteMask)
        setSemitones(semitones)
        setSpeed(speed)
    }

    // ---------- 컨트롤 ----------

    fun playPause() {
        val willPlay = !activeIsPlaying()
        if (willPlay && !requestFocus()) return // 포커스 거부 시 재생하지 않음
        when {
            aiMode -> {
                val m = mixer ?: run { if (willPlay) abandonFocus(); return }
                if (m.isPlaying) m.pause() else m.play()
                isPlaying.value = m.isPlaying
                // 이어폰 분리(pauseAll)와 동일하게 일시정지 시 포커스 반납
                if (!m.isPlaying) abandonFocus()
            }
            source != null -> source?.let {
                if (it.isPlaying) it.pause() else it.play()
                isPlaying.value = it.isPlaying
                if (!it.isPlaying) abandonFocus()
            }
            else -> {
                // 캐시 준비 중/실패: 재생 의도를 저장해 두면 준비 완료 직후 자동 재생된다.
                // 실패 후라면 beginPrepare가 재시도 진입점이 된다 (준비 중이면 no-op)
                if (willPlay) {
                    val song = currentSong ?: run { abandonFocus(); return }
                    val keepPos = if (pendingResumeSongId == song.id) pendingResumePosMs else 0L
                    pendingResume(song.id, true, keepPos)
                    beginPrepare(song.id, song.uri.toUri())
                }
            }
        }
    }

    fun seekTo(ms: Long) {
        val target = PlaybackSkip.clamp(ms, knownDurationMs())
        when {
            aiMode -> mixer?.seekToFrame(msToFrames(target))
            source != null -> source?.seekToFrame(msToFrames(target))
            else -> pendingResume(currentSong?.id ?: return, pendingResumePlay, target)
        }
    }

    /** 현재 위치에서 [deltaMs]만큼 이동. 범위는 [seekTo]가 자른다. */
    fun skipBy(deltaMs: Long) {
        seekTo(positionMs() + deltaMs)
    }

    fun setMuteMask(mask: Int) {
        mixer?.gains = Stem.gainArray(mask)
        source?.muteMask = mask
    }

    fun setSemitones(n: Int) {
        mixer?.semitones = n
        source?.semitones = n
    }

    fun setSpeed(v: Float) {
        lastSpeed = PlaybackSpeed.snap(v)
        mixer?.speed = lastSpeed
        source?.speed = lastSpeed
    }

    /** AI OFF 보컬 제거 강도 0..1 (설정에서 로드/변경 시 호출) */
    fun setVocalStrength(v: Float) {
        vocalStrength = v
        source?.vocalStrength = v
    }

    /** 현재 로드된 곡 id (곡 삭제 시 재생 중 여부 판별용) */
    fun currentSongId(): Long? = currentSong?.id

    fun positionMs(): Long =
        when {
            aiMode -> framesToMs(mixer?.positionFrames() ?: 0L)
            source != null -> framesToMs(source?.positionFrames() ?: 0L)
            pendingResumeSongId == currentSong?.id -> pendingResumePosMs
            else -> 0L
        }

    private fun knownDurationMs(): Long =
        durationMs.value.takeIf { it > 0 } ?: currentSong?.durationMs ?: 0L

    fun release() {
        releaseEngines()
        unregisterNoisyReceiver()
        abandonFocus()
        // 주의: scope는 취소하지 않는다. 싱글턴 컨트롤러에서 cancel하면
        // 이후 모든 캐시 준비 코루틴이 조용히 무시된다(문구만 남는 버그).
        currentSong = null
        nowPlayingTitle.value = null
        isPlaying.value = false
        durationMs.value = 0L
        preparingSongId.value = null
        prepareFailedSongId.value = null
        pendingResumeSongId?.let { clearPendingResume(it) }
    }

    // ---------- 유틸 ----------

    private fun releaseEngines() {
        mixer?.release(); mixer = null
        source?.release(); source = null
    }

    private fun msToFrames(ms: Long): Long = ms * sampleRateCompat() / 1000

    private fun framesToMs(frames: Long): Long =
        if (frames <= 0) 0 else frames * 1000 / sampleRateCompat()

    private fun sampleRateCompat(): Long = 44_100L
}
