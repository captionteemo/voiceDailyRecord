package com.echolog.app.core.util

/**
 * 安全日志输出（跨环境可用）。
 *
 * **为什么需要它**：`android.util.Log` 在 JVM 单元测试中未 mock，直接调用会抛
 * `RuntimeException: Method w in android.util.Log not mocked`，从而**掩盖真正的
 * 业务异常**（例如合并失败的真实原因）。
 *
 * 本封装用 [runCatching] 吞掉日志自身的异常，保证：
 * 1. 真机上正常输出到 logcat（便于现场定位问题）；
 * 2. JVM 单测中静默跳过，不影响被测逻辑的断言。
 *
 * 用法：`EchoLog.w(TAG, "消息")` / `EchoLog.e(TAG, "消息", throwable)`。
 */
object EchoLog {

    /** 写 INFO 日志。 */
    fun i(tag: String, message: String) {
        runCatching { android.util.Log.i(tag, message) }
    }

    /** 写 WARN 日志。 */
    fun w(tag: String, message: String) {
        runCatching { android.util.Log.w(tag, message) }
    }

    /** 写 WARN 日志（带异常）。 */
    fun w(tag: String, message: String, throwable: Throwable) {
        runCatching { android.util.Log.w(tag, message, throwable) }
    }

    /** 写 ERROR 日志。 */
    fun e(tag: String, message: String) {
        runCatching { android.util.Log.e(tag, message) }
    }

    /** 写 ERROR 日志（带异常）。 */
    fun e(tag: String, message: String, throwable: Throwable?) {
        runCatching { android.util.Log.e(tag, message, throwable) }
    }
}
