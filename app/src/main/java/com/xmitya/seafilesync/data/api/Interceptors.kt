package com.xmitya.seafilesync.data.api

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.math.min

/**
 * Identifies the client the way the desktop daemon does. The server records this against the
 * device entry, so a recognisable string is what makes a session identifiable in the web UI.
 */
class UserAgentInterceptor(
    private val userAgent: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(
            chain
                .request()
                .newBuilder()
                .header("User-Agent", userAgent)
                .build(),
        )
}

/**
 * Retries transient failures with exponential backoff.
 *
 * Only connection-level errors and the statuses that mean "not now" are retried. Everything else,
 * including every 4xx that describes a real problem with the request, is passed straight through
 * so the caller sees it immediately.
 *
 * Safe for this API's writes because they are content-addressed: uploading the same block or fs
 * object twice is indistinguishable from uploading it once.
 */
class RetryInterceptor(
    private val maxAttempts: Int = 4,
    private val initialDelayMillis: Long = 500,
    private val maxDelayMillis: Long = 8_000,
    private val sleeper: (Long) -> Unit = Thread::sleep,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        var lastFailure: IOException? = null

        while (attempt < maxAttempts) {
            if (attempt > 0) sleeper(backoffMillis(attempt))
            attempt++

            val response = try {
                chain.proceed(chain.request())
            } catch (interrupted: InterruptedIOException) {
                // A cancelled call must not be retried, or cancellation never takes effect.
                throw interrupted
            } catch (failure: IOException) {
                lastFailure = failure
                continue
            }

            if (attempt >= maxAttempts || !isTransient(response.code)) return response
            response.close()
        }

        throw lastFailure ?: IOException("Giving up after $maxAttempts attempts")
    }

    private fun isTransient(code: Int): Boolean = code == 429 || code in 500..599

    private fun backoffMillis(attempt: Int): Long =
        min(initialDelayMillis shl (attempt - 1), maxDelayMillis)
}
