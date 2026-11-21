package com.melcat.localfirst.core.common

import kotlinx.coroutines.CancellationException

sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Error(
        val message: String,
        val cause: Throwable? = null,
    ) : AppResult<Nothing>
}

inline fun <T> runCatchingAppResult(block: () -> T): AppResult<T> =
    try {
        AppResult.Success(block())
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        AppResult.Error(
            message = t.message ?: t.javaClass.simpleName,
            cause = t,
        )
    }
