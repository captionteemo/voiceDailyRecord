package com.echolog.app.di

import com.echolog.app.core.common.ApplicationScope
import com.echolog.app.core.common.DefaultDispatcher
import com.echolog.app.core.common.IoDispatcher
import com.echolog.app.core.common.LlmDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * 全局依赖模块（架构 2.1）。
 *
 * 提供三类全局单例：
 * 1. 协程调度器限定符实例（线程模型见架构 7.3）；
 * 2. 应用级 [CoroutineScope]（SupervisorJob，随进程存活）；
 * 3. [OkHttpClient]（T04 模型流式下载使用）。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** Room 写、文件合并、模型下载（架构 7.3）。 */
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /** ASR 推理等 CPU 密集调度（架构 7.3）。 */
    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /** LLM 推理：单线程串行执行，防内存峰值与上下文竞争（架构 7.3）。 */
    @Provides
    @LlmDispatcher
    fun provideLlmDispatcher(): CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** 应用级协程作用域：服务/Worker 之外的异步初始化使用。 */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @DefaultDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    /** 模型下载客户端（T04 ModelDownloader）：大文件流式读写，放宽超时。 */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
