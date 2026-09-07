package com.xmitya.seafilesync.data.api

import com.xmitya.seafilesync.data.api.model.AccountInfoDto
import com.xmitya.seafilesync.data.api.model.AuthTokenDto
import com.xmitya.seafilesync.data.api.model.DownloadInfoDto
import com.xmitya.seafilesync.data.api.model.RepoDto
import com.xmitya.seafilesync.data.api.model.ServerInfoDto
import com.xmitya.seafilesync.data.fs.SeafJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * The seahub REST API: everything that is not the block sync protocol. Used for logging in,
 * listing libraries and obtaining the per-library sync tokens that [SeafHttpApi] needs.
 */
class SeafileApi(
    serverUrl: String,
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val baseUrl: HttpUrl = normalize(serverUrl)

    /**
     * Exchanges credentials for an account token.
     *
     * The v2 device fields are all-or-nothing: send platform, deviceId and deviceName together
     * or send none of them. [deviceId] must also match the format the server enforces for the
     * platform, which for android is 1..16 lowercase hex characters (that is Settings.Secure
     * ANDROID_ID). Sending a desktop platform would require a 40-character peer id instead, and
     * an unrecognised platform is rejected outright.
     */
    suspend fun login(
        username: String,
        password: String,
        deviceId: String,
        deviceName: String,
        clientVersion: String,
        platformVersion: String,
        otp: String? = null,
    ): String {
        require(ANDROID_DEVICE_ID.matches(deviceId)) {
            "device_id must be 1..16 lowercase hex characters for the android platform, was '$deviceId'"
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("username", username)
            .addFormDataPart("password", password)
            .addFormDataPart("platform", PLATFORM)
            .addFormDataPart("device_id", deviceId)
            .addFormDataPart("device_name", deviceName.take(DEVICE_NAME_MAX))
            .addFormDataPart("client_version", clientVersion)
            .addFormDataPart("platform_version", platformVersion)
            .build()

        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("api2/auth-token/").build())
            .post(body)
            .apply { otp?.let { header("X-Seafile-OTP", it) } }
            .build()

        return withContext(io) {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw loginFailure(response, text)
                SeafJson.parser.decodeFromString<AuthTokenDto>(text).token
            }
        }
    }

    suspend fun serverInfo(): ServerInfoDto = get("api2/server-info/")

    suspend fun accountInfo(token: String): AccountInfoDto = get("api2/account/info/", token)

    suspend fun repos(token: String): List<RepoDto> = get("api2/repos/", token)

    /** Also the handshake that yields the sync token and encryption parameters for a library. */
    suspend fun downloadInfo(token: String, repoId: String): DownloadInfoDto =
        get("api2/repos/$repoId/download-info/", token)

    private suspend inline fun <reified T> get(path: String, token: String? = null): T {
        val text = getText(path, token)
        return SeafJson.parser.decodeFromString(text)
    }

    suspend fun getText(path: String, token: String? = null): String {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments(path).build())
            .apply { token?.let { header("Authorization", "Token $it") } }
            .build()
        return withContext(io) {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw SeafileException.fromSeahub(response.code, errorMessage(text), response.isWiped())
                }
                text
            }
        }
    }

    /**
     * Acknowledges a remote wipe so the server stops reporting it. Deliberately unauthenticated:
     * by this point the token is already rejected.
     */
    suspend fun acknowledgeWipe(token: String) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("token", token)
            .build()
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("api2/device-wiped/").build())
            .post(body)
            .build()
        withContext(io) { client.newCall(request).execute().close() }
    }

    private fun loginFailure(response: Response, body: String): SeafileException {
        if (response.header("X-Seafile-OTP").equals("required", ignoreCase = true)) {
            return SeafileException.TwoFactorRequired()
        }
        // Login is a DRF serializer, so its errors arrive as non_field_errors rather than the
        // error_msg envelope every other endpoint uses.
        if (response.code == 400) {
            return SeafileException.InvalidCredentials(nonFieldError(body) ?: body)
        }
        return SeafileException.fromSeahub(response.code, errorMessage(body), response.isWiped())
    }

    private fun Response.isWiped(): Boolean = header("X-Seafile-Wiped").equals("true", ignoreCase = true)

    private fun nonFieldError(body: String): String? = runCatching {
        SeafJson.parser.parseToJsonElement(body).jsonObject["non_field_errors"]
            ?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
    }.getOrNull()

    private fun errorMessage(body: String): String = runCatching {
        SeafJson.parser.parseToJsonElement(body).jsonObject["error_msg"]?.jsonPrimitive?.content
    }.getOrNull() ?: body.take(ERROR_BODY_MAX)

    companion object {
        const val PLATFORM = "android"

        /** Server-side check for android device ids; the 64-bit ANDROID_ID in hex. */
        val ANDROID_DEVICE_ID = Regex("^[a-f0-9]{1,16}$")

        /** The server truncates longer device names anyway. */
        const val DEVICE_NAME_MAX = 40

        private const val ERROR_BODY_MAX = 500

        val JSON = "application/json".toMediaType()

        /** Accepts what a user would type: bare host, missing scheme, trailing slash. */
        fun normalize(serverUrl: String): HttpUrl {
            val trimmed = serverUrl.trim().trimEnd('/')
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }
            return "$withScheme/".toHttpUrl()
        }
    }
}
