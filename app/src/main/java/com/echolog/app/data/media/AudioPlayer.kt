package com.echolog.app.data.media

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import java.io.IOException

/**
 * 全天合并音频播放器（架构 2.3，P2 增强 FR-202）。
 *
 * 封装 [MediaPlayer]：定位跳转（打点跳转 FR-104）、暂停、释放；
 * API 23+ 支持倍速（[setSpeed]）。
 *
 * 非线程安全：所有方法应在主线程调用。
 */
class AudioPlayer {

    private var player: MediaPlayer? = null

    val isPlaying: Boolean
        get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    /**
     * 播放音频文件。
     *
     * @param path 音频文件绝对路径。
     * @param startMs 起始位置（epoch millis 偏移；0 表示从头）。
     */
    fun play(path: String, startMs: Long = 0L) {
        release()
        val created = MediaPlayer().apply {
            setDataSource(path)
            prepare()
        }
        if (startMs > 0) {
            runCatching { created.seekTo(startMs.toInt()) }
        }
        created.start()
        player = created
    }

    /** 跳转到指定位置（epoch millis 偏移）。 */
    fun seekTo(positionMs: Long) {
        runCatching { player?.seekTo(positionMs.coerceAtLeast(0L).toInt()) }
    }

    /** 当前播放位置（epoch millis 偏移）。 */
    fun currentPositionMs(): Long = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)

    /** 暂停播放。 */
    fun pause() {
        runCatching { if (player?.isPlaying == true) player?.pause() }
    }

    /** 设置倍速（0.5~2.0，API 23+ 生效）。 */
    fun setSpeed(speed: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            player?.let { mediaPlayer ->
                mediaPlayer.playbackParams = PlaybackParams().setSpeed(speed.coerceIn(0.5f, 2.0f))
            }
        }
    }

    /** 释放播放资源。 */
    fun release() {
        player?.let { mediaPlayer ->
            runCatching {
                if (mediaPlayer.isPlaying) mediaPlayer.stop()
            }
            runCatching { mediaPlayer.release() }
        }
        player = null
    }
}
