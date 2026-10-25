package com.echolog.app.core.common

import javax.inject.Qualifier

/** Dispatchers.IO：Room 写、文件合并、模型下载（架构 7.3）。 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class IoDispatcher

/** Dispatchers.Default：ASR 推理等 CPU 密集调度（架构 7.3）。 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class DefaultDispatcher

/** LLM 推理专用单线程调度器，串行执行防内存峰值（架构 7.3）。 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class LlmDispatcher

/** 应用级 CoroutineScope 限定符（SupervisorJob，随进程存活）。 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
