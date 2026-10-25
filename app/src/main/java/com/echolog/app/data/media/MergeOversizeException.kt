package com.echolog.app.data.media

import java.io.IOException

/**
 * 合并结果超出 WAV（RIFF）格式上限（用户需求 4：全天录音 + 高采样率）。
 *
 * WAV 的 `RIFF` / `data` 长度字段为无符号 32 位，单文件上限约 4GB。
 * 16kHz 单声道全天约 2.76GB 已逼近该上限；44.1kHz/48kHz 下必然超限。
 *
 * 与普通 [IOException] 区分：本异常是**非重试**的软失败——
 * 片段本身完好，只是无法拼成单个合法 WAV 文件。调用方据此降级：
 * 跳过合并、如实回写总时长，转写与总结照常进行。
 */
class MergeOversizeException(
    message: String,
) : IOException(message)
