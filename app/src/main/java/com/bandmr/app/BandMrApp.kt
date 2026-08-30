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
import com.bandmr.app.separation.ModelManager
import com.bandmr.app.separation.StemFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
        Locator.appScope.launch(Dispatchers.IO) {
            runCatching {
                val songs = Locator.songDao.getAllOnce()
                cleanUpOrphans(songs.mapTo(HashSet()) { it.id })
                cleanUpSourceFiles(songs)
                songs.forEach { song ->
                    if (!MixCache.cacheFile(this@BandMrApp, song.id).exists()) {
                        runCatching {
                            MixCache.prepare(this@BandMrApp, song.id, song.uri.toUri())
                        }.onFailure { Log.w(TAG, "곡 ${song.id} 캐시 준비 실패", it) }
                    }
                }
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

    fun init(app: Application) {
        appContext = app
    }

    val context: Application get() = appContext

    val database: AppDatabase by lazy { AppDatabase.build(appContext) }
    val songDao get() = database.songDao()
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val modelManager: ModelManager by lazy { ModelManager(appContext) }
    val playerController: PlayerController by lazy { PlayerController(appContext) }
    val exporter: Exporter by lazy { Exporter(appContext) }
}
