package com.bandmr.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bandmr.app.separation.Tier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLong

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore internal constructor(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.dataStore)

    private val selectionVersion = AtomicLong()
    val modelSelectionVersion: Long get() = selectionVersion.get()

    private val aiKey = booleanPreferencesKey("ai_enabled")
    private val tierKey = stringPreferencesKey("model_tier")
    private val vocalStrengthKey = floatPreferencesKey("vocal_strength")
    private val designKey = stringPreferencesKey("app_design")

    val design: Flow<AppDesign> = dataStore.data.map { AppDesign.fromId(it[designKey]) }

    /** 첫 프레임 전에 읽는다. 구독 초기값을 기본 테마로 두면 그 색이 먼저 보였다가 바뀐다. */
    fun startupDesign(): AppDesign = runBlocking { design.first() }

    suspend fun setDesign(design: AppDesign) {
        dataStore.edit { it[designKey] = design.id }
    }

    val aiEnabled: Flow<Boolean> = dataStore.data.map { it[aiKey] ?: false }

    val modelTier: Flow<String> = dataStore.data.map { it[tierKey] ?: Tier.S6_BALANCED.id }

    /** AI OFF 보컬 제거 강도 0..1 (기본 1 = 강하게) */
    val vocalStrength: Flow<Float> =
        dataStore.data.map { it[vocalStrengthKey] ?: 1f }

    suspend fun setAiEnabled(value: Boolean) {
        dataStore.edit { it[aiKey] = value }
    }

    suspend fun setModelTier(id: String) {
        selectionVersion.incrementAndGet()
        dataStore.edit { it[tierKey] = id }
    }

    /** 다운로드 중 사용자가 모델을 다시 골랐다면 그 선택을 보존한다. */
    suspend fun selectDownloadedModel(id: String, expectedVersion: Long) {
        dataStore.edit {
            if (selectionVersion.get() == expectedVersion) it[tierKey] = id
        }
    }

    suspend fun setVocalStrength(value: Float) {
        dataStore.edit { it[vocalStrengthKey] = value.coerceIn(0f, 1f) }
    }
}
