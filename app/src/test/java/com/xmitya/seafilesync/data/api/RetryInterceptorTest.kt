package com.xmitya.seafilesync.data.api

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class RetryInterceptorTest {

    private val server = MockWebServer().apply { start() }
    private val slept = mutableListOf<Long>()

    private val client = OkHttpClient
        .Builder()
        .addInterceptor(RetryInterceptor(maxAttempts = 4, sleeper = { slept += it }))
        .build()

    @After
    fun tearDown() = server.shutdown()

    private fun call() = client.newCall(Request.Builder().url(server.url("/")).build()).execute()

    @Test
    fun `a transient server error is retried until it succeeds`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        call().use { assertEquals("ok", it.body!!.string()) }

        assertEquals(3, server.requestCount)
        // Backoff doubles rather than hammering a struggling server.
        assertEquals(listOf(500L, 1000L), slept)
    }

    @Test
    fun `rate limiting is treated as transient`() {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        call().use { assertEquals(200, it.code) }

        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a client error is surfaced immediately`() {
        // 403 describes a real problem with the request; retrying only delays the report.
        server.enqueue(MockResponse().setResponseCode(403))

        call().use { assertEquals(403, it.code) }

        assertEquals(1, server.requestCount)
        assertEquals(emptyList<Long>(), slept)
    }

    @Test
    fun `retries give up and return the last response rather than looping`() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(503)) }

        call().use { assertEquals(503, it.code) }

        assertEquals(4, server.requestCount)
    }

    @Test
    fun `a dropped connection is retried`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        call().use { assertEquals("ok", it.body!!.string()) }

        assertEquals(2, server.requestCount)
    }
}
