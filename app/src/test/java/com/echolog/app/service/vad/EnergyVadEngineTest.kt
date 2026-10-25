package com.echolog.app.service.vad

import com.echolog.app.core.common.Constants
import com.echolog.app.domain.model.SensitivityLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * [EnergyVadEngine] 单元测试（QA：FR-002/003/204 + 架构 1.6）。
 *
 * 覆盖：帧能量计算（经「门限边界行为」精确验证）、自适应噪声门（快下慢上）、
 * 滞回触发（连续 100ms/4 帧）、静音断段（默认 5s=200 帧 / 可配 3s=120 帧）、
 * 灵敏度余量（14/10/6dB）、reset 语义。
 *
 * 输入 PCM 由目标 dBFS 反推样本幅度构造，无需真实音频设备。
 */
class EnergyVadEngineTest {

    // ── 帧能量计算 ──
    // 读取方式：processFrame 内部先自适应噪声门再比较门限。
    // 快下路径 floor' = 0.8*floor + 0.2*E、慢上路径 floor' = 0.995*floor + 0.005*E，
    // 因此经 currentThresholdDb() 反推可精确钉住单帧能量 E（Float 精度内）。
    // 注意：样本幅度取整会引入量化误差，故按「幅度 → 理论 dB」推导期望值。

    @Test
    fun `帧能量按RMS的20log10计算_快下路径读数`() {
        val engine = EnergyVadEngine()
        engine.init(noiseFloorDb = -40f)
        engine.processFrame(frameAtAmplitude(32)) // E≈-60.2dB < floor → 快下 0.8/0.2
        // floor' = 0.8*(-40) + 0.2*E
        val expected = 0.8f * (-40f) + 0.2f * energyDbOf(32)
        assertEquals(expected, engine.noiseFloorDb(), 0.001f)
    }

    @Test
    fun `帧能量按RMS的20log10计算_慢上路径读数`() {
        val engine = EnergyVadEngine()
        engine.init(noiseFloorDb = -40f)
        engine.processFrame(frameAtAmplitude(3277)) // E≈-20dB > floor → 慢上 0.995/0.005
        // floor' = 0.995*(-40) + 0.005*E
        val expected = 0.995f * (-40f) + 0.005f * energyDbOf(3277)
        assertEquals(expected, engine.noiseFloorDb(), 0.001f)
    }

    @Test
    fun `半幅度帧能量约为负6dBFS`() {
        val engine = EnergyVadEngine()
        engine.init(noiseFloorDb = -40f)
        engine.processFrame(frameAtAmplitude(16384)) // 20*log10(0.5) = -6.0206dB
        val expected = 0.995f * (-40f) + 0.005f * energyDbOf(16384)
        assertEquals(-39.8301f, expected, 0.001f) // 期望值自洽
        assertEquals(expected, engine.noiseFloorDb(), 0.001f)
    }

    @Test
    fun `全幅度帧能量接近0dBFS`() {
        val engine = EnergyVadEngine()
        engine.init(noiseFloorDb = -2f)
        engine.processFrame(frameAtAmplitude(32767)) // RMS≈1.0 → ≈0dB
        val expected = 0.995f * (-2f) + 0.005f * energyDbOf(32767)
        assertEquals(-1.9900013f, expected, 0.0001f) // 期望值自洽
        assertEquals(expected, engine.noiseFloorDb(), 0.0001f)
    }

    @Test
    fun `空帧回落到能量下限`() {
        val engine = EnergyVadEngine()
        engine.init(noiseFloorDb = -80f)
        engine.processFrame(ShortArray(0)) // E=MIN_ENERGY_DB=-100 < floor → 快下
        // floor' = 0.8*(-80) + 0.2*(-100) = -84 → 反推 E 恰为 -100dB
        assertEquals(-84f, engine.noiseFloorDb(), 0.001f)
    }

    // ── 门限与灵敏度（架构 1.6：噪声底 + 14/10/6dB 余量；低灵敏度 → 更高门限 → 需更响声音） ──

    @Test
    fun `初始门限等于噪声底加灵敏度余量`() {
        val engine = EnergyVadEngine()
        engine.init(noiseFloorDb = -55f)
        // 默认 MEDIUM = 10dB
        assertEquals(-45f, engine.currentThresholdDb(), 0.001f)

        engine.setSensitivity(SensitivityLevel.LOW)
        assertEquals(-41f, engine.currentThresholdDb(), 0.001f)

        engine.setSensitivity(SensitivityLevel.HIGH)
        assertEquals(-49f, engine.currentThresholdDb(), 0.001f)

        engine.setSensitivity(SensitivityLevel.MEDIUM)
        assertEquals(-45f, engine.currentThresholdDb(), 0.001f)
    }

    // ── 滞回触发：连续 100ms（4 帧）超阈才开录 ──

    @Test
    fun `连续4帧超阈才触发ONSET`() {
        val engine = EnergyVadEngine()
        engine.init(noiseFloorDb = -55f) // 门限 -45dB
        val voice = frameAtDb(-30f) // 高于门限 15dB

        assertEquals(VadEvent.SILENT, engine.processFrame(voice))
        assertEquals(VadEvent.SILENT, engine.processFrame(voice))
        assertEquals(VadEvent.SILENT, engine.processFrame(voice))
        assertEquals(VadEvent.VOICE_ONSET, engine.processFrame(voice))
    }

    @Test
    fun `超阈帧被静音帧打断则重新计数`() {
        val engine = EnergyVadEngine()
        engine.init(noiseFloorDb = -55f)
        val voice = frameAtDb(-30f)
        val silence = frameAtDb(-80f)

        engine.processFrame(voice)
        engine.processFrame(voice)
        engine.processFrame(voice)
        engine.processFrame(silence) // 打断，计数清零
        assertEquals(VadEvent.SILENT, engine.processFrame(voice))
        assertEquals(VadEvent.SILENT, engine.processFrame(voice))
        assertEquals(VadEvent.SILENT, engine.processFrame(voice))
        assertEquals(VadEvent.VOICE_ONSET, engine.processFrame(voice))
    }

    @Test
    fun `ONSET后持续人声每帧报VOICE`() {
        val engine = EnergyVadEngine()
        engine.init(noiseFloorDb = -55f)
        val voice = frameAtDb(-30f)

        repeat(EnergyVadEngine.TRIGGER_FRAMES) { engine.processFrame(voice) }
        // ONSET 之后 20 帧（500ms）持续人声应持续报 VOICE（不得提前断段）
        repeat(20) {
            assertEquals(VadEvent.VOICE, engine.processFrame(voice))
        }
    }

    // ── 静音断段：默认 5s = 200 帧（FR-003） ──

    @Test
    fun `静音达到默认200帧才触发OFFSET`() {
        val engine = EnergyVadEngine()
        engine.init(noiseFloorDb = -55f)
        val voice = frameAtDb(-30f)
        val silence = frameAtDb(-80f)

        repeat(EnergyVadEngine.TRIGGER_FRAMES) { engine.processFrame(voice) }

        val expectedSilenceFrames =
            (Constants.DEFAULT_SILENCE_MS / Constants.FRAME_INTERVAL_MS).toInt()
        assertEquals("默认静音断段应为 5s = 200 帧", 200, expectedSilenceFrames)

        repeat(expectedSilenceFrames - 1) {
            assertEquals("第 ${it + 1} 帧静音不应断段", VadEvent.VOICE, engine.processFrame(silence))
        }
        assertEquals(VadEvent.VOICE_OFFSET, engine.processFrame(silence))
    }

    @Test
    fun `自定义断段时长3秒对应120帧`() {
        val engine = EnergyVadEngine()
        engine.init(noiseFloorDb = -55f)
        engine.setSilenceDurationMs(3000L)
        val voice = frameAtDb(-30f)
        val silence = frameAtDb(-80f)

        repeat(EnergyVadEngine.TRIGGER_FRAMES) { engine.processFrame(voice) }
        repeat(119) { engine.processFrame(silence) }
        assertEquals(VadEvent.VOICE_OFFSET, engine.processFrame(silence))
    }

    @Test
    fun `断段时长下限保护为1帧`() {
        val engine = EnergyVadEngine()
        engine.init(noiseFloorDb = -55f)
        engine.setSilenceDurationMs(0L) // 非法值不得导致零帧/除零，下限 1 帧
        val voice = frameAtDb(-30f)
        val silence = frameAtDb(-80f)

        repeat(EnergyVadEngine.TRIGGER_FRAMES) { engine.processFrame(voice) }
        // 1 帧静音即达到断段帧数下限
        assertEquals(VadEvent.VOICE_OFFSET, engine.processFrame(silence))
    }

    // ── 持续人声不得被误判为静音（FR-003：仅「连续静音」才断段） ──

    @Test
    fun `持续人声20秒不应触发断段`() {
        val engine = EnergyVadEngine()
        engine.init(noiseFloorDb = -55f)
        val voice = frameAtDb(-30f)

        var onsetCount = 0
        var offsetCount = 0
        // 20s @ 25ms/帧 = 800 帧恒定电平「语音」
        repeat(800) {
            when (engine.processFrame(voice)) {
                VadEvent.VOICE_ONSET -> onsetCount++
                VadEvent.VOICE_OFFSET -> offsetCount++
                else -> Unit
            }
        }
        assertEquals("持续人声应只开录一次", 1, onsetCount)
        assertEquals(
            "持续人声不是静音，不应触发 VOICE_OFFSET（断段仅用于连续静音，FR-003）",
            0,
            offsetCount,
        )
    }

    @Test
    fun `断段后同级语音应能再次开录`() {
        val engine = EnergyVadEngine()
        engine.init(noiseFloorDb = -55f)
        val voice = frameAtDb(-30f)
        val silence = frameAtDb(-80f)

        // 第一段：开录 → 静音 5s 断段
        repeat(EnergyVadEngine.TRIGGER_FRAMES) { engine.processFrame(voice) }
        repeat(200) { engine.processFrame(silence) }
        // 同级语音再次出现：4 帧内必须重新 ONSET
        var reOnset = false
        repeat(EnergyVadEngine.TRIGGER_FRAMES) {
            if (engine.processFrame(voice) == VadEvent.VOICE_ONSET) reOnset = true
        }
        assertTrue("断段后同级语音应能再次触发开录", reOnset)
    }

    // ── 自适应噪声门：快下慢上（架构 1.6） ──

    @Test
    fun `安静环境噪声底快速下修`() {
        val engine = EnergyVadEngine()
        engine.init(noiseFloorDb = -40f)
        // frameAtDb(-60) 的幅度取整为 32（32768*10^-3=32.768），实际能量以幅度推导为准
        val quiet = frameAtDb(-60f)
        val actualDb = energyDbOf(32)

        engine.processFrame(quiet)
        // 快下：0.8 旧 + 0.2 新
        assertEquals(0.8f * (-40f) + 0.2f * actualDb, engine.noiseFloorDb(), 0.001f)

        repeat(4) { engine.processFrame(quiet) }
        // 再 4 帧：-40 + (E+40)*(1-0.8^5)
        val expected = -40f + (actualDb - (-40f)) * (1f - 0.8f.pow(5))
        assertEquals(expected, engine.noiseFloorDb(), 0.001f)
    }

    @Test
    fun `reset清空运行态但保留噪声底`() {
        val engine = EnergyVadEngine()
        engine.init(noiseFloorDb = -55f)
        val voice = frameAtDb(-30f)
        repeat(EnergyVadEngine.TRIGGER_FRAMES + 10) { engine.processFrame(voice) }
        // 14 帧人声后噪声底已被慢上路径自适应抬高（门限不再等于初值 -45），
        // 此处验证 reset 的语义：噪声底（门限）保留不变，仅触发计数清零。
        val thresholdBefore = engine.currentThresholdDb()

        engine.reset()
        assertEquals(thresholdBefore, engine.currentThresholdDb(), 0.001f)
        // 触发计数清零，需重新累计 4 帧才 ONSET
        assertEquals(VadEvent.SILENT, engine.processFrame(voice))
        assertEquals(VadEvent.SILENT, engine.processFrame(voice))
        assertEquals(VadEvent.SILENT, engine.processFrame(voice))
        assertEquals(VadEvent.VOICE_ONSET, engine.processFrame(voice))
    }

    companion object {
        /** 构造恒定 RMS ≈ [db] dBFS 的 25ms 帧（400 样本；供触发类测试使用）。 */
        fun frameAtDb(db: Float): ShortArray = frameAtAmplitude(
            (32768.0 * 10.0.pow(db / 20.0)).toInt().coerceIn(1, 32767),
        )

        /** 构造恒定幅度的 25ms 帧（400 样本）。 */
        fun frameAtAmplitude(amplitude: Int): ShortArray =
            ShortArray(Constants.FRAME_SAMPLES) { amplitude.coerceIn(-32768, 32767).toShort() }

        /** 幅度对应的理论能量 dBFS（与引擎公式一致，供期望值推导）。 */
        fun energyDbOf(amplitude: Int): Float {
            val normalized = amplitude.coerceIn(1, 32768) / 32768.0
            return (20.0 * kotlin.math.log10(normalized)).toFloat()
        }
    }
}

/** 当前噪声底读数（门限 - 默认 MEDIUM 余量 10dB；引擎未暴露噪声底，经门限反推）。 */
private fun EnergyVadEngine.noiseFloorDb(): Float =
    currentThresholdDb() - SensitivityLevel.MEDIUM.marginDb
