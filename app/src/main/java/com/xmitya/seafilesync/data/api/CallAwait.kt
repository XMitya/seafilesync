package com.xmitya.seafilesync.data.api

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs a call so that cancelling the coroutine cancels the request.
 *
 * `withContext(IO) { execute() }` looks equivalent but is not: it blocks a thread inside a call
 * that cancellation cannot interrupt, so stopping a sync would only take effect once the current
 * block had finished transferring. With a 4 MiB block on a slow connection that is a long time to
 * keep writing into a folder the user just switched off.
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }

    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            // A cancelled call also reports failure; resuming then would overwrite the
            // cancellation with a misleading IOException.
            if (continuation.isCancelled) return
            continuation.resumeWithException(e)
        }
    })
}
