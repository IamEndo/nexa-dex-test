package org.nexadex.core.error

/**
 * Result type for DEX operations. Wraps success value or DexError.
 */
sealed class DexResult<out T> {
    data class Success<T>(val value: T) : DexResult<T>()
    data class Failure(val error: DexError) : DexResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }

    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw error
    }

    fun errorOrNull(): DexError? = when (this) {
        is Success -> null
        is Failure -> error
    }

    inline fun <R> map(transform: (T) -> R): DexResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    inline fun <R> flatMap(transform: (T) -> DexResult<R>): DexResult<R> = when (this) {
        is Success -> transform(value)
        is Failure -> this
    }

    inline fun onSuccess(action: (T) -> Unit): DexResult<T> {
        if (this is Success) action(value)
        return this
    }

    inline fun onFailure(action: (DexError) -> Unit): DexResult<T> {
        if (this is Failure) action(error)
        return this
    }

    inline fun getOrElse(onError: (DexError) -> @UnsafeVariance T): T = when (this) {
        is Success -> value
        is Failure -> onError(error)
    }

    companion object {
        fun <T> success(value: T): DexResult<T> = Success(value)
        fun failure(error: DexError): DexResult<Nothing> = Failure(error)
    }
}
