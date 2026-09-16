package com.xmitya.seafilesync.data.api

import java.io.IOException

/**
 * Failures a caller can act on differently. Everything else stays an [IOException] and is
 * treated as "retry later".
 *
 * Seahub and the fileserver both define custom status codes in the 44x range and they do not
 * agree: 440 means "library password required" to seahub and "bad filename" to the fileserver.
 * The two are therefore mapped separately, by [fromSeahub] and [fromFileServer].
 */
sealed class SeafileException(
    message: String,
) : IOException(message) {

    /** Credentials rejected at login. */
    class InvalidCredentials(
        message: String,
    ) : SeafileException(message)

    /** Login needs a one-time code; retry with [SeafileApi.login]'s `otp`. */
    class TwoFactorRequired : SeafileException("Two-factor code required")

    /** The stored token is no longer accepted. The account has to log in again. */
    class TokenRejected : SeafileException("Authentication token rejected")

    /**
     * An administrator remotely wiped this device. Local state must be deleted rather than
     * re-synced, and the wipe acknowledged.
     */
    class DeviceWiped : SeafileException("Device was remotely wiped")

    /** The account may not perform this operation on this library. */
    class PermissionDenied(
        message: String,
    ) : SeafileException(message)

    class LibraryNotFound(
        message: String,
    ) : SeafileException(message)

    class LibraryDeleted : SeafileException("Library was deleted")

    class LibraryCorrupted : SeafileException("Library is corrupted on the server")

    /** The library is encrypted and its password has not been supplied yet. */
    class PasswordRequired : SeafileException("Library password required")

    class OutOfQuota : SeafileException("Out of quota")

    class FileTooLarge : SeafileException("File is too large for this server")

    /**
     * The server refused a commit because blocks it references were never uploaded. Recoverable:
     * push the missing blocks and retry.
     */
    class BlocksMissing(
        val body: String,
    ) : SeafileException("Server is missing blocks: $body")

    /** Garbage collection ran while the push was in flight. Retrying is the intended response. */
    class GarbageCollectionConflict : SeafileException("Garbage collection conflict, retry")

    class RateLimited : SeafileException("Rate limited by the server")

    class ServerError(
        val code: Int,
        message: String,
    ) : SeafileException("Server error $code: $message")

    class Unexpected(
        val code: Int,
        message: String,
    ) : SeafileException("Unexpected response $code: $message")

    companion object {

        /** Codes as seahub defines them, for everything under /api2 and /api/v2.1. */
        fun fromSeahub(code: Int, body: String, wiped: Boolean): SeafileException = when {
            code == 401 && wiped -> DeviceWiped()
            code == 401 -> TokenRejected()
            code == 403 -> PermissionDenied(body)
            code == 404 -> LibraryNotFound(body)
            code == 429 -> RateLimited()
            code == 440 || code == 441 -> PasswordRequired()
            code == 443 -> OutOfQuota()
            code >= 500 -> ServerError(code, body)
            else -> Unexpected(code, body)
        }

        /** Codes as the Go fileserver defines them, for everything under /seafhttp. */
        fun fromFileServer(code: Int, body: String): SeafileException = when (code) {
            401, 403 -> PermissionDenied(body)
            409 -> GarbageCollectionConflict()
            429 -> RateLimited()
            442 -> FileTooLarge()
            443 -> OutOfQuota()
            444 -> LibraryDeleted()
            445 -> LibraryCorrupted()
            446 -> BlocksMissing(body)
            in 500..599 -> ServerError(code, body)
            else -> Unexpected(code, body)
        }
    }
}
