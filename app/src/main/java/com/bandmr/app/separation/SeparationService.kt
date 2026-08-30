package com.bandmr.app.separation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.core.app.ServiceCompat
import com.bandmr.app.Locator
import com.bandmr.app.MainActivity
import com.bandmr.app.R
import com.bandmr.app.audio.MixCache
import com.bandmr.app.io.FilePromote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SeparationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 코루틴 본체와 isCancelled 람다가 다른 스레드에서 읽으므로 가시성 보장 필요
    @Volatile
    private var job: Job? = null

    // stopSelf(startId)는 "가장 최근 시작 요청"과 일치할 때만 서비스를 멈춘다
    @Volatile
    private var lastStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_CANCEL -> {
                val running = job
                running?.cancel()
                // 돌고 있는 작업이 없으면 그 작업의 finally가 멈춰 줄 수 없다 — 여기서 정리한다
                if (running == null || running.isCompleted) stopSelf(startId)
            }
            else -> {
                val songId = intent?.getLongExtra(EXTRA_SONG_ID, -1L) ?: -1L
                // startForegroundService로 들어오므로 아무 일도 하지 않을 때도 반드시 stopSelf
                if (songId <= 0) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                // 이미 돌고 있으면 무시한다(그 작업이 끝날 때 서비스를 멈춘다)
                if (job?.isActive == true) return START_NOT_STICKY

                createChannel()
                startInForeground("준비 중…")
                // 취소된 이전 작업이 아직 ONNX 추론 중일 수 있다. 세션이 수 GB를 쓰므로
                // 겹쳐 돌면 안 된다 — 이전 작업을 끝까지 기다린 뒤 시작한다.
                // LAZY로 만들어 job 대입을 먼저 끝내야 run()이 자기 Job을 확실히 본다.
                val previous = job
                val next = scope.launch(start = CoroutineStart.LAZY) {
                    previous?.let {
                        it.cancel()
                        it.join()
                    }
                    run(songId)
                }
                job = next
                next.start()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun run(songId: Long) {
        val dao = Locator.songDao
        // 취소 판정은 자기 자신의 Job으로 한다. 서비스 필드(job)를 읽으면 (a) 대입 전에는 null이라
        // 즉시 취소로 오판하고, (b) 취소 직후 새 작업이 필드를 덮어쓰면 이 작업이 영원히 취소되지 않는다
        val self = currentCoroutineContext()[Job]
        val partDir = File(filesDir, "stems/$songId.part")
        try {
            acquireWakeLock()
            val song = dao.get(songId) ?: error("곡을 찾을 수 없습니다")
            val tier = Tier.fromId(Locator.settings.modelTier.first())
            val modelFile = Locator.modelManager.modelFile(tier)
            if (!modelFile.exists()) error("먼저 설정에서 '${tier.label}' 모델을 다운로드하세요")

            setState(SepState.Running(songId, "입력 준비 중…", 0f))
            // MixCache.prepare와 ONNX 추론은 둘 다 블로킹이라 IO 디스패처에서 돌린다
            val stemsDir = withContext(Dispatchers.IO) {
                val wav = MixCache.prepare(this@SeparationService, songId, song.uri.toUri())

                partDir.deleteRecursively()
                val stems = DemucsSeparator().separate(
                    modelFile, ModelConfig(), wav, partDir,
                    segmentSamples = tier.segmentSamples,
                    onProgress = { p, stage ->
                        // 취소된 뒤에는 상태를 되살리지 않는다. isCancelled는 세그먼트 경계에서만
                        // 보므로, 취소 시점의 세그먼트가 끝나면 진행률이 한 번 더 올라온다
                        if (self?.isActive == true) setState(SepState.Running(songId, stage, p))
                    },
                    isCancelled = { self?.isActive != true },
                )
                check(stems.isNotEmpty()) { "분리 결과가 없습니다" }
                // 완성된 결과만 정식 디렉터리로 교체한다. 중간에 취소/실패하면 이전 스템이 그대로
                // 남아서 DB의 분리 완료 표시(stemsDir)와 파일이 어긋나지 않는다
                promoteStems(partDir, File(filesDir, "stems/$songId"))
            }
            dao.updateSeparation(songId, tier.id, stemsDir.absolutePath)
            // 완료 여부는 Song.isSeparated가 갖는다 — 버스는 진행/오류 표시 전용이라 Idle로 되돌린다
            setState(SepState.Idle)
        } catch (e: CancellationException) {
            partDir.deleteRecursively()
            setState(SepState.Idle)
            throw e
        } catch (t: Throwable) {
            partDir.deleteRecursively()
            setState(SepState.Error(songId, t.message ?: "분리 중 오류가 발생했습니다"))
        } finally {
            releaseWakeLock()
            // 뒤에 새 작업이 예약됐으면(job이 교체됨) 서비스를 멈추지 않는다 — 새 분리를 죽이지 않도록
            if (job === self) stopSelf(lastStartId)
        }
    }

    /** 완성된 임시 스템 디렉터리를 정식 위치로 교체 */
    private fun promoteStems(part: File, dest: File): File {
        FilePromote.directory(part, dest)
        return dest
    }

    private fun setState(s: SepState) {
        SepBus.state.value = s
        if (s is SepState.Running) {
            updateNotification(s.stage, (s.progress * 100).toInt())
        }
    }

    // ---------- 알림 ----------

    private fun buildNotification(text: String, progressPct: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle("스템 분리 중")
            .setContentText(text)
            .setProgress(100, progressPct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    private fun startInForeground(text: String) {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(text, 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun updateNotification(text: String, pct: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text, pct))
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "스템 분리", NotificationManager.IMPORTANCE_LOW)
        )
    }

    // ---------- WakeLock ----------

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BandMR:separation").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.release() }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "separation"
        private const val NOTIF_ID = 1001
        private const val EXTRA_SONG_ID = "song_id"
        private const val ACTION_CANCEL = "cancel"
        private const val WAKELOCK_MS = 60 * 60 * 1000L

        fun start(context: Context, songId: Long) {
            val intent = Intent(context, SeparationService::class.java)
                .putExtra(EXTRA_SONG_ID, songId)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, SeparationService::class.java).setAction(ACTION_CANCEL)
            )
        }
    }
}
