package com.bandmr.app

import android.app.Application
import android.util.Log
import androidx.core.net.toUri
import com.bandmr.app.audio.MixCache
import com.bandmr.app.audio.PlayerController
import com.bandmr.app.data.AppDatabase
import com.bandmr.app.data.SettingsStore
import com.bandmr.app.data.Song
import com.bandmr.app.export.Exporter
import com.bandmr.app.export.ExportTasks
import com.bandmr.app.separation.ModelManager
import com.bandmr.app.separation.StemFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

private const val TAG = "BandMrApp"

class BandMrApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Locator.init(this)
        preCacheMixes()
    }

    /** 캐시 없는 기존 곡들을 백그라운드로 미리 변환해 첫 재생이 즉시 되도록 한다 */
    private fun preCacheMixes() {
        // UI·서비스가 시작되기 전에 대기 대상을 등록한다. 사전 변환 전체가 아니라
        // DB 스냅샷 조회부터 고아 삭제까지 끝난 시점에 새 파일 작업을 허용한다.
        val cleanupDone = CompletableDeferred<Unit>()
        Locator.startupCleanup = cleanupDone
        val preCache = Locator.appScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                val preCacheContext = currentCoroutineContext()
                val songs = try {
                    Locator.songDao.getAllOnce().also { songs ->
                        cleanUpOrphans(songs.mapTo(HashSet()) { it.id })
                        cleanUpSourceFiles(songs)
                    }
                } finally {
                    cleanupDone.complete(Unit)
                }
                songs.forEach { song ->
                    preCacheContext.ensureActive()
                    if (!MixCache.cacheFile(this@BandMrApp, song.id).exists()) {
                        try {
                            MixCache.prepare(
                                this@BandMrApp, song.id, song.uri.toUri(),
                                checkCancelled = { preCacheContext.ensureActive() },
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            Log.w(TAG, "곡 ${song.id} 캐시 준비 실패", failure)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Log.w(TAG, "시작 캐시 준비 실패", failure)
            }
        }
        Locator.startupPreCacheJob = preCache
        // 코루틴 본문 실행 전에 취소되어도 대기를 남기지 않는다.
        preCache.invokeOnCompletion { cleanupDone.complete(Unit) }
        preCache.start()
    }

    /**
     * DB에 없는 곡의 잔여 파일 정리.
     * 곡 삭제와 백그라운드 작업(분리/캐시 준비)이 경쟁하면 스템·캐시가 고아로 남을 수 있다.
     */
    private fun cleanUpOrphans(validIds: Set<Long>) {
        MixCache.dir(this).listFiles()?.forEach { f ->
            val id = f.name.substringBefore('.').toLongOrNull()
            // 1패스 전환 전 버전이 남긴 중간 raw(곡당 수십 MB). 지금은 아무도 쓰지 않으므로
            // 유효한 곡의 것도 지운다 — 그냥 두면 id가 살아있어 영구히 남는다.
            // 일회성 마이그레이션: 1패스 이전 버전에서 올라오는 경로가 사라지면 지워도 된다.
            // .wav.part / .peaks.tmp는 지금도 쓰이는 임시 파일이라 건드리지 않는다(쓰기 중일 수 있음)
            if (f.name.endsWith(".raw")) {
                f.delete()
                return@forEach
            }
            if (id == null || id !in validIds) f.delete()
        }
        StemFiles.dir(this).listFiles()?.forEach { d ->
            val id = d.name.toLongOrNull()
            if (id == null || id !in validIds) d.deleteRecursively()
        }
    }

    /**
     * 참조가 끊긴 다운로드 원본(filesDir/sources, 유튜브 임포트) 정리.
     * 곡 삭제와 DB 쓰기 경쟁으로 남은 고아 .part·원본을 제거한다.
     */
    private fun cleanUpSourceFiles(songs: List<Song>) {
        val referenced = songs.mapNotNullTo(HashSet()) { s ->
            s.uri.takeIf { it.startsWith("file://") }?.toUri()?.path
        }
        File(filesDir, "sources").listFiles()?.forEach { f ->
            if (f.absolutePath !in referenced) f.delete()
        }
    }
}

/** 간단한 수동 DI 컨테이너 */
object Locator {
    private lateinit var appContext: Application

    /** 화면 수명과 무관하게 끝까지 실행되어야 하는 작업용 (모델 다운로드, 캐시 프리페치 등) */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 캐시 비우기가 취소·종료를 기다릴 대상. 앱 시작 때 한 번만 등록한다. */
    internal var startupPreCacheJob: Job? = null

    /** 새 파일 작업이 기다릴 시작 삭제 구간. 이후의 사전 변환은 대기 대상이 아니다. */
    @Volatile
    internal var startupCleanup: CompletableDeferred<Unit>? = null

    internal suspend fun awaitStartupCleanup() {
        startupCleanup?.await()
    }

    fun init(app: Application) {
        appContext = app
    }

    val context: Application get() = appContext

    val database: AppDatabase by lazy { AppDatabase.build(appContext) }
    val songDao get() = database.songDao()

    /**
     * 첫 목록은 화면을 그리기 전에 읽고, 이후에는 화면 수명과 무관하게 Room 변경을 반영한다.
     * 같은 StateFlow를 화면에서도 사용해 재진입 때 시작 시점의 목록이 다시 표시되지 않게 한다.
     */
    val librarySongState: StateFlow<List<Song>> by lazy {
        songDao.observeAll().stateIn(
            appScope,
            SharingStarted.Eagerly,
            runBlocking { songDao.getAllOnce() },
        )
    }

    fun librarySongs(): List<Song> = librarySongState.value
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val modelManager: ModelManager by lazy { ModelManager(appContext, appScope) }
    val playerController: PlayerController by lazy { PlayerController(appContext) }
    val exporter: Exporter by lazy { Exporter(appContext) }
    val exportTasks by lazy { ExportTasks(appScope) }
}
