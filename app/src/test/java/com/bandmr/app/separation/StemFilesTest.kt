package com.bandmr.app.separation

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class StemFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `시작 정리는 목적지가 없거나 부분 결과여도 먼저 이전 스템을 복구한다`() {
        for (partial in listOf(false, true)) {
            val root = temp.newFolder()
            val dest = File(root, "1")
            if (partial) {
                dest.mkdirs()
                File(dest, "partial.wav").writeText("incomplete")
            }
            val backup = File(root, "1.backup.tmp").apply { mkdirs() }
            File(backup, "old.wav").writeText("previous complete audio")
            File(root, "1.part").apply { mkdirs() }
            // 삭제된 곡의 백업은 복구하지 않고 고아로 정리한다.
            File(root, "2.backup.tmp").apply { mkdirs() }

            StemFiles.cleanUpOrphans(root, setOf(1L))

            assertEquals(listOf("1"), root.list()!!.toList())
            assertEquals(listOf("old.wav"), dest.list()!!.toList())
            assertEquals("previous complete audio", File(dest, "old.wav").readText())
        }
    }

    @Test
    fun `백업을 복구할 수 없으면 고아 삭제를 중단하고 백업을 남긴다`() {
        val root = temp.newFolder()
        val unreadableBackup = File(root, "1.backup.tmp").apply { writeText("not a directory") }
        val orphan = File(root, "2").apply { mkdirs() }

        try {
            StemFiles.cleanUpOrphans(root, setOf(1L))
            fail("백업 복구 실패가 전파되어야 한다")
        } catch (_: IOException) {
            assertTrue(unreadableBackup.exists())
            assertTrue(orphan.exists())
        }
    }
}
