package dev.dsh.pocket

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BridgeStreamTest {
    @Test fun `closed streams release connections and close exactly once across retries`() {
        MockWebServer().use { server ->
            server.start()
            val api = BridgeApi("test", server.port)
            val released = AtomicInteger()
            api.streamClient = api.streamClient.newBuilder().eventListener(object : EventListener() {
                override fun connectionReleased(call: Call, connection: Connection) { released.incrementAndGet() }
            }).build()
            val responses = listOf(
                MockResponse().setResponseCode(401).setBody("denied"),
                MockResponse().setBody("<html>error"),
                MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"id\":\"chat\"}\n\n"),
                MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: deleted\ndata: {}\n\n")
            )
            val frames = AtomicInteger()
            responses.forEachIndexed { index, response ->
                server.enqueue(response)
                val closed = CountDownLatch(1)
                val calls = AtomicInteger()
                api.stream("chat", { frames.incrementAndGet() }, { calls.incrementAndGet(); closed.countDown() })
                assertTrue(closed.await(3, TimeUnit.SECONDS))
                assertEquals(1, calls.get())
                assertEquals(index + 1, released.get())
            }
            assertEquals(2, frames.get())
        }
    }
    @Test fun `silent connection times out and releases the monitor`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val api = BridgeApi("test", server.port)
            api.streamClient = api.streamClient.newBuilder().readTimeout(150, TimeUnit.MILLISECONDS).build()
            val closed = CountDownLatch(1)
            api.stream("chat", { fail("unexpected frame") }, { closed.countDown() })
            assertTrue(closed.await(3, TimeUnit.SECONDS))
        }
    }
}
