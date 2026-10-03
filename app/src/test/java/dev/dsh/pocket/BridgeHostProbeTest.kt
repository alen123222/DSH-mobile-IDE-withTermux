package dev.dsh.pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ConnectException

/**
 * Regression cover for "Termux reports the service healthy but the app cannot
 * connect". A VPN or per-app proxy can capture the app's traffic while leaving
 * Termux alone, so the app's own 127.0.0.1 never reaches the device loopback.
 * The address search must therefore find whichever local address works.
 */
class BridgeHostProbeTest {
    /** Mirror of BridgeApi.probeHosts, exercised without a device. */
    private fun search(candidates: List<String>, call: (String) -> Unit): String {
        var last: Throwable? = null
        for (candidate in candidates) {
            try { call(candidate); return candidate }
            catch (error: Throwable) { last = error }
        }
        throw last ?: IllegalStateException("no candidate host")
    }

    @Test
    fun `first reachable host wins`() {
        val winner = search(listOf("127.0.0.1", "localhost", "10.0.2.2")) { host ->
            if (host != "localhost") throw ConnectException("no route to host $host")
        }
        assertEquals("localhost", winner)
    }

    @Test
    fun `the first host is used when it answers`() {
        var tried = 0
        val winner = search(listOf("127.0.0.1", "localhost")) { tried++ }
        assertEquals("127.0.0.1", winner)
        assertEquals(1, tried)
    }

    @Test
    fun `a later host is chosen when every earlier one fails`() {
        val winner = search(listOf("127.0.0.1", "localhost", "10.0.2.2")) { host ->
            if (host != "10.0.2.2") throw ConnectException("connection refused")
        }
        assertEquals("10.0.2.2", winner)
    }

    @Test
    fun `every candidate failing reports the last real failure`() {
        // The caller shows this message, so it must be the transport error and
        // not a generic wrapper that hides the cause.
        try {
            search(listOf("127.0.0.1", "localhost")) { throw ConnectException("connection refused") }
            fail("expected a ConnectException")
        } catch (error: ConnectException) {
            assertEquals("connection refused", error.message)
        }
    }

    @Test
    fun `candidate order keeps loopback first`() {
        // Loopback is correct on a normal device; the alternates exist only for
        // the captured case, so they must not be tried first.
        val candidates = BridgeApi("token").candidates
        assertEquals("127.0.0.1", candidates.first())
        assertTrue(candidates.contains("localhost"))
        assertTrue(candidates.size >= 2)
    }

    @Test
    fun `the api exposes the address in use`() {
        val api = BridgeApi("token")
        assertEquals("127.0.0.1", api.address())
        api.preferHost("localhost")
        assertEquals("localhost", api.address())
    }
}