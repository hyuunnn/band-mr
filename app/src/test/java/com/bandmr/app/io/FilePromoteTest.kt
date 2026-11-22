package com.bandmr.app.io

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 승격 계약: 목적지가 이미 있어도(= rename이 false를 돌려주는 기기 조건) 결과가 완성본이어야 하고
 * 임시 산출물은 남지 않아야 한다. MixCache·모델·유튜브 원본·스템이 모두 이 함수를 지난다.
 */
class FilePromoteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val payload = ByteArray(200) { it.toByte() }

    @Test
    fun `파일은 목적지가 있어도 완성본으로 교체된다`() {
        val part = tmp.newFile("id.m4a.part")
        val dest = tmp.newFile("id.m4a")
        part.writeBytes(payload)
        dest.writeBytes(byteArrayOf(1))

        FilePromote.file(part, dest)

        assertArrayEquals(payload, dest.readBytes())
        assertFalse(part.exists())
    }

    @Test
    fun `파일 승격은 없는 상위 디렉터리를 만든다`() {
        val part = tmp.newFile("model.tmp")
        part.writeBytes(payload)
        val dest = File(tmp.root, "models/balanced/model-6s.onnx")

        FilePromote.file(part, dest)

        assertArrayEquals(payload, dest.readBytes())
    }

    @Test
    fun `디렉터리는 기존 내용을 남기지 않고 교체된다`() {
        val part = tmp.newFolder("12.part")
        File(part, "vocals.wav").writeBytes(payload)
        val dest = tmp.newFolder("12")
        // 이전 분리 결과: 새 결과에 없는 파일이 섞여 남으면 안 된다
        File(dest, "stale.wav").writeBytes(byteArrayOf(9))

        FilePromote.directory(part, dest)

        assertTrue(dest.isDirectory)
        assertEquals(listOf("vocals.wav"), dest.list()!!.sorted())
        assertArrayEquals(payload, File(dest, "vocals.wav").readBytes())
        assertFalse(part.exists())
    }

    /**
     * 승격이 실패하면 목적지가 남아 있어서는 안 된다. 앱은 파일 존재 여부를 완성 신호로 쓰므로
     * (MixCache.cacheFile().exists() 등) 실패한 승격이 뭔가를 남기면 손상된 결과가 재생에 쓰인다.
     *
     * 여기서는 소스가 없는 경우로 계약만 고정한다 — 복사가 중간에 끊기는 상황(디스크 가득 등)은
     * 유닛테스트로 주입할 수 없어서, 그 경로는 [FilePromote]의 catch → delete가 담당한다.
     */
    @Test
    fun `승격이 실패하면 목적지를 남기지 않는다`() {
        val missing = File(tmp.root, "없는파일.part") // rename 실패 → 복사도 실패
        val dest = tmp.newFile("published.wav")
        dest.writeBytes(payload)

        var threw = false
        try {
            FilePromote.file(missing, dest)
        } catch (_: Throwable) {
            threw = true
        }

        assertTrue("실패는 호출자에게 전파되어야 한다", threw)
        assertFalse("실패한 승격 결과가 완성본으로 공개되면 안 된다", dest.exists())
    }

    @Test
    fun `디렉터리 승격이 실패하면 목적지를 남기지 않는다`() {
        val missing = File(tmp.root, "없는폴더.part")
        val dest = tmp.newFolder("stems")
        File(dest, "vocals.wav").writeBytes(payload)

        var threw = false
        try {
            FilePromote.directory(missing, dest)
        } catch (_: Throwable) {
            threw = true
        }

        assertTrue(threw)
        assertFalse("스템 일부만 있는 디렉터리가 남으면 분리 완료로 오인된다", dest.exists())
    }
}
