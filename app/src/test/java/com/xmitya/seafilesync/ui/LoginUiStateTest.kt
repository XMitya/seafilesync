package com.xmitya.seafilesync.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The login form's URL handling. Pure state, so it is exercised directly rather than through the
 * view model, which would need the whole application container to exist.
 */
class LoginUiStateTest {

    @Test
    fun `the chosen scheme is what gets dialled`() {
        val form = LoginUiState(serverUrl = "seafile.example.com")

        assertEquals("https://seafile.example.com", form.fullServerUrl)
        assertEquals("http://seafile.example.com", form.copy(useHttps = false).fullServerUrl)
    }

    @Test
    fun `stray whitespace and a trailing slash are not the user's problem`() {
        val form = LoginUiState(serverUrl = "  seafile.example.com/  ")

        assertEquals("https://seafile.example.com", form.fullServerUrl)
    }

    @Test
    fun `a port survives`() {
        val form = LoginUiState(serverUrl = "192.168.1.5:8000", useHttps = false)

        assertEquals("http://192.168.1.5:8000", form.fullServerUrl)
    }

    @Test
    fun `pasting an http url moves the scheme into the selector`() {
        val form = LoginUiState().withTypedServerUrl("http://192.168.1.5:8000")

        assertFalse(form.useHttps)
        assertEquals("192.168.1.5:8000", form.serverUrl)
        assertEquals("http://192.168.1.5:8000", form.fullServerUrl)
    }

    @Test
    fun `pasting an https url moves the scheme into the selector`() {
        val form = LoginUiState(useHttps = false).withTypedServerUrl("https://seafile.example.com")

        assertTrue(form.useHttps)
        assertEquals("seafile.example.com", form.serverUrl)
    }

    @Test
    fun `a scheme is recognised whatever its case`() {
        val form = LoginUiState().withTypedServerUrl("HTTP://seafile.example.com")

        assertFalse(form.useHttps)
        assertEquals("seafile.example.com", form.serverUrl)
    }

    @Test
    fun `typing a bare host leaves the selector alone`() {
        val form = LoginUiState(useHttps = false).withTypedServerUrl("seafile.example.com")

        assertFalse(form.useHttps)
        assertEquals("seafile.example.com", form.serverUrl)
    }

    @Test
    fun `pasting an http url drops a certificate exemption that is no longer on screen`() {
        val form = LoginUiState(allowInsecureTls = true).withTypedServerUrl("http://192.168.1.5")

        assertFalse(form.allowInsecureTls)
    }

    @Test
    fun `the exemption cannot apply over http even if it is still set`() {
        // Belt and braces: the read path is one property, so nothing has to remember to check
        // the scheme before trusting the flag.
        val form = LoginUiState(useHttps = false, allowInsecureTls = true)

        assertFalse(form.skipCertificateCheck)
        assertTrue(form.copy(useHttps = true).skipCertificateCheck)
    }
}
