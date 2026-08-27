package com.bandmr.app.separation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.core.app.ServiceCompat
import com.bandmr.app.Locator
import com.bandmr.app.MainActivity
import com.bandmr.app.R
import com.bandmr.app.audio.MixCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class SeparationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 코루틴 본체와 isCancelled 람다가 다른 스레드에서 읽으므로 가시성 보장 필요
    @Volatile
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                job?.cancel()
            }
            else -> {
                val songId = intent?.getLongExtra(EXTRA_SONG_ID, -1L) ?: -1L
                if (songId > 0 && job?.isActive != true) {
                    createChannel()
                    startInForeground("준비 중…")
                    job = scope.launch { run(songId) }
                }
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
        try {
            acquireWakeLock()
            val song = dao.get(songId) ?: error("곡을 찾을 수 없습니다")
            val tier = Tier.fromId(Locator.settings.modelTier.first())
            val modelFile = Locator.modelManager.modelFile(tier)
            if (!modelFile.exists()) error("먼저 설정에서 '${tier.label}' 모델을 다운로드하세요")

            setState(SepState.Running(songId, "입력 준비 중…", 0f))
            val wav = MixCache.prepare(this, songId, song.uri.toUri())

            val outDir = File(filesDir, "stems/$songId")
            outDir.deleteRecursively()

            val stems = DemucsSeparator().separate(
                modelFile, ModelConfig(), wav, outDir,
                segmentSamples = tier.segmentSamples,
                onProgress = { p, stage ->
                    setState(SepState.Running(songId, stage, p))
                },
                isCancelled = { job?.isActive != true },
            )
            check(stems.isNotEmpty()) { "분리 결과가 없습니다" }

            dao.update(song.copy(separatedTier = tier.id, stemsDir = outDir.absolutePath))
            setState(SepState.Done(songId))
        } catch (e: CancellationException) {
            cleanupFiles(songId)
            setState(SepState.Idle)
            throw e
        } catch (t: Throwable) {
            cleanupFiles(songId)
            setState(SepState.Error(songId, t.message ?: "분리 중 오류가 발생했습니다"))
        } finally {
            releaseWakeLock()
            stopSelf()
        }
    }

    private fun cleanupFiles(songId: Long) {
        File(cacheDir, "sep_$songId.raw").delete()
        File(filesDir, "stems/$songId").deleteRecursively()
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
            .setSmallIcon(R.drawable.ic_stat_mr)
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
