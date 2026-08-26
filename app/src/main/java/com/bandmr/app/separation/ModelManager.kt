package com.bandmr.app.separation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface ModelState {
    data object NotDownloaded : ModelState
    data class Downloading(val progress: Float) : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

/** 다운받은 파일 자체가 손상된 경우(부분 파일을 남기면 안 됨) */
private class IntegrityException(message: String) : IOException(message)

/** 3종(경량/균형/품질) 모델의 다운로드·삭제·상태 관리 */
class ModelManager(private val context: Context) {

    private val _states = MutableStateFlow<Map<Tier, ModelState>>(emptyMap())
    val states: StateFlow<Map<Tier, ModelState>> = _states

    init {
        _states.value = Tier.entries.associateWith { if (modelFile(it).exists()) ModelState.Ready else ModelState.NotDownloaded }
    }

    fun modelFile(tier: Tier): File = File(File(context.filesDir, "models/${tier.id}"), "model.onnx")

    fun isDownloaded(tier: Tier): Boolean = modelFile(tier).exists()

    suspend fun download(tier: Tier) {
        if (isDownloaded(tier)) return
        withContext(Dispatchers.IO) {
            setState(tier, ModelState.Downloading(0f))
            val tmp = File(context.cacheDir, "model_${tier.id}.tmp")
            try {
                // 이어받기 준비: 기존 부분 파일의 프리픽스 해시 선계산
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                var offset = if (tmp.exists()) tmp.length() else 0L
                if (offset > 0L) {
                    tmp.inputStream().use { input ->
                        val buf = ByteArray(DEFAULT_BUF)
                        var r: Int
                        while (input.read(buf).also { r = it } >= 0) digest.update(buf, 0, r)
                    }
                }

                val url = URL(tier.url)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                }
                try {
                    if (offset > 0L) conn.setRequestProperty("Range", "bytes=$offset-")
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        throw IOException("다운로드 실패: HTTP $code")
                    }
                    // 서버가 이어받기를 지원하지 않으면(200 응답) 처음부터 다시
                    val resuming = code == HttpURLConnection.HTTP_PARTIAL && offset > 0L
                    if (!resuming) {
                        offset = 0L
                        digest.reset()
                        if (tmp.exists()) tmp.delete()
                    }
                    val remaining = conn.contentLengthLong
                    val totalBytes = if (resuming) offset + remaining else remaining

                    conn.inputStream.use { input ->
                        val output = if (resuming) {
                            java.io.FileOutputStream(tmp, true)
                        } else {
                            tmp.outputStream()
                        }
                        output.use { out ->
                            val buf = ByteArray(DEFAULT_BUF)
                            var read: Int
                            var done = offset
                            var lastPct = if (totalBytes > 0) ((done * 100) / totalBytes).toInt() else -1
                            while (input.read(buf).also { read = it } >= 0) {
                                if (read > 0) {
                                    out.write(buf, 0, read)
                                    digest.update(buf, 0, read)
                                    done += read
                                    if (totalBytes > 0) {
                                        // % 단위로만 갱신해 StateFlow 업데이트 빈도를 줄인다
                                        val pct = ((done * 100) / totalBytes).toInt()
                                        if (pct != lastPct) {
                                            lastPct = pct
                                            setState(tier, ModelState.Downloading(done.toFloat() / totalBytes))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // 무결성 검증: 해시 우선, 없으면 최소 크기
                    tier.sha256?.let { expected ->
                        val actual = digest.digest().joinToString("") { "%02x".format(it) }
                        if (!actual.equals(expected, ignoreCase = true)) {
                            throw IntegrityException("모델 파일 무결성 오류 (해시 불일치)")
                        }
                    } ?: run {
                        if (tmp.length() < MIN_VALID_BYTES) {
                            throw IntegrityException("비정상 모델 파일")
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                val dest = modelFile(tier)
                dest.parentFile?.mkdirs()
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                setState(tier, ModelState.Ready)
            } catch (t: Throwable) {
                // 손상된 파일은 삭제, 네트워크 실패는 부분 파일을 남겨 이어받기 유도
                if (t is IntegrityException) tmp.delete()
                setState(tier, ModelState.Failed(t.message ?: "알 수 없는 오류"))
                throw t
            }
        }
    }

    fun delete(tier: Tier) {
        modelFile(tier).delete()
        setState(tier, ModelState.NotDownloaded)
    }

    private fun setState(tier: Tier, state: ModelState) {
        _states.update { it + (tier to state) }
    }

    companion object {
        private const val DEFAULT_BUF = 128 * 1024
        private const val MIN_VALID_BYTES = 1_000_000L
    }
}
