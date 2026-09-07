package com.xmitya.seafilesync.data.api

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

class SeafileApiTest {

    private val server = MockWebServer().apply { start() }
    private val api = SeafileApi(server.url("/").toString(), OkHttpClient())

    @After
    fun tearDown() = server.shutdown()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "missing fixture $name"
        }.use { it.readBytes().decodeToString() }

    private fun enqueue(body: String, code: Int = 200, vararg headers: Pair<String, String>) {
        val response = MockResponse().setResponseCode(code).setBody(body)
        headers.forEach { response.addHeader(it.first, it.second) }
        server.enqueue(response)
    }

    private suspend fun login(deviceId: String = "a1b2c3d4e5f60718", otp: String? = null) =
        api.login(
            username = "test@example.com",
            password = "secret",
            deviceId = deviceId,
            deviceName = "Pixel 3a",
            clientVersion = "1.0",
            platformVersion = "14",
            otp = otp,
        )

    @Test
    fun `login posts the device fields the server requires`() = runTest {
        enqueue(fixture("auth-token.json"))

        assertEquals("0".repeat(40), login())

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api2/auth-token/", request.path)
        val body = request.body.readUtf8()
        // All of platform, device_id and device_name must be present together, or the server
        // falls back to a v1 token and the device is never registered.
        for (field in listOf("username", "password", "platform", "device_id", "device_name",
                             "client_version", "platform_version")) {
            assertTrue("missing form field $field", body.contains("name=\"$field\""))
        }
        assertTrue(body.contains("android"))
    }

    @Test
    fun `login rejects a device id the server would refuse`() = runTest {
        // The server validates android device ids as 1..16 lowercase hex. A UUID or a desktop
        // 40-character peer id comes back as a bare 400, so it is caught before the round trip.
        assertFailsWith<IllegalArgumentException> { login(deviceId = "550e8400-e29b-41d4-a716") }
        assertFailsWith<IllegalArgumentException> { login(deviceId = "A1B2C3D4E5F60718") }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `bad credentials surface as InvalidCredentials`() = runTest {
        enqueue("""{"non_field_errors":["Unable to login with provided credentials."]}""", code = 400)

        val failure = assertFailsWith<SeafileException.InvalidCredentials> { login() }
        assertEquals("Unable to login with provided credentials.", failure.message)
    }

    @Test
    fun `a two-factor challenge is distinguishable from bad credentials`() = runTest {
        enqueue("""{"non_field_errors":["Two factor auth token is missing."]}""", code = 400,
            "X-Seafile-OTP" to "required")

        assertFailsWith<SeafileException.TwoFactorRequired> { login() }
    }

    @Test
    fun `otp is sent as a header when retrying`() = runTest {
        enqueue(fixture("auth-token.json"))

        login(otp = "123456")

        assertEquals("123456", server.takeRequest().getHeader("X-Seafile-OTP"))
    }

    @Test
    fun `a remote wipe is not mistaken for an expired token`() = runTest {
        enqueue("", code = 401, "X-Seafile-Wiped" to "true")

        assertFailsWith<SeafileException.DeviceWiped> { api.repos("token") }
    }

    @Test
    fun `a plain 401 asks for a new login`() = runTest {
        enqueue("", code = 401)

        assertFailsWith<SeafileException.TokenRejected> { api.repos("token") }
    }

    @Test
    fun `error_msg is unwrapped from the envelope`() = runTest {
        enqueue("""{"error_msg":"Permission denied."}""", code = 403)

        val failure = assertFailsWith<SeafileException.PermissionDenied> { api.repos("token") }
        assertEquals("Permission denied.", failure.message)
    }

    @Test
    fun `repos are parsed and carry the head commit`() = runTest {
        enqueue(fixture("repos.json"))

        val repo = api.repos("token").single()
        assertEquals("79dc614e-d7f4-47a5-8bcc-10400a0a08cb", repo.id)
        assertEquals("My Library", repo.name)
        assertEquals("b2be155598a7ef094045f343939daa410b3ff03f", repo.headCommitId)
        assertEquals(1, repo.version)
        assertFalse(repo.encrypted)
        assertTrue(repo.isWritable)
        assertEquals("Token token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `download info yields the per-library sync token`() = runTest {
        enqueue(fixture("download-info.json"))

        val info = api.downloadInfo("token", "79dc614e-d7f4-47a5-8bcc-10400a0a08cb")
        assertEquals("1".repeat(40), info.token)
        assertEquals("b2be155598a7ef094045f343939daa410b3ff03f", info.headCommitId)
        assertEquals(1, info.repoVersion)
        // The server reports "not encrypted" as an empty string rather than a boolean.
        assertFalse(info.isEncrypted)
        assertTrue(info.isWritable)
    }

    @Test
    fun `server info is readable without a token`() = runTest {
        enqueue(fixture("server-info.json"))

        val info = api.serverInfo()
        assertEquals("11.0.13", info.version)
        assertEquals(2, info.encryptedLibraryVersion)
        assertEquals(null, server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `account info is parsed`() = runTest {
        enqueue(fixture("account-info.json"))

        assertEquals("test@example.com", api.accountInfo("token").contactEmail)
    }

    @Test
    fun `server url accepts what a user would type`() {
        val expected = "https://seafile.example.com/"
        assertEquals(expected, SeafileApi.normalize("seafile.example.com").toString())
        assertEquals(expected, SeafileApi.normalize("https://seafile.example.com").toString())
        assertEquals(expected, SeafileApi.normalize("  https://seafile.example.com/  ").toString())
        assertEquals("http://192.168.1.5:8000/", SeafileApi.normalize("http://192.168.1.5:8000").toString())
    }
}
