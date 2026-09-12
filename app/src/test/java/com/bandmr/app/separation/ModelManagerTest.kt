package com.bandmr.app.separation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 이미 받은 파일은 exists()만 보면 Ready가 된다. 카탈로그 핀과 다르면 버린다.
 */
class ModelManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

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
