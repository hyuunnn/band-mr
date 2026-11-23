package com.bandmr.app.export

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportMimeTest {
    @Test
    fun `저장 문서는 wav 확장자와 연결된 MIME으로 만든다`() {
        assertEquals("audio/x-wav", Exporter.WAV_MIME)
    }
}
