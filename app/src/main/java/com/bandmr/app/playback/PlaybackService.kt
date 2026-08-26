package com.bandmr.app.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.bandmr.app.Locator
import com.bandmr.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 백그라운드 재생용 포그라운드 서비스.
 * 플레이어 화면을 벗어나거나 앱을 내려도 연습 재생이 계속되며,
 * 알림에서 재생/일시정지·종료를 제어할 수 있다.
 */
class PlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var promoted = false

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        scope.launch {
            Locator.playerController.isPlaying.collect { promoteOrUpdate() }
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
        when (intent?.action) {
            ACTION_TOGGLE -> Locator.playerController.playPause()
            ACTION_STOP -> {
                Locator.playerController.release()
                promoted = false
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> promoteOrUpdate()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun promoteOrUpdate() {
        val playing = Locator.playerController.isPlaying.value
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

    private fun buildNotification(playing: Boolean): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val togglePi = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPi = PendingIntent.getService(
            this, 2,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.bandmr.app.R.drawable.ic_stat_mr)
            .setContentTitle(Locator.playerController.nowPlayingTitle.value ?: "밴드 MR")
            .setContentText(if (playing) "연습 재생 중" else "일시정지")
            .setContentIntent(openPi)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "일시정지" else "재생",
                togglePi,
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "종료", stopPi)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
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

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PlaybackService::class.java))
        }
    }
}
