package com.echolog.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AudioResampler] 单元测试（QA：用户需求 3 采样率可配，ASR 固定 16k 重采样）。
 *
 * 覆盖：同速率直通、降采样长度减半、升采样长度翻倍、端点保真、空输入、
 * 非法采样率抛参。线性插值对语音足够，重点验证长度与时间轴正确性。
 */
class AudioResamplerTest {

    @Test(expected = IllegalArgumentException::class)
    fun `零或负采样率抛异常`() {
        AudioResampler.resample(floatArrayOf(0f, 1f), 0, 16000)
    }

    @Test
    fun `空输入返回空数组`() {
        assertEquals(0, AudioResampler.resample(floatArrayOf(), 8000, 16000).size)
    }

    @Test
    fun `同采样率原样返回（同一数组引用）`() {
        val input = floatArrayOf(0.1f, -0.2f, 0.3f)
        val out = AudioResampler.resample(input, 16000, 16000)
        // 同速率应原样返回（内容一致）
        assertTrue(out.contentEquals(input))
    }

    @Test
    fun `降采样长度按比例缩短`() {
        // 16000 → 8000 应刚好减半
        val input = FloatArray(1600) { it / 1600f }
        val out = AudioResampler.resample(input, 16000, 8000)
        assertEquals(800, out.size)
    }

    @Test
    fun `升采样长度按比例拉长`() {
        // 8000 → 48000 应为 6 倍
        val input = FloatArray(100) { it / 100f }
        val out = AudioResampler.resample(input, 8000, 48000)
        assertEquals(600, out.size)
    }

    @Test
    fun `端点保真：首末样本接近原值`() {
        // 恒定信号应保持恒定（线性插值在常数上恒等）
        val input = FloatArray(10) { 0.5f }
        val out = AudioResampler.resample(input, 44100, 16000)
        assertTrue("降采样恒定信号应仍接近 0.5", out.all { kotlin.math.abs(it - 0.5f) < 1e-5f })

        // 斜坡信号：端点不应越界，且首/末接近原首/末
        val ramp = FloatArray(20) { it / 19f }
        val rampOut = AudioResampler.resample(ramp, 8000, 16000)
        assertEquals(0f, rampOut.first(), 1e-5f)
        assertEquals(1f, rampOut.last(), 1e-5f)
    }

    @Test
    fun `插值输出不超过输入包络`() {
        // 在 [0,1] 之间的输入，线性插值不会超出端点范围（无超调）
        val input = floatArrayOf(0f, 1f, 0f, 1f, 0f)
        val out = AudioResampler.resample(input, 16000, 44100)
        assertTrue("插值不应超调", out.all { it in -1e-6f..1f + 1e-6f })
    }
}
