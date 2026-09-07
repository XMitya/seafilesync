package com.xmitya.seafilesync.data.api

import com.xmitya.seafilesync.data.api.model.CommitDto
import com.xmitya.seafilesync.data.api.model.HeadCommitDto
import com.xmitya.seafilesync.data.fs.SeafJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.InputStream

/**
 * The block sync protocol served by the Go fileserver under /seafhttp. This is what the desktop
 * client speaks, and the reason this app can do delta sync at all rather than re-uploading whole
 * files the way the official mobile app does.
 *
 * Authentication is a per-library sync token from `SeafileApi.downloadInfo`, not the account
 * token. The desktop daemon sends it in both headers, so this does too.
 */
class SeafHttpApi(
    serverUrl: String,
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Optional so the API stays usable from tests without an Android context. */
    private val onRejectedCommit: (String) -> Unit = {},
) {

    private val baseUrl: HttpUrl = SeafileApi.normalize(serverUrl)

    suspend fun permissionCheck(token: String, repoId: String, operation: String) {
        request(token) {
            url(endpoint(repoId, "permission-check").addQueryParameter("op", operation).build())
        }.consume { }
    }

    suspend fun headCommitId(token: String, repoId: String): HeadCommitDto =
        request(token) { url(repo(repoId).addPathSegments("commit/HEAD").build()) }
            .consume { SeafJson.parser.decodeFromString(it.string()) }

    /**
     * Heads for several libraries in one round trip. The desktop client polls with this rather
     * than one request per library, which matters because there is no push channel unless the
     * server runs a notification service.
     */
    suspend fun headCommits(token: String, repoIds: List<String>): Map<String, String> =
        request(token) {
            url(baseUrl.newBuilder().addPathSegments("seafhttp/repo/head-commits-multi").build())
            post(json(SeafJson.parser.encodeToString(repoIds)))
        }.consume { SeafJson.parser.decodeFromString(it.string()) }

    suspend fun commit(token: String, repoId: String, commitId: String): CommitDto =
        request(token) { url(repo(repoId).addPathSegments("commit/$commitId").build()) }
            .consume { SeafJson.parser.decodeFromString(it.string()) }

    suspend fun putCommit(token: String, repoId: String, commit: CommitDto) {
        val body = SeafJson.encoder.encodeToString(commit)
        try {
            request(token) {
                url(repo(repoId).addPathSegments("commit/${commit.commitId}").build())
                put(json(body))
            }.consume { }
        } catch (rejected: SeafileException) {
            // The server validates the commit and answers with a bare status, so without the
            // body there is nothing to debug against.
            onRejectedCommit(body)
            throw rejected
        }
    }

    /**
     * Publishes a new head. The server runs fast-forward-or-merge itself, so a race with another
     * client does not fail here; the resulting head may differ from [commitId] and has to be read
     * back afterwards.
     */
    suspend fun updateHead(token: String, repoId: String, commitId: String) {
        request(token) {
            url(repo(repoId).addPathSegment("commit").addPathSegment("HEAD").addPathSegment("")
                .addQueryParameter("head", commitId).build())
            put(EMPTY_BODY)
        }.consume { }
    }

    /**
     * Ids of every fs object reachable from [serverHead]. Passing [clientHead] asks for only the
     * objects added since that commit, which is dramatically cheaper on repeat syncs.
     */
    suspend fun fsIdList(
        token: String,
        repoId: String,
        serverHead: String,
        clientHead: String? = null,
        dirOnly: Boolean = false,
    ): List<String> = request(token) {
        url(
            endpoint(repoId, "fs-id-list")
                .addQueryParameter("server-head", serverHead)
                .apply { clientHead?.let { addQueryParameter("client-head", it) } }
                .apply { if (dirOnly) addQueryParameter("dir-only", "1") }
                .build()
        )
    }.consume { SeafJson.parser.decodeFromString(it.string()) }

    /**
     * Fetches fs objects by id. The server caps a response at [ObjectPack.MAX_RESPONSE_BYTES]
     * regardless of how many were asked for, so fewer objects may come back than were requested
     * and the caller has to keep asking for the remainder.
     */
    suspend fun packFs(token: String, repoId: String, ids: List<String>): List<ObjectPack.Entry> =
        request(token) {
            url(endpoint(repoId, "pack-fs").build())
            post(json(SeafJson.parser.encodeToString(ids)))
        }.consume { ObjectPack.read(it.byteStream()) }

    suspend fun sendFs(token: String, repoId: String, entries: List<ObjectPack.Entry>) {
        request(token) {
            url(endpoint(repoId, "recv-fs").build())
            post(ObjectPack.encode(entries).toRequestBody(OCTET_STREAM))
        }.consume { }
    }

    /** Returns the subset of [ids] the server does *not* have, i.e. what still needs uploading. */
    suspend fun missingFs(token: String, repoId: String, ids: List<String>): List<String> =
        checkObjects(token, repoId, "check-fs", ids)

    /** Returns the subset of [ids] the server does *not* have. This is where dedup happens. */
    suspend fun missingBlocks(token: String, repoId: String, ids: List<String>): List<String> =
        checkObjects(token, repoId, "check-blocks", ids)

    private suspend fun checkObjects(
        token: String,
        repoId: String,
        name: String,
        ids: List<String>,
    ): List<String> = request(token) {
        url(endpoint(repoId, name).build())
        post(json(SeafJson.parser.encodeToString(ids)))
    }.consume { SeafJson.parser.decodeFromString(it.string()) }

    /**
     * Streams one block to [consumer]. Blocks reach 4 MiB and the server ignores Range, so the
     * body is handed over as a stream rather than materialised; a failed transfer is retried
     * whole.
     */
    suspend fun <T> downloadBlock(
        token: String,
        repoId: String,
        blockId: String,
        consumer: (InputStream) -> T,
    ): T = request(token) {
        url(repo(repoId).addPathSegments("block/$blockId").build())
    }.consume { consumer(it.byteStream()) }

    suspend fun uploadBlock(token: String, repoId: String, blockId: String, body: RequestBody) {
        request(token) {
            url(repo(repoId).addPathSegments("block/$blockId").build())
            put(body)
        }.consume { }
    }

    /** Asks whether writing [delta] more bytes would exceed the quota. Throws OutOfQuota if so. */
    suspend fun quotaCheck(token: String, repoId: String, delta: Long) {
        request(token) {
            url(endpoint(repoId, "quota-check").addQueryParameter("delta", delta.toString()).build())
        }.consume { }
    }

    private fun repo(repoId: String): HttpUrl.Builder =
        baseUrl.newBuilder().addPathSegments("seafhttp/repo/$repoId")

    /**
     * Builds a repo endpoint with a trailing slash.
     *
     * Not cosmetic: the fileserver answers 404 for these paths when the slash is missing and a
     * query string follows, even though its route declares the slash as optional. Download
     * happens to work without it because pack-fs takes no query parameters, which makes this an
     * easy thing to get wrong in exactly one place and not notice.
     */
    private fun endpoint(repoId: String, name: String): HttpUrl.Builder =
        repo(repoId).addPathSegment(name).addPathSegment("")

    private fun json(body: String): RequestBody = body.toRequestBody(SeafileApi.JSON)

    private suspend fun request(token: String, build: Request.Builder.() -> Unit): Response {
        val request = Request.Builder()
            // The desktop daemon sends the sync token in both headers; some deployments only
            // look at one of them.
            .header("Seafile-Repo-Token", token)
            .header("Authorization", "Token $token")
            .apply(build)
            .build()
        return client.newCall(request).await()
    }

    private inline fun <T> Response.consume(block: (okhttp3.ResponseBody) -> T): T = use {
        val body = it.body ?: throw SeafileException.Unexpected(it.code, describe(it))
        if (!it.isSuccessful) {
            // The fileserver answers some failures with an empty body, so the request itself has
            // to be part of the message or the error says nothing about what went wrong.
            val detail = body.string().take(ERROR_BODY_MAX).ifBlank { describe(it) }
            throw SeafileException.fromFileServer(it.code, detail)
        }
        block(body)
    }

    private fun describe(response: Response): String =
        "${response.request.method} ${response.request.url.encodedPath}"

    private companion object {
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody()
        const val ERROR_BODY_MAX = 500
    }
}
