package com.xmitya.seafilesync.data.api

import android.annotation.SuppressLint
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * A TLS setup that accepts whatever certificate the server presents.
 *
 * It exists because a Seafile instance on a home server or a LAN routinely runs a self-signed
 * certificate, and refusing it leaves no way in at all. Nothing reaches this object unless the
 * user ticked the box for their account; [com.xmitya.seafilesync.app.AppContainer] hands out the
 * ordinary client otherwise. Turning it on really does give up the protection against a man in
 * the middle rather than merely relaxing a check, which is why the choice is stored per account
 * and worded plainly on screen.
 */
object InsecureTrust {

    @SuppressLint("CustomX509TrustManager")
    val trustManager: X509TrustManager = object : X509TrustManager {

        @SuppressLint("TrustAllX509TrustManager")
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

        @SuppressLint("TrustAllX509TrustManager")
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit

        /** Empty is the correct answer for a client: it never asks a peer to pick an issuer. */
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * Accepts any hostname. Needed alongside [trustManager]: a certificate issued for a name the
     * user is not dialling still fails the default verifier even once the chain is trusted, and
     * a LAN server reached by IP is exactly that case.
     */
    @SuppressLint("BadHostnameVerifier")
    fun hostnameVerifier(): HostnameVerifier = HostnameVerifier { _, _ -> true }

    fun socketFactory(): SSLSocketFactory =
        SSLContext
            .getInstance("TLS")
            .apply { init(null, arrayOf(trustManager), SecureRandom()) }
            .socketFactory
}
