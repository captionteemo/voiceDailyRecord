package com.echolog.app.core.util

/**
 * 线性插值重采样器（用户需求 3：录音采样率可配，ASR 固定 16kHz）。
 *
 * 录音采样率（8k/16k/44.1k/48k）与 ASR 输入（16kHz，架构 1.4）不一致时，
 * 在推理前将 PCM 浮点样本重采样到 16k。线性插值对语音 ASR 足够
 * （SenseVoice 特征提取本身容忍小幅失真）；未做抗混叠滤波，属 MVP 取舍，
 * 高保真档位若检出识别率下降再升级为多相滤波器。
 */
object AudioResampler {

    /**
     * 重采样（纯函数，无 IO）。
     *
     * @param input 输入样本（[-1, 1] 浮点）。
     * @param fromRate 输入采样率（>0）。
     * @param toRate 目标采样率（>0）。
     * @return 目标采样率下的样本；同速率或空输入原样返回。
     */
    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        require(fromRate > 0 && toRate > 0) { "采样率必须为正: from=$fromRate to=$toRate" }
        if (input.isEmpty() || fromRate == toRate) return input
        val ratio = toRate.toDouble() / fromRate.toDouble()
        val outputSize = (input.size * ratio).toInt().coerceAtLeast(1)
        val output = FloatArray(outputSize)
        val lastIndex = input.lastIndex
        for (index in 0 until outputSize) {
            val position = index / ratio
            val left = position.toInt()
            val fraction = (position - left).toFloat()
            val a = input[left.coerceIn(0, lastIndex)]
            val b = input[(left + 1).coerceIn(0, lastIndex)]
            output[index] = a + (b - a) * fraction
        }
        return output
    }
}
