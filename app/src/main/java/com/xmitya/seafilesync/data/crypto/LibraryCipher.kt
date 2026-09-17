package com.xmitya.seafilesync.data.crypto

/**
 * The transformation applied to every block of one encrypted library.
 *
 * Blocks are identified by the SHA-1 of what is stored, which for an encrypted library is the
 * ciphertext. So encryption happens before hashing on the way out, and verification happens
 * before decryption on the way in.
 */
class LibraryCipher(
    private val key: LibraryKey,
    private val version: Int,
) {

    fun encrypt(plaintext: ByteArray): ByteArray = LibraryCrypto.encrypt(plaintext, key, version)

    fun decrypt(ciphertext: ByteArray): ByteArray = LibraryCrypto.decrypt(ciphertext, key, version)

    companion object {
        fun forLibrary(password: String, randomKeyHex: String, version: Int, saltHex: String) =
            LibraryCipher(LibraryCrypto.fileKey(password, randomKeyHex, version, saltHex), version)
    }
}
