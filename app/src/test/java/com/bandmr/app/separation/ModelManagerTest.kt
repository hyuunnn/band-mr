package com.bandmr.app.separation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * 핀 검증 전 파일은 분리에 공개하지 않으며, 검증은 생성자 호출 뒤 IO 작업에서 한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `생성자에서는 본문을 검사하지 않고 IO 검증 전에는 사용을 막는다`() = runTest {
        val files = tmp.newFolder()
        val tier = Tier.S6_BALANCED
        val file = File(files, "models/${tier.id}/${tier.fileName}").apply {
            parentFile!!.mkdirs()
            writeText("unpinned model")
        }
        val manager = ModelManager(files, tmp.newFolder(), backgroundScope, StandardTestDispatcher(testScheduler))
        assertTrue("생성자가 동기 검증으로 파일을 지워서는 안 된다", file.exists())
        assertEquals(ModelState.Checking, manager.states.value[tier])
        assertFalse(manager.isDownloaded(tier))
        runCurrent()
        assertFalse(file.exists())
        assertEquals(ModelState.NotDownloaded, manager.states.value[tier])
    }

    @Test
    fun `분리와 다운로드는 시작 검증을 기다리고 검증된 파일을 재사용한다`() = runTest {
        val files = tmp.newFolder()
        val tier = Tier.S6_BALANCED
        val file = File(files, "models/${tier.id}/${tier.fileName}").apply {
            parentFile!!.mkdirs()
            writeText("previously verified")
        }
        File(file.parentFile, ModelManager.SHA_MARKER).writeText(tier.sha256)
        val manager = ModelManager(files, tmp.newFolder(), backgroundScope, StandardTestDispatcher(testScheduler))
        assertFalse(manager.isDownloaded(tier))
        val required = async { manager.requireModel(tier) }
        val download = async { manager.download(tier) }
        assertFalse(required.isCompleted)
        runCurrent()
        assertEquals(file, required.await())
        download.await() // 네트워크 호출 없이 기존 검증 파일을 재사용해야 한다.
        assertTrue(manager.isDownloaded(tier))
        assertEquals(ModelState.Ready, manager.states.value[tier])
        manager.delete(tier)
        assertFalse(manager.isDownloaded(tier))
    }

    @Test
    fun `핀 검증에 실패한 파일을 분리에서 요구하면 거부한다`() = runTest {
        val files = tmp.newFolder()
        val tier = Tier.S6_BALANCED
        File(files, "models/${tier.id}/${tier.fileName}").apply {
            parentFile!!.mkdirs()
            writeText("stale model")
        }
        val manager = ModelManager(files, tmp.newFolder(), backgroundScope, StandardTestDispatcher(testScheduler))
        try {
            manager.requireModel(tier)
            org.junit.Assert.fail("검증되지 않은 모델을 반환하면 안 된다")
        } catch (expected: IllegalStateException) {
            assertFalse(manager.isDownloaded(tier))
        }
    }

    @Test
    fun `핀과 다른 파일은 지운다`() {
        val file = tmp.newFile("model.onnx").apply { writeText("stale") }
        val marker = File(tmp.root, "sha256")
        val pin = ModelManager.sha256Hex(tmp.newFile("pin-src").apply { writeText("pinned") })

        assertFalse(ModelManager.retainPinnedModel(file, pin, marker))
        assertFalse(file.exists())
        assertFalse(marker.exists())
    }

    @Test
    fun `핀과 같으면 남기고 마커를 쓴다`() {
        val file = tmp.newFile("model.onnx").apply { writeText("current") }
        val marker = File(tmp.root, "sha256")
        val pin = ModelManager.sha256Hex(file)

        assertTrue(ModelManager.retainPinnedModel(file, pin, marker))
        assertTrue(file.exists())
        assertEquals(pin, marker.readText())
    }

    @Test
    fun `마커가 핀과 같으면 본문을 다시 보지 않고 남긴다`() {
        val file = tmp.newFile("model.onnx").apply { writeText("replaced-after-marker") }
        val pin = "abc"
        val marker = File(tmp.root, "sha256").apply { writeText(pin) }

        assertTrue(ModelManager.retainPinnedModel(file, pin, marker))
        assertEquals("replaced-after-marker", file.readText())
    }

    @Test
    fun `파일이 없으면 마커도 지운다`() {
        val file = File(tmp.root, "missing.onnx")
        val marker = File(tmp.root, "sha256").apply { writeText("stale") }

        assertFalse(ModelManager.retainPinnedModel(file, "abc", marker))
        assertFalse(marker.exists())
    }
}
