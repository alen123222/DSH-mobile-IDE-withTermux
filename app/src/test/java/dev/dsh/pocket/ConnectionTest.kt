package dev.dsh.pocket

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class ConnectionTest {
    @Test fun `Termux status is distinct from shell exit code`() {
        assertFalse(TermuxConnection.CommandResult(0, -1, "").failed)
        assertTrue(TermuxConnection.CommandResult(1, -1, "install failed").failed)
        assertTrue(TermuxConnection.CommandResult(null, 2, "allow-external-apps").failed)
        assertTrue(TermuxConnection.CommandResult(0, 0, "cancelled").failed)
    }

    @Test fun `replies cannot cross connection attempts`() = runBlocking {
        TermuxConnection.track { first, firstReply ->
            TermuxConnection.track { second, secondReply ->
                TermuxConnection.complete(first, TermuxConnection.CommandResult(null, 2, "old error"))
                assertTrue(firstReply.await().failed)
                assertFalse(secondReply.isCompleted)
                TermuxConnection.complete(second, TermuxConnection.CommandResult(0, -1, "ok"))
                assertFalse(secondReply.await().failed)
            }
        }
    }

    @Test fun `health sends pairing header and decodes success`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"version\":\"0.6.3\"}"))
            server.start()
            assertEquals("0.6.3", BridgeApi("test-token", server.port).health().string("version"))
            assertEquals("Bearer test-token", server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun `silent server cannot hold the connection loop for sixty seconds`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val failure = withTimeoutOrNull(4000) {
                try { BridgeApi("test", server.port).health(); false }
                catch (e: java.io.IOException) { true }
            }
            assertEquals(true, failure)
        }
    }

    @Test fun `outer connection deadline cancels in flight health call`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            assertNull(withTimeoutOrNull(100) { BridgeApi("test", server.port).health() })
        }
    }
}
