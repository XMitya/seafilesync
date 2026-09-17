package com.xmitya.seafilesync.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuthTokenDto(
    val token: String,
)

@Serializable
data class ServerInfoDto(
    val version: String,
    @SerialName("encrypted_library_version") val encryptedLibraryVersion: Int = 2,
    val features: List<String> = emptyList(),
)

@Serializable
data class AccountInfoDto(
    val email: String,
    val name: String = "",
    @SerialName("contact_email") val contactEmail: String = "",
    /** Bytes in use. */
    val usage: Long = 0,
    /** Quota in bytes, or a negative value when unlimited. */
    val total: Long = -2,
)

@Serializable
data class RepoDto(
    val id: String,
    val name: String,
    val size: Long = 0,
    val mtime: Long = 0,
    val encrypted: Boolean = false,
    /** Object format of the library. Everything current is 1. */
    val version: Int = 1,
    /** "r" or "rw". */
    val permission: String = "r",
    @SerialName("head_commit_id") val headCommitId: String? = null,
    /** Per-repo salt, present only for encrypted libraries from enc_version 3 on. */
    val salt: String = "",
) {
    val isWritable: Boolean get() = permission == "rw"
}

/**
 * Handshake for the sync protocol. The [token] here is a per-library sync token for /seafhttp
 * and is not the account token.
 */
@Serializable
data class DownloadInfoDto(
    @SerialName("repo_id") val repoId: String,
    @SerialName("repo_name") val repoName: String,
    val token: String,
    val permission: String = "r",
    @SerialName("repo_version") val repoVersion: Int = 1,
    @SerialName("head_commit_id") val headCommitId: String? = null,
    /** Encoded as "" or as 1 depending on the value, hence the lenient reader. */
    @Serializable(with = LenientBooleanSerializer::class)
    val encrypted: Boolean = false,
    @SerialName("enc_version") val encVersion: Int = 0,
    val magic: String = "",
    @SerialName("random_key") val randomKey: String = "",
    val salt: String = "",
) {
    val isEncrypted: Boolean get() = encrypted
    val isWritable: Boolean get() = permission == "rw"
}

/** Reply to `GET /seafhttp/repo/{id}/commit/HEAD`. */
@Serializable
data class HeadCommitDto(
    @SerialName("is_corrupted") val isCorrupted: Int = 0,
    @SerialName("head_commit_id") val headCommitId: String? = null,
) {
    val corrupted: Boolean get() = isCorrupted != 0
}

/**
 * A commit object. Unlike fs objects these are plain JSON on the wire and their id is assigned
 * by the creator rather than derived from the content.
 */
@Serializable
data class CommitDto(
    @SerialName("commit_id") val commitId: String,
    @SerialName("root_id") val rootId: String,
    @SerialName("repo_id") val repoId: String,
    @SerialName("creator_name") val creatorName: String = "",
    /** Peer id of the creating client, all zeroes for commits the server made. */
    val creator: String = "0".repeat(40),
    val description: String = "",
    val ctime: Long = 0,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("second_parent_id") val secondParentId: String? = null,
    @SerialName("repo_name") val repoName: String = "",
    @SerialName("repo_desc") val repoDesc: String = "",
    val version: Int = 1,
    /** Set by clients that do not keep local history; the desktop client writes 1. */
    @SerialName("no_local_history") val noLocalHistory: Int? = null,
    val encrypted: String? = null,
    @SerialName("enc_version") val encVersion: Int? = null,
    val magic: String? = null,
    /** The fileserver names this "key" on commits, unlike download-info which says "random_key". */
    @SerialName("key") val randomKey: String? = null,
    val salt: String? = null,
    @SerialName("pwd_hash") val pwdHash: String? = null,
    @SerialName("pwd_hash_algo") val pwdHashAlgo: String? = null,
    @SerialName("pwd_hash_params") val pwdHashParams: String? = null,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("client_version") val clientVersion: String? = null,
)
