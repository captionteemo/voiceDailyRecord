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
import okhttp3.Protocol
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
     * 模型下载客户端（T04 ModelDownloader）：大文件流式读写，放宽超时 + **强制 HTTP/1.1**。
     *
     * ## 一、为什么强制 HTTP/1.1（**不要删掉这一行**）
     *
     * 真机实测（1.1GB 的 LLM 权重，`model.int8.onnx` 同源 CDN）：
     * 下载到 581,647,329 字节时停滞，5 分半后抛出：
     * ```
     * java.net.SocketException: Software caused connection abort
     *     at okhttp3.internal.http2.Http2Writer.windowUpdate(Http2Writer.kt:253)
     *     at okhttp3.internal.http2.Http2Connection$writeWindowUpdateLater...
     * ```
     * 堆栈直指 **HTTP/2 的流控窗口更新帧**在写 socket 时被中止。
     *
     * 决定性对照实验（同一设备、同一文件、同一 URL）：
     *
     * | 方式 | 结果 |
     * |---|---|
     * | `curl -sL`（默认 **HTTP/2**） | 中途 `connection abort`，失败 |
     * | `curl -sL --http1.1` | `http=200 size=1117320736 speed=42916369 time=26.03s` —— 1.1GB **完整下完，26 秒** |
     *
     * 另外服务端本身没问题：`curl -r 700000000-720000000` 取中后段区间 → `http=206`，
     * 中后段数据随时可取 ⇒ 结论是「ModelScope 的 CDN 在 HTTP/2 下对超大文件长连接
     * 不稳定，HTTP/1.1 稳定」，与本项目的代码逻辑无关。
     *
     * 看到「这是多余的，删掉吧」之前请先读完上面这段：**删掉这一行就会让 1.1GB 的
     * LLM 权重永远下不完**（每次都在几百 MB 处被中止）。
     *
     * ## 二、readTimeout 为什么从 30s 提到 120s
     *
     * OkHttp 的 readTimeout 是**单次 socket 读**超时（不是整个传输的总时长），
     * 所以正常下载不会因它失败——数据持续到达时每次 `read()` 都在毫秒级返回。
     * 真正的风险在**慢速/不稳定网络**：服务端限速、基站切换、TCP 重传（RTO 动辄
     * 十几秒）都可能让 socket 出现超过 30s 的数据空档，此时 `SocketTimeoutException`
     * 会中断一个**已经下到 90%** 的大文件。239MB / 1.1GB 这量级的传输，
     * 30s 静默期判死明显偏紧。
     *
     * 120s 的取舍：既能扛住正常的网络抖动（不误杀进行中的下载），又能在连接真的
     * 死掉时较快失败（不会无限挂起）。
     *
     * **不要**加 `callTimeout`——那是**总时长**上限，会把正常的十几分钟大文件下载
     * 直接掐断。
     *
     * ## 三、retryOnConnectionFailure 与应用层重试**不能互相替代**
     *
     * `retryOnConnectionFailure(true)` 只覆盖「连接建立失败」和部分 IO 异常，
     * **对 HTTP/2 流控 abort 这类协议层错误不自动重试**（OkHttp 无法在协议层
     * 错误上安全地重新发起 Range 续传）。因此 [com.echolog.app.ai.model.ModelDownloader]
     * 里另有**应用层重试**（有限次 + 指数退避 + 断点续传），两者是叠加关系。
     *
     * ## 四、注入点核实结论（不必拆成两个 client）
     *
     * 全项目检索 `OkHttpClient`，本 bean 的**唯一注入点**是
     * `ModelDownloader`（构造参数 `okHttpClient`）；`ModelCatalog.fetchManifest`
     * 只是**函数形参**，当前**没有任何调用点**；测试代码各自新建 client，不吃本 bean。
     * ⇒ 强制 HTTP/1.1 只影响模型下载，**不存在混用风险，无需拆分独立 client**。
     *
     * 将来若接入确实需要 HTTP/2 的用途（如 manifest 拉取），应**新建一个独立的
     * OkHttpClient bean** 注入给它，而不是把本 bean 的协议列表改回去。
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // 强制 HTTP/1.1：HTTP/2 流控会在大文件长连接上被 CDN 中止（详见上方 KDoc）
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()
}
