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

    /**
     * 模型下载客户端（T04 ModelDownloader）：大文件流式读写，放宽超时。
     *
     * **readTimeout 为什么从 30s 提到 120s**：OkHttp 的 readTimeout 是**单次 socket 读**
     * 超时（不是整个传输的总时长），所以正常下载不会因它失败——数据持续到达时每次
     * `read()` 都在毫秒级返回。真正的风险在**慢速/不稳定网络**：服务端限速、基站切换、
     * TCP 重传（RTO 动辄十几秒）都可能让 socket 出现超过 30s 的数据空档，
     * 此时 `SocketTimeoutException` 会中断一个**已经下到 90%** 的大文件。
     * 239MB / 1.1GB 这量级的传输，30s 静默期判死明显偏紧。
     *
     * 120s 的取舍：既能扛住正常的网络抖动（不误杀进行中的下载），
     * 又能在连接真的死掉时较快失败（不会无限挂起）。
     *
     * **不要**加 `callTimeout`——那是**总时长**上限，会把正常的十几分钟大文件下载
     * 直接掐断。
     *
     * 该客户端只被模型下载使用（[com.echolog.app.ai.model.ModelDownloader] 注入），
     * 放宽读超时不会影响其它业务请求。
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
