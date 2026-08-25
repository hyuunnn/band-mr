package com.bandmr.app

import android.app.Application
import com.bandmr.app.audio.PlayerController
import com.bandmr.app.data.AppDatabase
import com.bandmr.app.data.SettingsStore
import com.bandmr.app.export.Exporter
import com.bandmr.app.separation.ModelManager

class BandMrApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Locator.init(this)
    }
}

/** 간단한 수동 DI 컨테이너 */
object Locator {
    private lateinit var appContext: Application

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
