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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        cleanUpStaleFiles()
    }

    /**
     * 시작할 때 잔여 파일만 정리한다. 재생 캐시는 곡 추가 때 만들고, 없으면 재생·분리·내보내기가
     * 그 자리에서 만든다 — 여기서 전 곡을 다시 변환하면 사용자가 비운 캐시가 다음 실행에 되살아난다.
     */
    private fun cleanUpStaleFiles() {
        Locator.appScope.launch(Dispatchers.IO) {
            try {
                val songs = Locator.songDao.getAllOnce()
                cleanUpOrphans(songs.mapTo(HashSet()) { it.id })
                cleanUpSourceFiles(songs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Log.w(TAG, "시작 정리 실패", failure)
            }
        }
    }

    /**
     * DB에 없는 곡의 잔여 파일 정리.
     * 곡 삭제와 백그라운드 작업(분리/캐시 준비)이 경쟁하면 스템·캐시가 고아로 남을 수 있다.
     */
    private fun cleanUpOrphans(validIds: Set<Long>) {
        MixCache.dir(this).listFiles()?.forEach { f ->
            val id = f.name.substringBefore('.').toLongOrNull()
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

    /** 화면 수명과 무관하게 끝까지 실행되어야 하는 작업용 (모델 다운로드, 유튜브 임포트 등) */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
