package com.echolog.app.service.vad

import com.echolog.app.core.common.Constants
import com.echolog.app.domain.model.SensitivityLevel
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 能量 VAD 实现（架构 1.6/2.3）。
 *
 * 算法：
 * 1. 每帧计算 RMS 能量（dBFS）；
 * 2. 自适应噪声门（仅未激活态适配）：能量低于门限时快速下修（0.8/0.2），
 *    高于时缓慢上浮（0.995/0.005）——适应办公室/街道/静室差异，无需用户校准；
 *    已激活（voiceActive）期间冻结噪声底，持续人声不参与噪声统计（FR-003）；
 * 3. 判定门限 = 噪声底 + 灵敏度余量（低 14 / 中 10 / 高 6 dB，FR-204：低灵敏度 → 更响声音才开录）；
 * 4. 滞回：连续 [TRIGGER_FRAMES] 帧（100ms）超阈才判 ONSET（防键盘声/咳嗽误触发）；
 *    连续静音达到断段帧数（默认 200 帧 = 5s，FR-003）才判 OFFSET。
 *
 * 线程约定：仅 [com.echolog.app.service.RecordingService] 的音频线程调用，非线程安全。
 */
class EnergyVadEngine : VadEngine {

    private var noiseFloorDb: Float = DEFAULT_NOISE_FLOOR_DB
    private var marginDb: Float = SensitivityLevel.MEDIUM.marginDb
    private var framesForSilence: Int = defaultSilenceFrames()

    private var voiceActive: Boolean = false
    private var aboveThresholdFrames: Int = 0
    private var silentFrames: Int = 0

    /** 切换灵敏度档位（设置页 FR-204）。 */
    fun setSensitivity(level: SensitivityLevel) {
        marginDb = level.marginDb
    }

    /** 设置静音断段时长（FR-003，3/5/10s）。 */
    fun setSilenceDurationMs(durationMs: Long) {
        framesForSilence = (durationMs / Constants.FRAME_INTERVAL_MS)
            .toInt()
            .coerceAtLeast(1)
    }

    override fun init(noiseFloorDb: Float) {
        this.noiseFloorDb = noiseFloorDb
        reset()
    }

    override fun reset() {
        voiceActive = false
        aboveThresholdFrames = 0
        silentFrames = 0
    }

    override fun processFrame(frame: ShortArray): VadEvent {
        val energyDb = frameEnergyDb(frame)

        if (!voiceActive) {
            // 未激活态才适配噪声底（快下慢上）：安静段才是噪声样本
            adaptNoiseFloor(energyDb)
            val threshold = noiseFloorDb + marginDb
            val isVoice = energyDb > threshold
            aboveThresholdFrames = if (isVoice) aboveThresholdFrames + 1 else 0
            return if (aboveThresholdFrames >= TRIGGER_FRAMES) {
                voiceActive = true
                silentFrames = 0
                VadEvent.VOICE_ONSET
            } else {
                VadEvent.SILENT
            }
        }

        // 已激活：冻结噪声底，门限保持进入语音时的水平。
        // 持续人声不是噪声样本——若此期间继续慢上抬门限，长段语音会被误判为
        // 静音而断段（BUG-17，违反 FR-003「仅连续静音才断段」）。
        val threshold = noiseFloorDb + marginDb
        val isVoice = energyDb > threshold
        if (isVoice) {
            silentFrames = 0
            return VadEvent.VOICE
        }
        silentFrames++
        return if (silentFrames >= framesForSilence) {
            voiceActive = false
            aboveThresholdFrames = 0
            VadEvent.VOICE_OFFSET
        } else {
            VadEvent.VOICE
        }
    }

    /** 当前判定门限（dBFS，调试与测试断言用）。 */
    fun currentThresholdDb(): Float = noiseFloorDb + marginDb

    private fun adaptNoiseFloor(energyDb: Float) {
        noiseFloorDb = if (energyDb < noiseFloorDb) {
            // 安静环境快速下修，跟上关门/空调启停等场景切换
            noiseFloorDb * FAST_DOWN + energyDb * (1f - FAST_DOWN)
        } else {
            // 噪声底缓慢上浮，避免被持续语音抬高门限
            noiseFloorDb * SLOW_UP + energyDb * (1f - SLOW_UP)
        }
    }

    private fun frameEnergyDb(frame: ShortArray): Float {
        if (frame.isEmpty()) return MIN_ENERGY_DB
        var sumSquares = 0.0
        for (sample in frame) {
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
        }
        val rms = sqrt(sumSquares / frame.size)
        return if (rms <= RMS_EPSILON) {
            MIN_ENERGY_DB
        } else {
            (20.0 * log10(rms)).toFloat()
        }
    }

    companion object {
        /** 初始/兜底噪声底（dBFS），约 -55dB。 */
        const val DEFAULT_NOISE_FLOOR_DB = -55f

        /** 能量下限（数字静音）。 */
        const val MIN_ENERGY_DB = -100f

        private const val RMS_EPSILON = 1e-10

        /** 触发所需连续超阈帧数：100ms / 25ms = 4 帧（架构 1.6）。 */
        val TRIGGER_FRAMES: Int =
            (Constants.TRIGGER_VOICE_MS / Constants.FRAME_INTERVAL_MS).toInt().coerceAtLeast(1)

        private const val FAST_DOWN = 0.8f
        private const val SLOW_UP = 0.995f

        private fun defaultSilenceFrames(): Int =
            (Constants.DEFAULT_SILENCE_MS / Constants.FRAME_INTERVAL_MS).toInt().coerceAtLeast(1)
    }
}
