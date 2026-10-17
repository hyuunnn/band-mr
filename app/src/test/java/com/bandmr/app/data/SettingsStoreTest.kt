package com.bandmr.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `다운로드 중 선택을 바꾸지 않았으면 받은 모델을 선택한다`() = runTest {
        val settings = settings()
        val version = settings.modelSelectionVersion
        settings.selectDownloadedModel("downloaded", version)
        assertEquals("downloaded", settings.modelTier.first())
    }

    @Test
    fun `다운로드 중 나중에 고른 모델을 완료 콜백이 덮지 않는다`() = runTest {
        val settings = settings()
        val version = settings.modelSelectionVersion
        settings.setModelTier("manual")
        settings.selectDownloadedModel("downloaded", version)
        assertEquals("manual", settings.modelTier.first())
    }

    @Test
    fun `이전 모델을 다시 골라도 수동 선택을 보존한다`() = runTest {
        val settings = settings()
        val original = settings.modelTier.first()
        val version = settings.modelSelectionVersion
        settings.setModelTier("other")
        settings.setModelTier(original)
        settings.selectDownloadedModel("downloaded", version)
        assertEquals(original, settings.modelTier.first())
    }

    @Test
    fun `완료 쓰기가 대기 중일 때 수동 선택해도 수동 선택이 남는다`() = runTest {
        val settings = settings()
        val version = settings.modelSelectionVersion
        val completion = launch { settings.selectDownloadedModel("downloaded", version) }
        settings.setModelTier("manual")
        completion.join()
        assertEquals("manual", settings.modelTier.first())
    }

    @Test
    fun `수동 선택 뒤 새로 시작한 다운로드는 자동 선택할 수 있다`() = runTest {
        val settings = settings()
        settings.setModelTier("manual")
        settings.selectDownloadedModel("new-download", settings.modelSelectionVersion)
        assertEquals("new-download", settings.modelTier.first())
    }

    private fun TestScope.settings() = SettingsStore(
        PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(tmp.newFolder(), "settings.preferences_pb")
        },
    )
}
