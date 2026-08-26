package com.bandmr.app

import android.app.Application
import androidx.core.net.toUri
import com.bandmr.app.audio.MixCache
import com.bandmr.app.audio.PlayerController
import com.bandmr.app.data.AppDatabase
import com.bandmr.app.data.SettingsStore
import com.bandmr.app.export.Exporter
import com.bandmr.app.separation.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

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
                songs.forEach { song ->
                    if (!MixCache.cacheFile(this@BandMrApp, song.id).exists()) {
                        runCatching {
                            MixCache.prepare(this@BandMrApp, song.id, song.uri.toUri())
                        }
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
        File(filesDir, "mixcache").listFiles()?.forEach { f ->
            val id = f.name.substringBefore('.').toLongOrNull()
            if (id == null || id !in validIds) f.delete()
        }
        File(filesDir, "stems").listFiles()?.forEach { d ->
            val id = d.name.toLongOrNull()
            if (id == null || id !in validIds) d.deleteRecursively()
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
