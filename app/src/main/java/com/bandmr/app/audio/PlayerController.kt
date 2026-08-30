package com.bandmr.app.audio

import android.content.Context
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
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

private const val TAG = "PlayerController"

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

    /**
     * 현재 모드의 엔진. 두 엔진 모두 [AudioTrackEngine]이라 재생/일시정지·시크·위치 조회는
     * 이 하나를 지나간다 — 모드별 분기를 컨트롤 메서드마다 반복하면 한쪽만 고치는 버그가 생긴다.
     * 반면 파라미터(키·배속·A-B·게인)는 모드를 바꿔도 유지되어야 하므로 [eachEngine]으로 양쪽에 건다.
     */
    private val active: AudioTrackEngine?
        get() = if (aiMode) mixer else source

    private inline fun eachEngine(block: (AudioTrackEngine) -> Unit) {
        mixer?.let(block)
        source?.let(block)
    }

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

    /**
     * [release]가 호출될 때마다 증가한다. 알림을 지워 엔진을 놓아준 뒤에도
     * 플레이어 화면이 남아 있으면 로드 조건(곡 id·모드)이 그대로여서 다시 준비되지 않는다.
     * 화면이 이 값을 보고 엔진을 재준비한다 — 없으면 재생 버튼이 영구 무반응이 된다.
     */
    val releaseEpoch = MutableStateFlow(0)

    /**
     * 시크할 때마다 증가한다(파형 스크럽·점프 버튼 모두 [seekTo]를 지나감).
     * [com.bandmr.app.playback.PlaybackService]가 이 신호로 알림 진행바를 갱신한다.
     */
    val seekEpoch = MutableStateFlow(0)

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
        active?.pause()
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

    fun ensureLoaded(song: Song, aiOn: Boolean, stemGainsPacked: Long, semitones: Int, speed: Float) {
        setStemLevels(stemGainsPacked)
        lastSemitones = semitones
        lastSpeed = PlaybackSpeed.snap(speed)
        val newAiMode = aiOn && song.isSeparated
        val modeChanged = newAiMode != aiMode || currentSong?.id != song.id
        if (!modeChanged && active != null) {
            applyParams(semitones, lastSpeed)
            applyLoopToEngines()
            snapIntoLoopIfNeeded()
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
            loadMixer(song, lastGains, semitones, lastSpeed, wasPlaying, pos)
        } else {
            loadSource(song, lastMask, semitones, lastSpeed, wasPlaying, pos)
        }
        applyLoopToEngines()
        snapIntoLoopIfNeeded()
        wasAutoEnded = false
    }

    private fun loadMixer(song: Song, gains: FloatArray, semi: Int, speed: Float, wasPlaying: Boolean, pos: Long) {
        mixer = StemMixPlayer(onEndedCallback = ::onAutoEnded).also {
            it.load(File(song.stemsDir!!))
            it.semitones = semi
            it.speed = speed
            it.gains = gains
            durationMs.value = framesToMs(it.durationFrames)
            it.seekToFrame(msToFrames(pos))
            if (wasPlaying && !wasAutoEnded) it.play()
        }
    }

    /** AI OFF 엔진 구성. 캐시가 없으면 준비 후 자동으로 이어 재생한다 */
    private fun loadSource(song: Song, mask: Int, semi: Int, speed: Float, wasPlaying: Boolean, pos: Long) {
        val f = MixCache.cacheFile(context, song.id)
        val player = if (f.exists()) openSourceOrDiscardCache(song.id) else null
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

    /**
     * 캐시 WAV로 엔진을 만든다. 파일이 있는데 쓸 수 없으면(헤더 손상, 길이 0) **캐시를 버리고** null.
     *
     * 열기 실패를 그냥 null로 흘리면 "캐시 없음"과 구분되지 않아 [beginPrepare]로 가는데,
     * [MixCache.prepare]는 `exists()`만 보고 즉시 반환하므로 지우지 않으면 열기 실패가 영구히
     * 반복된다 — 준비도 실패도 표시되지 않고 재생 버튼만 무반응이 되며 로그에도 흔적이 없다.
     * 파형 캐시(.peaks)도 함께 버린다: 유효성 검사가 원본 WAV **크기** 기준이라
     * 다시 만든 WAV가 같은 크기면 손상본으로 계산한 막대가 살아남을 수 있다.
     *
     * 길이 0도 버리는 이유: 헤더만 있는 44바이트 WAV는 [WavReader]가 정상 파싱해서 예외가 없는데,
     * `play()`가 `totalFrames == 0`에서 조용히 반환해 재생 버튼이 무반응이 된다. 지금은
     * [MixCache.prepare]가 승격 자체를 막지만, 그 검사가 없던 버전이 남긴 파일이 기기에 있을 수 있다.
     */
    private fun openSourceOrDiscardCache(songId: Long): SourceWavPlayer? =
        try {
            newSourcePlayer(MixCache.cacheFile(context, songId)).also {
                if (it.durationFrames <= 0) {
                    it.release()
                    error("캐시 WAV가 비어 있습니다 (0프레임)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "캐시 WAV를 쓸 수 없어 버리고 다시 만든다: songId=$songId", e)
            MixCache.delete(context, songId)
            null
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
                    val player = openSourceOrDiscardCache(songId)
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
                        applyLoopToEngines()
                        snapIntoLoopIfNeeded()
                        isPlaying.value = player.isPlaying
                    } else {
                        // 준비는 성공했는데 캐시를 열지 못한 경우(위에서 그 캐시를 버렸다).
                        // 실패를 노출해야 재생 버튼이 실제 재시도 진입점이 된다 — 조용히 지나가면
                        // 화면에 아무 표시 없이 재생만 안 되는 상태로 남는다
                        prepareFailedSongId.value = songId
                        abandonFocus()
                    }
                    clearPendingResume(songId)
                }
            }
        }
    }

    private var lastMask = 0
    private var lastGains = Stem.gainArrayFromPacked(Stem.DEFAULT_PACKED)
    private var lastSemitones = 0
    private var lastSpeed = PlaybackSpeed.DEFAULT

    /** 현재 배속 (알림 미디어 카드의 진행바 추정에 쓰임) */
    val currentSpeed: Float get() = lastSpeed
    private var vocalStrength = 1f
    private var lastLoopStartMs: Long? = null
    private var lastLoopEndMs: Long? = null

    private fun activeIsPlaying(): Boolean = active?.isPlaying == true

    private fun applyParams(semitones: Int, speed: Float) {
        setSemitones(semitones)
        setSpeed(speed)
    }

    // ---------- 컨트롤 ----------

    /** 화면의 재생 버튼용 토글. 현재 상태를 읽는 지점이 여기 한 곳이어야 한다 */
    fun playPause() = setPlaying(!activeIsPlaying())

    /**
     * 재생 상태를 [shouldPlay]로 맞춘다(절대 명령).
     *
     * 알림 미디어 카드·잠금화면·블루투스에서 오는 play/pause는 토글이 아니라 "재생해라 / 멈춰라"다.
     * 호출부가 상태를 읽어 토글로 바꾸면, 읽는 순간과 실행 사이에 자동 일시정지(오디오 포커스
     * 상실·이어폰 분리)가 끼면 명령이 정반대로 뒤집힌다 — 그래서 절대 명령을 그대로 받는다.
     * 이미 그 상태여도 안전하다(엔진의 play/pause와 포커스 요청·반납이 모두 멱등).
     */
    fun setPlaying(shouldPlay: Boolean) {
        if (shouldPlay && !requestFocus()) return // 포커스 거부 시 재생하지 않음
        val engine = active
        if (engine != null) {
            if (shouldPlay) engine.play() else engine.pause()
            isPlaying.value = engine.isPlaying
            // 이어폰 분리(pauseAll)와 동일하게 일시정지 시 포커스 반납
            if (!engine.isPlaying) abandonFocus()
            return
        }
        // 여기부터는 엔진이 없는 경우. 멈추라는 명령이면 이미 멈춘 상태다
        if (!shouldPlay) return
        // AI ON에서 믹서가 없으면 스템 로드가 실패한 것이라 준비할 것이 없다
        if (aiMode) {
            abandonFocus()
            return
        }
        // 캐시 준비 중/실패: 재생 의도를 저장해 두면 준비 완료 직후 자동 재생된다.
        // 실패 후라면 beginPrepare가 재시도 진입점이 된다 (준비 중이면 no-op)
        val song = currentSong ?: run { abandonFocus(); return }
        val keepPos = if (pendingResumeSongId == song.id) pendingResumePosMs else 0L
        pendingResume(song.id, true, keepPos)
        beginPrepare(song.id, song.uri.toUri())
    }

    fun seekTo(ms: Long) {
        val target = PlaybackLoop.clampSeek(ms, lastLoopStartMs, lastLoopEndMs, knownDurationMs())
        val engine = active
        if (engine != null) {
            engine.seekToFrame(msToFrames(target))
        } else {
            // 엔진이 없으면 준비 완료 후 이어갈 위치만 기억한다
            pendingResume(currentSong?.id ?: return, pendingResumePlay, target)
        }
        // 알림 미디어 카드가 진행바를 바로 따라오게 한다(앱 → 알림 방향 반영)
        seekEpoch.value += 1
    }

    /** 현재 위치에서 [deltaMs]만큼 이동. 범위는 [seekTo]가 자른다. */
    fun skipBy(deltaMs: Long) {
        seekTo(positionMs() + deltaMs)
    }

    /**
     * A-B 반복. 유효 구간이면 엔진이 B에서 A로 되돌리고, 시크/점프도 그 안에 가둔다.
     * [apply] false면 값만 기억한다. 곡 로드 직후 이전 곡 엔진을 건드리지 않기 위함.
     */
    fun setLoop(startMs: Long?, endMs: Long?, apply: Boolean = true) {
        lastLoopStartMs = startMs
        lastLoopEndMs = endMs
        if (!apply) return
        applyLoopToEngines()
        snapIntoLoopIfNeeded()
    }

    private fun snapIntoLoopIfNeeded() {
        if (!PlaybackLoop.isArmed(lastLoopStartMs, lastLoopEndMs)) return
        val pos = positionMs()
        val clamped = PlaybackLoop.clampSeek(pos, lastLoopStartMs, lastLoopEndMs, knownDurationMs())
        if (clamped != pos) seekTo(clamped)
    }

    private fun applyLoopToEngines() {
        val start = lastLoopStartMs
        val end = lastLoopEndMs
        val startFrame: Long
        val endFrame: Long
        if (start != null && end != null && PlaybackLoop.isArmed(start, end)) {
            startFrame = msToFrames(start)
            endFrame = msToFrames(end)
        } else {
            startFrame = PlaybackLoop.DISABLED_FRAME
            endFrame = PlaybackLoop.DISABLED_FRAME
        }
        eachEngine {
            it.loopStartFrame = startFrame
            it.loopEndFrame = endFrame
        }
    }

    /** 스템 유지 퍼센트. 믹서 게인과 AI OFF 뮤트 마스크를 같이 갱신한다. */
    fun setStemLevels(packed: Long) {
        lastMask = Stem.muteMaskFromPacked(packed)
        lastGains = Stem.gainArrayFromPacked(packed)
        mixer?.gains = lastGains
        source?.muteMask = lastMask
    }

    fun setSemitones(n: Int) {
        eachEngine { it.semitones = n }
    }

    fun setSpeed(v: Float) {
        lastSpeed = PlaybackSpeed.snap(v)
        eachEngine { it.speed = lastSpeed }
    }

    /** AI OFF 보컬 제거 강도 0..1 (설정에서 로드/변경 시 호출) */
    fun setVocalStrength(v: Float) {
        vocalStrength = v
        source?.vocalStrength = v
    }

    /** 현재 로드된 곡 id (곡 삭제 시 재생 중 여부 판별용) */
    fun currentSongId(): Long? = currentSong?.id

    fun positionMs(): Long =
        active?.let { framesToMs(it.positionFrames()) }
            // 엔진이 없으면 준비 완료 후 이어갈 위치를 그대로 보여준다(진행바가 0으로 튀지 않게)
            ?: if (pendingResumeSongId == currentSong?.id) pendingResumePosMs else 0L

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
        lastLoopStartMs = null
        lastLoopEndMs = null
        pendingResumeSongId?.let { clearPendingResume(it) }
        // 화면이 열려 있으면 이 신호로 엔진을 다시 준비한다
        releaseEpoch.value += 1
    }

    // ---------- 유틸 ----------

    private fun releaseEngines() {
        mixer?.release(); mixer = null
        source?.release(); source = null
    }

    // MixCache·스템 WAV가 전부 PIPELINE_SAMPLE_RATE(44.1kHz)로 고정되어 프레임 수학이 이 값에 묶인다
    private fun msToFrames(ms: Long): Long = ms * PIPELINE_SAMPLE_RATE / 1000

    private fun framesToMs(frames: Long): Long =
        if (frames <= 0) 0 else frames * 1000 / PIPELINE_SAMPLE_RATE
}
