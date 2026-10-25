package com.echolog.app.ai.llm

import com.echolog.app.core.common.LlmDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import com.echolog.app.ai.model.ModelManager
import com.echolog.app.domain.model.Marker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Qwen 端侧总结器（架构 1.4 决策 2/2.4）：llama.cpp JNI 封装。
 *
 * - GGUF 模型经 [ModelManager] 定位，`System.loadLibrary("llama_jni")` 加载 JNI 桥；
 * - 加载/推理失败不抛给调用方风暴：[isReady] 返回 false，由 `SummarizeDayUseCase`
 *   自动降级 [RuleBasedSummarizer]（E-4001）；
 * - 串行执行于 [LlmDispatcher] 单线程（架构 7.3：防内存峰值与上下文竞争）。
 *
 * 注意：native 封装（`libllama_jni.so`）由 llama.cpp 预编译 track 提供，
 * 未就位时本类所有 native 调用均不可达，[isReady] 恒 false，链路自动走兜底。
 */
@Singleton
class QwenSummarizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
    @LlmDispatcher private val llmDispatcher: CoroutineDispatcher,
) : Summarizer {

    private val loadLock = Any()

    @Volatile
    private var nativeHandle: Long = 0L

    @Volatile
    private var loadAttempted: Boolean = false

    override fun isReady(): Boolean {
        ensureLoaded()
        return nativeHandle != 0L
    }

    override suspend fun summarize(
        transcriptText: String,
        markers: List<Marker>,
    ): com.echolog.app.ai.llm.SummaryDraft =
        withContext(llmDispatcher) {
            if (!isReady()) {
                throw IllegalStateException("LLM native 未就绪")
            }
            val prompt = com.echolog.app.ai.llm.PromptTemplates.fullDay(transcriptText, markers)
            val raw = nativeGenerate(nativeHandle, prompt, MAX_TOKENS)
            com.echolog.app.ai.llm.SummaryJsonParser.parse(raw)
        }

    override fun release() {
        synchronized(loadLock) {
            if (nativeHandle != 0L) {
                runCatching { nativeFree(nativeHandle) }
                nativeHandle = 0L
            }
        }
    }

    /**
     * 惰性加载：模型文件存在才尝试加载 native 桥。
     *
     * BUG-13 修复：`loadAttempted` 仅在**加载成功**后置位。模型未就绪
     * （用户稍后下载）或加载失败时允许后续再次尝试，避免一次失败即永久锁死。
     */
    private fun ensureLoaded() {
        if (nativeHandle != 0L || loadAttempted) return
        synchronized(loadLock) {
            if (nativeHandle != 0L || loadAttempted) return
            // 模型未就绪：不置 loadAttempted，等用户下载完成后自然进入加载
            if (!modelManager.isLlmReady()) return
            runCatching {
                System.loadLibrary("llama_jni")
                val modelPath = modelManager.llmModelDir()
                    .listFiles { file -> file.extension.equals("gguf", ignoreCase = true) }
                    ?.maxByOrNull { it.length() }
                    ?.absolutePath
                    ?: return@runCatching
                nativeHandle = nativeInit(modelPath, THREADS)
            }.onSuccess {
                // 仅加载成功（nativeInit 返回非零句柄）才置位，失败允许重试
                if (nativeHandle != 0L) loadAttempted = true
            }
        }
    }

    private external fun nativeInit(modelPath: String, numThreads: Int): Long

    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String

    private external fun nativeFree(handle: Long)

    private companion object {
        /** 中端机 8 线程的保守取值（架构决策 2：5~8 tok/s 可接受）。 */
        const val THREADS = 4

        /** 500 token 输出约 1.5~2 分钟（NFR-004 < 10min）。 */
        const val MAX_TOKENS = 512
    }
}
