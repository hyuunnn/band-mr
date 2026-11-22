package com.bandmr.app.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 캐시 정리 계약: 쓰는 중일 수 있는 임시 산출물(`.part`/`.tmp`)을 지우면 안 된다.
 *
 * MixCache의 `.wav.part`, 파형 캐시의 `.peaks.tmp`, 모델의 `.tmp`가 모두 여기 걸린다.
 * 이 앱은 "완성된 뒤에만 정식 이름으로 공개"([FilePromote]) 규약을 쓰므로, 설정에서
 * 캐시를 비우는 순간 진행 중인 승격 대상이 사라지면 준비가 조용히 실패한다.
 */
class CacheStorageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(dir: File, name: String, size: Int): File =
        File(dir, name).apply { parentFile?.mkdirs(); writeBytes(ByteArray(size)) }

    @Test
    fun `dirSize는 하위 디렉터리까지 합산한다`() {
        val root = tmp.newFolder("mix")
        write(root, "1.wav", 100)
        write(root, "1.peaks", 20)
        write(root, "sub/2.wav", 30)

        assertEquals(150L, CacheStorage.dirSize(root))
    }

    @Test
    fun `없는 디렉터리는 0`() {
        assertEquals(0L, CacheStorage.dirSize(File(tmp.root, "nope")))
    }

    @Test
    fun `clearFiles는 파일만 지우고 part tmp는 남긴다`() {
        val root = tmp.newFolder("mix")
        write(root, "1.wav", 100)
        write(root, "1.peaks", 20)
        val part = write(root, "2.wav.part", 40)   // 디코딩 진행 중
        val peaksTmp = write(root, "3.peaks.tmp", 10) // 파형 쓰기 중
        write(root, "sub/keep.wav", 7)             // 하위 디렉터리는 대상 아님

        val freed = CacheStorage.clearFiles(root)

        assertEquals("지운 바이트는 실제 삭제분만", 120L, freed)
        assertFalse(File(root, "1.wav").exists())
        assertFalse(File(root, "1.peaks").exists())
        assertTrue("쓰는 중인 .part를 지우면 승격이 실패한다", part.exists())
        assertTrue("쓰는 중인 .tmp를 지우면 파형 쓰기가 실패한다", peaksTmp.exists())
        assertTrue(File(root, "sub/keep.wav").exists())
    }

    /**
     * 화면 표시·버튼 활성 기준은 **지울 수 있는 양**이어야 한다.
     * dirSize(= `.part` 포함)를 쓰면 "용량은 남았는데 눌러도 0B"가 된다 — 실기기에서
     * 실제로 재현됐던 버그다(비운 뒤 `2.wav.part` 500KB가 남아 버튼이 계속 활성).
     */
    @Test
    fun `clearable 크기는 clearFiles가 실제로 지우는 양과 같다`() {
        val root = tmp.newFolder("mix")
        write(root, "1.wav", 100)
        write(root, "1.peaks", 20)
        write(root, "2.wav.part", 40)
        write(root, "3.peaks.tmp", 10)

        val clearable = CacheStorage.clearableFileSize(root)
        assertEquals("dirSize와 달라야 한다(그게 버그의 원인이었다)", 170L, CacheStorage.dirSize(root))
        assertEquals(120L, clearable)
        assertEquals("표시값과 실제 회수량이 같아야 한다", clearable, CacheStorage.clearFiles(root))
        assertEquals("다 비운 뒤에는 0 — 버튼이 비활성이 된다", 0L, CacheStorage.clearableFileSize(root))
    }

    @Test
    fun `clearable 하위 디렉터리 크기도 실제 삭제량과 같다`() {
        val root = tmp.newFolder("stems")
        write(root, "1/vocals.wav", 100)
        write(root, "3.part/vocals.wav", 60)
        write(root, "stray.txt", 5)

        assertEquals(100L, CacheStorage.clearableSubdirectorySize(root))
        val all = CacheStorage.clearableSubdirectorySize(root, includeInFlight = true)
        assertEquals(160L, all)
        assertEquals(all, CacheStorage.clearSubdirectories(root, includeInFlight = true))
        assertEquals(0L, CacheStorage.clearableSubdirectorySize(root, includeInFlight = true))
    }

    @Test
    fun `clearSubdirectories는 곡별 폴더를 통째로 지우고 part 폴더는 남긴다`() {
        val root = tmp.newFolder("stems")
        write(root, "1/vocals.wav", 100)
        write(root, "1/drums.wav", 50)
        write(root, "2/vocals.wav", 25)
        val running = write(root, "3.part/vocals.wav", 60) // 분리 진행 중
        write(root, "stray.txt", 5)                        // 파일은 대상 아님

        val freed = CacheStorage.clearSubdirectories(root)

        assertEquals(175L, freed)
        assertFalse(File(root, "1").exists())
        assertFalse(File(root, "2").exists())
        assertTrue("진행 중인 분리 산출물을 지우면 결과가 사라진다", running.exists())
        assertTrue(File(root, "stray.txt").exists())
    }

    /**
     * "분리 결과 전체 삭제"는 호출부가 먼저 분리를 취소하므로 `.part`까지 지운다.
     * 남겨두면 취소 판정(세그먼트 경계) 직후 완료된 분리가 승격되어, DB는 미분리인데
     * 스템 디렉터리만 살아 있는 고아가 된다 — 유효한 songId라 cleanUpOrphans도 못 지운다.
     */
    @Test
    fun `includeInFlight면 part 폴더까지 지운다`() {
        val root = tmp.newFolder("stems")
        write(root, "1/vocals.wav", 100)
        write(root, "3.part/vocals.wav", 60)

        val freed = CacheStorage.clearSubdirectories(root, includeInFlight = true)

        assertEquals(160L, freed)
        assertFalse(File(root, "1").exists())
        assertFalse(File(root, "3.part").exists())
    }

    @Test
    fun `formatBytes`() {
        assertEquals("0B", CacheStorage.formatBytes(0))
        assertEquals("0B", CacheStorage.formatBytes(-1))
        assertEquals("512B", CacheStorage.formatBytes(512))
        assertEquals("1.0KB", CacheStorage.formatBytes(1024))
        assertEquals("40.0MB", CacheStorage.formatBytes(40L * 1024 * 1024))
        assertEquals("2.8GB", CacheStorage.formatBytes(3_006_477_107L))
    }
}
