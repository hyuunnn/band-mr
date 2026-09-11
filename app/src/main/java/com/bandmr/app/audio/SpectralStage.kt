package com.bandmr.app.audio

/**
 * STFT 기반 스펙트럼 처리 스테이지.
 *  - 보컬 제거: 패닝 인덱스 중앙 마스킹 (Avendano 2003) + 포먼트 가중 + 타악/서브 보존
 *  - 드럼 제거: 주파수축 중간값 필터링(HPSS)으로 타악 성분 억제
 *  - 베이스 제거: f0 검출 후 배음 콤 노칭
 *
 * 블록 지연(block=1024, hop=512 프레임)이 있으며 출력 FIFO로 흡수한다.
 * 분석/합성 모두 sqrt-Hann을 쓰며 hop=block/2에서 COLA 조건(합=1)을 만족한다.
 */
class SpectralStage(private val sampleRate: Int, channels: Int = 2) {

    private val n = BLOCK
    private val bins = n / 2 + 1
    private val hop = n / 2
    /** 1=모노, 2=스테레오 (인터리브 처리) */
    private val chCount = if (channels >= 2) 2 else 1

    private val fft = Fft(n)
    private val window = FloatArray(n).also { w ->
        for (i in 0 until n) {
            val hann = 0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / n)
            w[i] = kotlin.math.sqrt(hann).toFloat() // sqrt-Hann ×2 = COLA (hop=n/2)
        }
    }

    // 입력 프레임 버퍼 (interleaved)
    private var pending = FloatArray(n * chCount)
    private var pendingLen = 0

    // 시간축 중간값용 크기 스펙트럼 히스토리 (채널별)
    private val histDepth = MEDIAN_TIME
    private val magHist = Array(2) { Array(histDepth) { FloatArray(bins) } }
    private var histFill = 0
    private var histPos = 0

    // 채널별 OLA 상태와 역변환 결과
    private val olaTail = Array(2) { FloatArray(n) }
    private val specCh = Array(2) { FloatArray(n) }

    // 출력 FIFO (상대 인덱스 관리)
    private var outBuf = FloatArray(BLOCK * 8)
    private var fifoHead = 0
    private var fifoSize = 0

    private val histScratch = FloatArray(MEDIAN_TIME)
    // 채널별 스펙트럼 워크스페이스 (보컬 마스킹은 L/R을 함께 봐야 함)
    private val chRe = Array(2) { FloatArray(n) }
    private val chIm = Array(2) { FloatArray(n) }
    private val mags = FloatArray(bins)
    private val medV = FloatArray(bins)
    // 보컬용 HPSS: 프레임당 1회, mid 스펙트럼. 드럼 magHist는 채널마다 칸을 건너뛰어
    // 시간 중간값이 0으로 끌리므로 보컬 보호에 쓰면 안 된다.
    private val vocalMagHist = Array(MEDIAN_TIME) { FloatArray(bins) }
    private val vocalMags = FloatArray(bins)
    private val vocalPerc = FloatArray(bins)
    private val vocalMedV = FloatArray(bins)
    private var vocalHistPos = 0
    private var vocalHistFill = 0
    private val ilace = FloatArray(hop * chCount)
    private val scratch = FloatArray(MEDIAN_FREQ)

    /** 포먼트 대역(보컬 존재 판정·최대 감쇠). 빈 주파수는 process 때 j * sr / n */
    private val formantLoBin =
        kotlin.math.ceil(FORMANT_LO_HZ * n / sampleRate.toDouble()).toInt().coerceAtLeast(1)
    private val formantHiBin =
        kotlin.math.floor(FORMANT_HI_HZ * n / sampleRate.toDouble()).toInt().coerceAtMost(n / 2)

    /**
     * 보컬 제거 강도 0..1. 감쇠 시작 유사도와 최대 감쇠 깊이를 함께 조절한다.
     *  0 = 부드럽게 (시작 0.8, 최대 -12dB → 보컬이 작게 들리는 수준)
     *  1 = 강하게 (시작 0.45, 최대 -40dB → 사실상 제거)
     * 재생 중 변경해도 안전하다 (프레임 단위로 읽는 무상태 파라미터).
     */
    @Volatile
    var vocalStrength: Float = 1f

    /** 베이스 f0 검출용 저역 통과 상태 */
    private var lpState = 0f
    private val detBuf = FloatArray(n)

    /**
     * interleaved float 입력을 받아 처리 후 내부 FIFO에 적산.
     * 세 뮤트가 모두 false면 순수 패스스루.
     */
    fun feed(
        input: FloatArray,
        offset: Int,
        count: Int,
        muteDrums: Boolean,
        muteBass: Boolean,
        muteVocal: Boolean = false,
    ) {
        if (!muteDrums && !muteBass && !muteVocal) {
            appendOut(input, offset, count)
            return
        }
        var pos = offset
        var remaining = count
        val blockSamples = n * chCount
        while (remaining > 0) {
            val space = blockSamples - pendingLen
            val take = minOf(space, remaining)
            System.arraycopy(input, pos, pending, pendingLen, take)
            pendingLen += take
            pos += take
            remaining -= take
            if (pendingLen == blockSamples) {
                processFrame(muteDrums, muteBass, muteVocal)
                shiftPending()
            }
        }
    }

    /** FIFO에서 최대 [count] 샘플을 [dest]의 [destOffset]부터 기록. 실제 읽은 수 반환 */
    fun read(dest: FloatArray, destOffset: Int, count: Int): Int {
        val toRead = minOf(count, fifoSize)
        for (i in 0 until toRead) {
            dest[destOffset + i] = outBuf[(fifoHead + i) % outBuf.size]
        }
        fifoHead = (fifoHead + toRead) % outBuf.size
        fifoSize -= toRead
        return toRead
    }

    /**
     * 새로 만든 인스턴스와 동일한 상태로 되돌린다(시크 시 제자리 리셋용).
     * magHist를 반드시 비워야 한다 — histPos/histFill은 인스턴스 단위인데 증가는
     * 채널마다 일어나서(스테레오는 프레임당 2), 워밍업 중 medianOverHist가 해당 채널이
     * 아직 쓰지 않은 슬롯까지 읽는다. 잔여값이 남으면 새 인스턴스(0f)와 출력이 달라진다.
     */
    fun reset() {
        pendingLen = 0
        histFill = 0
        histPos = 0
        magHist.forEach { ch -> ch.forEach { it.fill(0f) } }
        vocalMagHist.forEach { it.fill(0f) }
        vocalPerc.fill(0f)
        vocalHistPos = 0
        vocalHistFill = 0
        olaTail.forEach { it.fill(0f) }
        fifoHead = 0; fifoSize = 0
        lpState = 0f
    }

    // ---------- 내부 ----------

    private fun shiftPending() {
        val keep = n * chCount - hop * chCount
        System.arraycopy(pending, hop * chCount, pending, 0, keep)
        pendingLen = keep
    }

    private fun processFrame(muteDrums: Boolean, muteBass: Boolean, muteVocal: Boolean) {
        val bassF0 = if (muteBass) detectBassF0() else -1f

        for (ch in 0 until chCount) {
            val re = chRe[ch]
            val im = chIm[ch]
            // 창 적용 + FFT
            for (i in 0 until n) {
                re[i] = pending[i * chCount + ch] * window[i]
                im[i] = 0f
            }
            fft.run(re, im, inverse = false)
        }

        // 드럼 억제 전에 mid 타악 비율을 잡는다. 드럼 hist를 쓰면 보컬이 타악으로 오인된다.
        if (muteVocal && chCount == 2) updateVocalPerc()

        for (ch in 0 until chCount) {
            val re = chRe[ch]
            val im = chIm[ch]
            if (muteDrums) applyPercussiveSuppression(ch, re, im)
            if (muteBass && bassF0 > 0f) applyBassNotch(bassF0, re, im)
        }

        // 보컬(중앙) 마스킹은 L/R 스펙트럼을 함께 봐야 하므로 채널 루프 밖에서 적용
        if (muteVocal && chCount == 2) applyCenterSuppression()

        for (ch in 0 until chCount) {
            val re = chRe[ch]
            val im = chIm[ch]
            fft.run(re, im, inverse = true)
            System.arraycopy(re, 0, specCh[ch], 0, n)
        }

        // OLA: 합성창 적산 후 앞쪽 hop만큼 방출, 꼬리는 다음 프레임으로 이월
        for (i in 0 until hop) {
            for (ch in 0 until chCount) {
                ilace[i * chCount + ch] =
                    olaTail[ch][i] + specCh[ch][i] * window[i]
            }
        }
        appendOut(ilace, 0, hop * chCount)
        for (ch in 0 until chCount) {
            val tail = olaTail[ch]
            val sp = specCh[ch]
            for (j in 0 until n - hop) {
                tail[j] = tail[j + hop] + sp[j + hop] * window[j + hop]
            }
            java.util.Arrays.fill(tail, n - hop, n, 0f)
        }
    }

    /**
     * 패닝 인덱스 기반 중앙 성분(보컬) 억제.
     * 빈 단위 정규화 상호상관 sim = 2·Re(X_L·X_R*) / (|X_L|²+|X_R|²) ∈ [-1,1]이
     * 1에 가까울수록(=진폭·위상이 같은 중앙 패닝) 강하게 감쇠한다.
     * 시간영역 L-R 상쇄 대비: 스테레오 이미지가 보존되고, 위상이 어긋난
     * 사이드 성분(리버브·스테레오 악기)은 sim이 낮아 건드리지 않는다.
     *
     * 전대역을 같은 깊이로 깎지 않는다.
     *  - 200–4kHz(포먼트): 기존과 같은 최대 감쇠
     *  - 타악 비율이 높은 빈(가운데 스네어): 보컬로 보지 않음
     *  - 80Hz 미만 서브: 항상 보존
     *  - 80–200Hz: 포먼트에 화성 중앙 성분이 있을 때만 가슴 저역으로 보고 감쇠
     *  - 4kHz 이상: 심벌·에어 보존을 위해 감쇠를 줄임
     */
    private fun applyCenterSuppression() {
        val s = vocalStrength.coerceIn(0f, 1f)
        val simThr = CENTER_THR_SOFT + (CENTER_THR_HARD - CENTER_THR_SOFT) * s
        val depthDb = CENTER_DEPTH_SOFT_DB + (CENTER_DEPTH_HARD_DB - CENTER_DEPTH_SOFT_DB) * s
        val maxSuppress = 1f - Math.pow(10.0, -depthDb / 20.0).toFloat() // dB → 선형 배율

        val reL = chRe[0]; val imL = chIm[0]
        val reR = chRe[1]; val imR = chIm[1]
        val half = n / 2
        val voicePresent = detectVoicePresence(simThr)
        val binHz = sampleRate.toFloat() / n

        for (j in 1..half) {
            val crossRe = reL[j] * reR[j] + imL[j] * imR[j]
            val pwr = reL[j] * reL[j] + imL[j] * imL[j] + reR[j] * reR[j] + imR[j] * imR[j]
            val sim = 2f * crossRe / (pwr + 1e-12f)
            if (sim <= simThr) continue
            val t = (sim - simThr) / (1f - simThr)
            val w = vocalBandWeight(j * binHz, voicePresent)
            val vocalFrac = t * t * w * percProtect(vocalPerc[j])
            if (vocalFrac < 1e-6f) continue
            val keep = 1f - maxSuppress * vocalFrac
            reL[j] *= keep; imL[j] *= keep
            reR[j] *= keep; imR[j] *= keep
            if (j < half) {
                reL[n - j] *= keep; imL[n - j] *= keep
                reR[n - j] *= keep; imR[n - j] *= keep
            }
        }
    }

    /**
     * 전체 에너지 대비 포먼트의 (중앙이면서 화성적) 에너지.
     * 분모를 포먼트만으로 두면 저역 순음의 스펙트럼 누설이 목소리로 오인된다.
     * 가운데 스네어는 타악이라 num이 거의 0이다.
     */
    private fun detectVoicePresence(simThr: Float): Boolean {
        val reL = chRe[0]; val imL = chIm[0]
        val reR = chRe[1]; val imR = chIm[1]
        val half = n / 2
        var num = 0f
        var den = 0f
        for (j in 1..half) {
            val pwr = reL[j] * reL[j] + imL[j] * imL[j] + reR[j] * reR[j] + imR[j] * imR[j]
            if (pwr < 1e-12f) continue
            den += pwr
            if (j !in formantLoBin..formantHiBin) continue
            val crossRe = reL[j] * reR[j] + imL[j] * imR[j]
            val sim = 2f * crossRe / (pwr + 1e-12f)
            val simRamp = ((sim - simThr) / (1f - simThr)).coerceAtLeast(0f)
            num += pwr * simRamp * percProtect(vocalPerc[j])
        }
        return den > 1e-6f && num / den > VOICE_PRESENT_THR
    }

    /** 대역별 보컬 감쇠 가중. 0=안 깎음, 1=강도 슬라이더의 최대 깊이. */
    private fun vocalBandWeight(hz: Float, voicePresent: Boolean): Float {
        if (hz < SUB_KEEP_HZ) return 0f
        if (hz < FORMANT_LO_HZ) {
            if (!voicePresent) return 0f
            val t = (hz - SUB_KEEP_HZ) / (FORMANT_LO_HZ - SUB_KEEP_HZ)
            return CHEST_WEIGHT_LO + (1f - CHEST_WEIGHT_LO) * t
        }
        if (hz <= FORMANT_HI_HZ) return 1f
        if (hz <= AIR_HZ) {
            val t = (hz - FORMANT_HI_HZ) / (AIR_HZ - FORMANT_HI_HZ)
            return 1f - t * (1f - AIR_EDGE_WEIGHT)
        }
        return AIR_WEIGHT
    }

    /**
     * 확실한 타악만 보컬 감쇠에서 빼 준다. 중간 값은 보컬을 깎는다.
     * 드럼 hist의 부풀려진 perc를 곱하면 포먼트까지 통과한다.
     */
    private fun percProtect(perc: Float): Float {
        if (perc <= PERC_PROTECT_LO) return 1f
        if (perc >= PERC_PROTECT_HI) return 0f
        val t = (perc - PERC_PROTECT_LO) / (PERC_PROTECT_HI - PERC_PROTECT_LO)
        return 1f - t * t
    }

    /** mid 스펙트럼으로 프레임당 한 번 타악 비율을 갱신한다. */
    private fun updateVocalPerc() {
        val half = n / 2
        for (j in 0..half) {
            val l = kotlin.math.hypot(chRe[0][j].toDouble(), chIm[0][j].toDouble()).toFloat()
            val r = kotlin.math.hypot(chRe[1][j].toDouble(), chIm[1][j].toDouble()).toFloat()
            vocalMags[j] = 0.5f * (l + r)
        }
        medianFreq(vocalMags, vocalMedV, scratch)
        System.arraycopy(vocalMags, 0, vocalMagHist[vocalHistPos], 0, bins)
        vocalHistPos = (vocalHistPos + 1) % histDepth
        if (vocalHistFill < histDepth) vocalHistFill++

        val cnt = vocalHistFill
        for (j in 0..half) {
            val h = medianVocalHist(j, cnt)
            val p = vocalMedV[j]
            vocalPerc[j] = (p * p) / (h * h + p * p + 1e-9f)
        }
    }

    private fun medianVocalHist(bin: Int, cnt: Int): Float {
        if (cnt == 1) {
            val idx = if (vocalHistPos == 0) histDepth - 1 else vocalHistPos - 1
            return vocalMagHist[idx][bin]
        }
        for (t in 0 until cnt) histScratch[t] = vocalMagHist[t][bin]
        for (a in 1 until cnt) {
            val v = histScratch[a]
            var b = a - 1
            while (b >= 0 && histScratch[b] > v) {
                histScratch[b + 1] = histScratch[b]; b--
            }
            histScratch[b + 1] = v
        }
        return histScratch[cnt / 2]
    }

    /** 주파수축 중간값(타악 추정) 대비 시간축 중간값(화성 추정) 소프트 마스크로 타악 억제 */
    private fun applyPercussiveSuppression(ch: Int, re: FloatArray, im: FloatArray) {
        val half = n / 2
        for (j in 0..half) {
            mags[j] = kotlin.math.hypot(re[j].toDouble(), im[j].toDouble()).toFloat()
        }
        medianFreq(mags, medV, scratch)
        val cur = magHist[ch][histPos]
        System.arraycopy(mags, 0, cur, 0, bins)
        histPos = (histPos + 1) % histDepth
        if (histFill < histDepth) histFill++

        for (j in 0..half) {
            val h = medianOverHist(ch, j)
            val p = medV[j]
            val perc = (p * p) / (h * h + p * p + 1e-9f)
            val keep = 1f - (perc * perc).coerceIn(0f, 1f)
            re[j] *= keep
            im[j] *= keep
            if (j in 1 until half) {
                re[n - j] *= keep
                im[n - j] *= keep
            }
        }
    }

    private fun medianOverHist(ch: Int, bin: Int): Float {
        val cnt = histFill
        if (cnt == 1) {
            val idx = if (histPos == 0) histDepth - 1 else histPos - 1
            return magHist[ch][idx][bin]
        }
        for (t in 0 until cnt) histScratch[t] = magHist[ch][t][bin]
        for (a in 1 until cnt) {
            val v = histScratch[a]
            var b = a - 1
            while (b >= 0 && histScratch[b] > v) {
                histScratch[b + 1] = histScratch[b]; b--
            }
            histScratch[b + 1] = v
        }
        return histScratch[cnt / 2]
    }

    /** 베이스 f0와 그 배음들을 가우시안 노치로 감쇠 */
    private fun applyBassNotch(f0: Float, re: FloatArray, im: FloatArray) {
        val binHz = sampleRate.toFloat() / n
        val f0Bin = f0 / binHz
        val maxHarm = ((LOW_CUTOFF_HZ / f0).toInt()).coerceAtMost(8)
        var k = 1
        while (k <= maxHarm) {
            val center = f0Bin * k
            val sigma = (center * 0.35f).coerceAtLeast(1.2f)
            val lo = (center - 2.5f * sigma).toInt().coerceAtLeast(1)
            val hi = (center + 2.5f * sigma).toInt().coerceAtMost(n / 2)
            for (j in lo..hi) {
                val d = j - center
                val g = NOTCH_GAIN * kotlin.math.exp(-(d * d) / (2f * sigma * sigma))
                val keep = 1f - g
                re[j] *= keep; im[j] *= keep
                if (j < n / 2) {
                    re[n - j] *= keep; im[n - j] *= keep
                }
            }
            k++
        }
    }

    /** 저역 통과 후 자기상관으로 베이스 f0 검출 */
    private fun detectBassF0(): Float {
        var e = 0f
        for (i in 0 until n) {
            val x = if (chCount == 1) pending[i]
            else (pending[i * 2] + pending[i * 2 + 1]) * 0.5f
            lpState += LP_COEF * (x - lpState)
            detBuf[i] = lpState
            e += lpState * lpState
        }
        if (e < 1e-6f * n) return -1f

        val minLag = (sampleRate / F0_MAX_HZ).toInt()
        val maxLag = (sampleRate / F0_MIN_HZ).toInt().coerceAtMost(n / 2)
        var bestLag = -1
        var bestVal = 0f
        var lag = minLag
        while (lag <= maxLag) {
            var sum = 0f
            for (i in 0 until n - lag) sum += detBuf[i] * detBuf[i + lag]
            sum /= (n - lag)
            if (sum > bestVal) {
                bestVal = sum
                bestLag = lag
            }
            lag++
        }
        val energy = e / n
        if (bestLag < 0 || bestVal < energy * CONFIDENCE_THR) return -1f
        return sampleRate.toFloat() / bestLag
    }

    private fun ensureOutCapacity(need: Int) {
        while (need > outBuf.size) {
            val bigger = FloatArray(outBuf.size * 2)
            for (i in 0 until fifoSize) {
                bigger[i] = outBuf[(fifoHead + i) % outBuf.size]
            }
            outBuf = bigger
            fifoHead = 0
        }
    }

    private fun appendOut(src: FloatArray, offset: Int, count: Int) {
        ensureOutCapacity(fifoSize + count)
        for (i in 0 until count) {
            outBuf[(fifoHead + fifoSize + i) % outBuf.size] = src[offset + i]
        }
        fifoSize += count
    }

    companion object {
        const val BLOCK = 1024
        const val HOP = 512
        private const val MEDIAN_FREQ = 17
        private const val MEDIAN_TIME = 9
        private const val F0_MIN_HZ = 41f
        private const val F0_MAX_HZ = 260f
        private const val LOW_CUTOFF_HZ = 320f
        private const val CONFIDENCE_THR = 0.30f
        private const val NOTCH_GAIN = 0.85f

        /** 이 주파수 미만은 보컬 마스크가 절대 안 건드린다 (서브/킥) */
        private const val SUB_KEEP_HZ = 80f
        /** 포먼트(최대 감쇠) 하한. 여기까지는 가슴 저역으로 보고 게이트한다 */
        private const val FORMANT_LO_HZ = 200f
        private const val FORMANT_HI_HZ = 4000f
        /** 이 주파수 이상부터는 심벌·에어로 보고 감쇠를 줄인다 */
        private const val AIR_HZ = 8000f
        private const val AIR_EDGE_WEIGHT = 0.45f
        private const val AIR_WEIGHT = 0.30f
        /** 목소리 존재 시 80Hz에서의 가슴 저역 감쇠 비율 (200Hz에서 1.0으로 상승) */
        private const val CHEST_WEIGHT_LO = 0.60f
        /** 포먼트 대역에서 중앙+화성 에너지 비율이 이 값 넘으면 가슴 저역을 깎는다 */
        private const val VOICE_PRESENT_THR = 0.22f
        /** 이 타악 비율 이하는 보컬로 보고 깎는다. 이상만 스네어 보호 */
        private const val PERC_PROTECT_LO = 0.62f
        private const val PERC_PROTECT_HI = 0.90f
        /** 감쇠 램프 시작 유사도: 강도 0(부드럽게) ~ 1(강하게) */
        private const val CENTER_THR_SOFT = 0.8f
        private const val CENTER_THR_HARD = 0.45f
        /** 완전 중앙(sim=1)에서의 최대 감쇠 깊이(dB): 강도 0 ~ 1 */
        private const val CENTER_DEPTH_SOFT_DB = 12.0
        private const val CENTER_DEPTH_HARD_DB = 40.0

        /** ~280Hz 1차 저역통과 계수 (44.1k 기준) */
        private val LP_COEF = 0.04f

        private fun medianFreq(mags: FloatArray, out: FloatArray, scratch: FloatArray) {
            val bins = out.size
            val halfK = MEDIAN_FREQ / 2
            for (j in 0 until bins) {
                var cnt = 0
                for (d in -halfK..halfK) {
                    val idx = j + d
                    if (idx in 0 until bins) {
                        scratch[cnt++] = mags[idx]
                    }
                }
                // 부분 정렬 없이 단순 정렬 (커널 작음)
                for (a in 1 until cnt) {
                    val v = scratch[a]
                    var b = a - 1
                    while (b >= 0 && scratch[b] > v) {
                        scratch[b + 1] = scratch[b]; b--
                    }
                    scratch[b + 1] = v
                }
                out[j] = scratch[cnt / 2]
            }
        }
    }
}
