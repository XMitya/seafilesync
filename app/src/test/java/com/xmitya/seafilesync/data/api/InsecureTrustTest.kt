package com.xmitya.seafilesync.data.api

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.assertFailsWith

/**
 * Serves a self-signed certificate, which is the situation the exemption exists for and the one
 * thing about it that cannot be checked by reading the code.
 */
class InsecureTrustTest {

    private val certificate = HeldCertificate
        .Builder()
        .addSubjectAlternativeName("seafile.example.com")
        .build()

    private val server = MockWebServer().apply {
        useHttps(
            HandshakeCertificates
                .Builder()
                .heldCertificate(certificate)
                .build()
                .sslSocketFactory(),
            false,
        )
        enqueue(MockResponse().setBody("ok"))
        start()
    }

    @After
    fun tearDown() = server.shutdown()

    private val insecure = OkHttpClient
        .Builder()
        .sslSocketFactory(InsecureTrust.socketFactory(), InsecureTrust.trustManager)
        .hostnameVerifier(InsecureTrust.hostnameVerifier())
        .build()

    /**
     * Dialled by address rather than by name on purpose. "localhost" resolves to ::1 first here
     * while MockWebServer listens on IPv4, and OkHttp reports the first route's ConnectException,
     * which would hide the TLS failure these tests are about.
     */
    private fun url() = server
        .url("/")
        .newBuilder()
        .host("127.0.0.1")
        .build()

    private fun get(client: OkHttpClient): String =
        client
            .newCall(Request.Builder().url(url()).build())
            .execute()
            .use { it.body?.string().orEmpty() }

    @Test
    fun `a self-signed certificate is refused by default`() {
        assertFailsWith<SSLHandshakeException> { get(OkHttpClient()) }
    }

    @Test
    fun `the exemption accepts a self-signed certificate`() {
        assertEquals("ok", get(insecure))
    }

    @Test
    fun `trusting the chain is not enough on its own`() {
        // The certificate names seafile.example.com while the server answers on localhost, which
        // is the ordinary shape of a LAN server reached by address. Dropping the hostname check
        // as well is what makes the exemption actually usable.
        val trusting = HandshakeCertificates
            .Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        val client = OkHttpClient
            .Builder()
            .sslSocketFactory(trusting.sslSocketFactory(), trusting.trustManager)
            .build()

        assertFailsWith<SSLPeerUnverifiedException> { get(client) }
    }
}
