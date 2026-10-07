package dev.dsh.pocket

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.Proxy
import java.net.URI

class BridgeHostProbeTest {
    @Test fun `real health probe and diagnostic report use controlled local server`() {
        MockWebServer().use { server ->
            server.start()
            repeat(3) { server.enqueue(MockResponse().setBody("{\"version\":\"0.4.9\"}")) }
            val api = BridgeApi("synthetic-test-token", server.port)
            assertEquals("127.0.0.1", api.probeHosts())
            val report = api.probeReport()
            assertTrue(report.contains("body[:180]"))
            api.candidates.forEach { assertTrue(report.contains("$it:${server.port}")) }
            // Assert through tr() so the test does not depend on the JVM locale.
            assertTrue(report.contains(tr("系统默认代理: ")))
            repeat(3) { assertEquals("Bearer synthetic-test-token", server.takeRequest().getHeader("Authorization")) }
        }
    }
    @Test fun `no listener produces useful diagnostics without requiring a response body`() {
        val server = MockWebServer()
        server.start()
        val port = server.port
        server.shutdown()
        val report = BridgeApi("test", port).probeReport()
        assertTrue(report.contains(tr("异常 ")))
        assertFalse(report.contains("body[:180]"))
    }
    @Test fun `non loopback hosts cannot receive the pairing token`() {
        val api = BridgeApi("test")
        assertEquals(listOf("127.0.0.1", "localhost"), api.candidates)
        assertThrows(IllegalArgumentException::class.java) { api.preferHost("10.0.2.2") }
        assertThrows(IllegalArgumentException::class.java) { api.callOn("example.com", "health") }
        api.preferHost("localhost")
        assertEquals("localhost", api.address())
    }
    @Test fun `html and malformed responses produce useful errors`() {
        assertTrue(assertThrows(IllegalStateException::class.java) { decodeText("<html>Error", 502) }.message!!.contains("HTML"))
        // Language-neutral: both the Chinese and the English wording contain "JSON".
        assertTrue(assertThrows(IllegalStateException::class.java) { decodeText("not json", 502) }.message!!.contains("JSON"))
        assertEquals("连接密钥不匹配", assertThrows(IllegalStateException::class.java) {
            decodeText("{\"error\":\"连接密钥不匹配\"}", 401)
        }.message)
    }
    @Test fun `no proxy selected`() {
        assertEquals(listOf(Proxy.NO_PROXY), NO_PROXY.select(URI("http://127.0.0.1:8765")))
    }
}
