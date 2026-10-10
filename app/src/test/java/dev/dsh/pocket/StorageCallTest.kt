package dev.dsh.pocket

import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StorageCallTest {
    @Test fun `canceling a browse cancels the HTTP call and another browse succeeds`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(MockResponse().setBody("{\"path\":\"/home\"}"))
            val api = BridgeApi("test", server.port)
            val canceled = CountDownLatch(1)
            api.storageClient = api.storageClient.newBuilder().eventListener(object : EventListener() {
                override fun canceled(call: Call) { canceled.countDown() }
            }).build()
            val first = launch { api.storageCall("browse") }
            withContext(Dispatchers.IO) {
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            }
            first.cancelAndJoin()
            assertTrue(canceled.await(1, TimeUnit.SECONDS))
            assertEquals("/home", api.storageCall("browse").getString("path"))
        }
    }

    @Test fun `silent storage requests have a bounded deadline`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val api = BridgeApi("test", server.port)
            withTimeout(3_000) {
                try {
                    api.storageCall("shortcuts", timeoutMs = 100)
                    fail("Expected storage timeout")
                } catch (_: IOException) { }
            }
        }
    }
}
