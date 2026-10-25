package com.echolog.app.core.common

/**
 * 统一结果封装（架构 2.1/7.4）。
 *
 * 所有可能失败的跨层操作返回 [EchoResult]：
 * - [Success]：携带数据；
 * - [Failure]：统一携带错误码（见 [Constants] 错误码表）、可读信息与可选异常。
 */
sealed class EchoResult<out T> {

    data class Success<T>(val data: T) : EchoResult<T>()

    data class Failure(
        val code: String,
        val message: String,
        val cause: Throwable? = null,
    ) : EchoResult<Nothing>()

    val isSuccess: Boolean get() = this is Success

    val isFailure: Boolean get() = this is Failure

    /** 成功时取数据，失败返回 null。 */
    fun getOrNull(): T? = (this as? Success)?.data

    /** 失败时取 [Failure]，成功返回 null。 */
    fun failureOrNull(): Failure? = this as? Failure

    /** 成功时映射数据，失败原样透传。 */
    inline fun <R> map(transform: (T) -> R): EchoResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }

    /** 成功时执行副作用，返回自身便于链式调用。 */
    inline fun onSuccess(action: (T) -> Unit): EchoResult<T> {
        if (this is Success) action(data)
        return this
    }

    /** 失败时执行副作用，返回自身便于链式调用。 */
    inline fun onFailure(action: (Failure) -> Unit): EchoResult<T> {
        if (this is Failure) action(this)
        return this
    }

    companion object {
        fun <T> success(data: T): EchoResult<T> = Success(data)

        fun failure(code: String, message: String, cause: Throwable? = null): EchoResult<Nothing> =
            Failure(code, message, cause)
    }
}
