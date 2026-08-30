package com.bandmr.app.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.core.app.ServiceCompat
import com.bandmr.app.Locator
import com.bandmr.app.MainActivity
import com.bandmr.app.R
import com.bandmr.app.audio.PlaybackSkip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 백그라운드 재생용 포그라운드 서비스.
 * 플레이어 화면을 벗어나거나 홈으로 나가도 연습 재생이 계속되며,
 * 알림에서 −10초·−5초·재생/일시정지·+5초·+10초를 조작할 수 있다.
 *
 * 알림은 [MediaSession]을 붙여 OS의 미디어 플레이어 카드로 그려진다(진행바·잠금화면 조작 포함).
 * 이때 카드의 버튼은 알림 액션이 아니라 **세션이 선언한 동작**에서 나오므로,
 * 점프 4개는 [PlaybackState]의 커스텀 액션으로도 함께 등록해야 카드에 보인다.
 *
 * 최근 앱 목록에서 앱을 치우거나([onTaskRemoved]) 알림을 지우면 재생도 정리한다.
 */
class PlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var promoted = false
    private var session: MediaSession? = null

    /**
     * 종료 절차 진입 표시. [stopPlaybackAndSelf]의 `release()`는 화면이 엔진을 다시 준비하도록
     * 신호를 보내는데(PlayerController.releaseEpoch), 그 결과 `nowPlayingTitle`이 다시 채워진다.
     * onDestroy가 아직 실행되지 않은 창에서 그 신호를 받으면 방금 지운 알림을 다시 띄운다.
     */
    @Volatile
    private var stopping = false

    /**
     * 미디어 카드의 점프 버튼. 값이 변하지 않으므로 한 번만 만들어 재사용한다
     * (진행바 갱신은 파형 드래그 중 초당 10회까지 일어난다).
     */
    private val skipActions: List<PlaybackState.CustomAction> by lazy {
        SkipButton.mediaCardOrder.map {
            PlaybackState.CustomAction.Builder(it.action, it.label, it.icon).build()
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSession(this, "BandMR").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = Locator.playerController.setPlaying(true)
                override fun onPause() = Locator.playerController.setPlaying(false)
                override fun onSeekTo(pos: Long) = Locator.playerController.seekTo(pos)
                override fun onStop() = stopPlaybackAndSelf()
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    SkipButton.of(action)?.let { skip(it) }
                }
            })
            isActive = true
        }
        scope.launch {
            Locator.playerController.isPlaying.collect { promoteOrUpdate() }
        }
        // 앱에서 시크(파형 스크럽·점프 버튼)하면 알림 진행바도 즉시 따라가게 한다
        scope.launch {
            Locator.playerController.seekEpoch.collect { syncPlaybackState() }
        }
        // A-B 반복 랩·배속 변경처럼 시크 없이 위치가 어긋나는 경우를 위한 주기 갱신.
        // collectLatest라 일시정지하면 루프가 취소된다(멈춰 있을 때 1초마다 깨우지 않는다)
        scope.launch {
            Locator.playerController.isPlaying.collectLatest { playing ->
                while (playing) {
                    delay(POSITION_SYNC_MS)
                    syncPlaybackState(true)
                }
            }
        }
        // 곡 삭제 등으로 컨트롤러가 해제되면(제목 null) 알림을 걷고 서비스도 종료
        scope.launch {
            Locator.playerController.nowPlayingTitle.collect { title ->
                if (title == null && !Locator.playerController.isPlaying.value) {
                    promoted = false
                    ServiceCompat.stopForeground(
                        this@PlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE,
                    )
                    stopSelf()
                } else {
                    promoteOrUpdate()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        // 종료 뒤 파괴되기 전에 다시 시작 요청이 오면 정상 동작으로 되돌린다
        if (action != ACTION_STOP) stopping = false
        val skipButton = action?.let { SkipButton.of(it) }
        when {
            skipButton != null -> skip(skipButton)
            action == ACTION_TOGGLE -> Locator.playerController.playPause()
            action == ACTION_STOP -> {
                stopPlaybackAndSelf()
                return START_NOT_STICKY
            }
            else -> promoteOrUpdate()
        }
        return START_NOT_STICKY
    }

    /**
     * 최근 앱 목록에서 앱을 치우면 재생도 정리한다.
     * 홈으로 나가는 것(백그라운드 전환)은 여기 오지 않으므로 연습 재생이 계속된다.
     * 포그라운드 서비스는 태스크가 사라져도 살아남기 때문에 명시적으로 끊어줘야 한다.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopPlaybackAndSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        session?.run { isActive = false; release() }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    // ---------- 조작 ----------

    private fun skip(button: SkipButton) {
        Locator.playerController.skipBy(button.deltaMs) // seekEpoch로 진행바가 갱신된다
    }

    /** 재생 정지 + 알림 제거 + 서비스 종료 (알림 지우기·앱 치우기·미디어 정지가 공유) */
    private fun stopPlaybackAndSelf() {
        if (stopping) return
        stopping = true
        Locator.playerController.release()
        promoted = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------- 세션 / 알림 ----------

    /** 제목·길이·재생 상태·점프 버튼을 세션에 반영한다(미디어 카드가 이걸 그린다) */
    private fun syncSession(playing: Boolean) {
        val s = session ?: return
        val ctrl = Locator.playerController
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, ctrl.nowPlayingTitle.value ?: "밴드 MR")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, if (playing) "연습 재생 중" else "일시정지")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, ctrl.durationMs.value)
                .build()
        )
        syncPlaybackState(playing)
    }

    /**
     * 재생 위치·상태만 세션에 반영한다(알림을 다시 만들지 않으므로 가볍다).
     * 시스템은 마지막으로 받은 위치에서 배속을 곱해 진행바를 추정하므로,
     * 앱에서 시크하면 반드시 알려줘야 한다 — 안 하면 알림 진행바가 옛 위치에 남는다.
     * A-B 반복 랩처럼 시크 호출 없이 위치가 바뀌는 경우까지 덮으려고 재생 중에는 1초마다 갱신한다.
     */
    private fun syncPlaybackState(playing: Boolean = Locator.playerController.isPlaying.value) {
        val s = session ?: return
        val ctrl = Locator.playerController
        val builder = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or
                    PlaybackState.ACTION_STOP
            )
            .setState(
                if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                ctrl.positionMs(),
                if (playing) ctrl.currentSpeed else 0f,
            )
        skipActions.forEach { builder.addCustomAction(it) }
        s.setPlaybackState(builder.build())
    }

    private fun promoteOrUpdate() {
        if (stopping) return // 종료 중에는 알림을 다시 띄우지 않는다
        val playing = Locator.playerController.isPlaying.value
        syncSession(playing)
        if (!promoted) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(playing),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
            promoted = true
        } else {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification(playing))
        }
    }

    private fun actionPi(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )

    private fun action(icon: Int, label: String, action: String, requestCode: Int): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(this, icon), label, actionPi(action, requestCode),
        ).build()

    private fun action(button: SkipButton): Notification.Action =
        action(button.icon, button.label, button.action, button.requestCode)

    /**
     * 미디어 카드로 그려지지 않는 환경(구형 런처·일부 알림 목록)을 위한 알림 액션도 함께 둔다.
     * 종료는 버튼 자리가 없어 알림을 지우는 동작(deleteIntent)에 붙였다.
     */
    private fun buildNotification(playing: Boolean): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val style = Notification.MediaStyle()
            .setShowActionsInCompactView(1, 2, 3) // 접힌 상태: −5초 / 재생·일시정지 / +5초
        session?.sessionToken?.let { style.setMediaSession(it) }

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(Locator.playerController.nowPlayingTitle.value ?: "밴드 MR")
            .setContentText(if (playing) "연습 재생 중" else "일시정지")
            .setContentIntent(openPi)
            .setDeleteIntent(actionPi(ACTION_STOP, 9))
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(style)

        // 알림 액션 순서는 −10 / −5 / 재생 / +5 / +10. 위 setShowActionsInCompactView(1, 2, 3)이
        // 이 순서에 묶여 있으므로 재생 버튼은 뒤로/앞으로 사이에 들어가야 한다
        SkipButton.rewind.forEach { builder.addAction(action(it)) }
        builder.addAction(
            action(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "일시정지" else "재생",
                ACTION_TOGGLE, 1,
            )
        )
        SkipButton.forward.forEach { builder.addAction(action(it)) }
        return builder.build()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "재생", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIF_ID = 1002
        private const val ACTION_TOGGLE = "toggle"
        private const val ACTION_STOP = "stop"

        /** 재생 중 알림 진행바를 맞추는 주기 */
        private const val POSITION_SYNC_MS = 1_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PlaybackService::class.java))
        }
    }
}

/**
 * 점프 버튼 한 벌. 알림 액션과 미디어 카드 커스텀 액션이 **같은 정의**를 써야 한다 —
 * 두 곳에 따로 적으면 아이콘/라벨/이동량이 조용히 어긋난다.
 * 등록 순서는 두 경로가 서로 다르므로 아래 companion의 목록으로 고정한다.
 */
private enum class SkipButton(
    val action: String,
    val label: String,
    val icon: Int,
    val deltaMs: Long,
    /** PendingIntent 구분용. 겹치면 다른 버튼의 인텐트를 재사용해 버린다 */
    val requestCode: Int,
) {
    BACK_LARGE("back_large", "10초 뒤로", R.drawable.ic_replay_10, -PlaybackSkip.LARGE_MS, 3),
    BACK_SMALL("back_small", "5초 뒤로", R.drawable.ic_replay_5, -PlaybackSkip.SMALL_MS, 4),
    FWD_SMALL("fwd_small", "5초 앞으로", R.drawable.ic_forward_5, PlaybackSkip.SMALL_MS, 5),
    FWD_LARGE("fwd_large", "10초 앞으로", R.drawable.ic_forward_10, PlaybackSkip.LARGE_MS, 6);

    companion object {
        fun of(action: String): SkipButton? = entries.firstOrNull { it.action == action }

        /**
         * 미디어 카드 등록 순서. 카드의 버튼 슬롯은 [custom0, prev, 재생, next, custom1]로 그려지는데
         * 등록 순서는 prev ← 1번째, next ← 2번째, custom0 ← 3번째, custom1 ← 4번째로 채워진다.
         * 화면에 −10 / −5 / 재생 / +5 / +10 으로 보이게 하려면 이 순서여야 한다.
         */
        val mediaCardOrder = listOf(BACK_SMALL, FWD_SMALL, BACK_LARGE, FWD_LARGE)

        /** 알림 액션은 화면 순서 그대로. 재생 버튼이 두 목록 사이에 들어간다 */
        val rewind = listOf(BACK_LARGE, BACK_SMALL)
        val forward = listOf(FWD_SMALL, FWD_LARGE)
    }
}
