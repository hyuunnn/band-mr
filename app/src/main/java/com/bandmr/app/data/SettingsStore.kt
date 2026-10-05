package com.bandmr.app.data

import android.content.Context
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

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {

    private val aiKey = booleanPreferencesKey("ai_enabled")
    private val tierKey = stringPreferencesKey("model_tier")
    private val vocalStrengthKey = floatPreferencesKey("vocal_strength")
    private val designKey = stringPreferencesKey("app_design")

    val design: Flow<AppDesign> = context.dataStore.data.map { AppDesign.fromId(it[designKey]) }

    /** 첫 프레임 전에 읽는다. 구독 초기값을 기본 테마로 두면 그 색이 먼저 보였다가 바뀐다. */
    fun startupDesign(): AppDesign = runBlocking { design.first() }

    suspend fun setDesign(design: AppDesign) {
        context.dataStore.edit { it[designKey] = design.id }
    }

    val aiEnabled: Flow<Boolean> = context.dataStore.data.map { it[aiKey] ?: false }

    val modelTier: Flow<String> = context.dataStore.data.map { it[tierKey] ?: Tier.S6_BALANCED.id }

    /** AI OFF 보컬 제거 강도 0..1 (기본 1 = 강하게) */
    val vocalStrength: Flow<Float> =
        context.dataStore.data.map { it[vocalStrengthKey] ?: 1f }

    suspend fun setAiEnabled(value: Boolean) {
        context.dataStore.edit { it[aiKey] = value }
    }

    suspend fun setModelTier(id: String) {
        context.dataStore.edit { it[tierKey] = id }
    }

    suspend fun setVocalStrength(value: Float) {
        context.dataStore.edit { it[vocalStrengthKey] = value.coerceIn(0f, 1f) }
    }
}
