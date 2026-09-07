package com.heyheyon.armbandbot

import com.heyheyon.armbandbot.LocalHttpServer as HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.Connection
import org.junit.Assert.*
import org.junit.Test

class JsoupRedirectTest {
    private fun server(block: (HttpServer, String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try { block(server, "http://127.0.0.1:${server.address.port}") } finally { server.stop(0) }
    }
    @Test fun `redirect hop checks fresh clock and owning cancellation`() {
        for (cancel in listOf(false, true)) server { server, base ->
            val now = AtomicInteger(9)
            val owner = Job()
            val gate = RuntimeRequestGate(owner) { now.get() < 10 }
            val target = AtomicInteger()
            server.createContext("/start") {
                now.set(10)
                if (cancel) owner.cancel()
                it.responseHeaders.add("Location", "/target")
                it.sendResponseHeaders(302, -1); it.close()
            }
            server.createContext("/target") { target.incrementAndGet(); it.sendResponseHeaders(200, -1); it.close() }
            assertThrows(if (cancel) CancellationException::class.java else SchedulePausedException::class.java) {
                executeGatedJsoup(Jsoup.connect("$base/start"), gate::check)
            }
            assertEquals(0, target.get())
        }
    }
    @Test fun `relative redirects retain cookies and Jsoup post method semantics`() {
        for (status in listOf(301, 302, 303, 307, 308)) server { server, base ->
            var method = ""
            var cookie = ""
            var body = ""
            server.createContext("/start") {
                it.responseHeaders.add("Location", "target")
                it.responseHeaders.add("Set-Cookie", "session=ok; Path=/")
                it.sendResponseHeaders(status, -1); it.close()
            }
            server.createContext("/target") {
                method = it.requestMethod; cookie = it.requestHeaders.getFirst("Cookie") ?: ""
                body = it.requestBody.bufferedReader().readText()
                it.responseHeaders.add("Content-Type", "text/html")
                val bytes = "<title>done</title>".toByteArray()
                it.sendResponseHeaders(200, bytes.size.toLong()); it.responseBody.use { out -> out.write(bytes) }
            }
            var checks = 0
            val response = executeGatedJsoup(Jsoup.connect("$base/start").method(Connection.Method.POST).data("key", "value")) { checks++ }
            assertEquals(2, checks)
            assertEquals(if (status == 307) "POST" else "GET", method)
            assertEquals(if (status == 307) "key=value" else "", body)
            assertTrue(cookie.contains("session=ok"))
            assertEquals("ok", response.cookie("session"))
            assertEquals("done", response.parse().title())
        }
    }
    @Test fun `Jsoup XML MIME parsing remains supported`() = server { server, base ->
        server.createContext("/xml") {
            it.responseHeaders.add("Content-Type", "image/svg+xml")
            val body = "<svg><title>image</title></svg>".toByteArray()
            it.sendResponseHeaders(200, body.size.toLong()); it.responseBody.use { out -> out.write(body) }
        }
        assertEquals("image", executeGatedJsoup(Jsoup.connect("$base/xml")) {}.parse().selectFirst("title")!!.text())
    }
    @Test fun `final HTTP error status follows caller ignore policy`() = server { server, base ->
        server.createContext("/start") {
            it.responseHeaders.add("Location", "/error"); it.sendResponseHeaders(302, -1); it.close()
        }
        server.createContext("/error") { it.sendResponseHeaders(403, -1); it.close() }
        assertThrows(org.jsoup.HttpStatusException::class.java) { executeGatedJsoup(Jsoup.connect("$base/start")) {} }
        assertEquals(403, executeGatedJsoup(Jsoup.connect("$base/start").ignoreHttpErrors(true)) {}.statusCode())
    }
    @Test fun `disabled redirects and redirect limit are respected`() = server { server, base ->
        val hits = AtomicInteger()
        server.createContext("/loop") {
            hits.incrementAndGet(); it.responseHeaders.add("Location", "/loop")
            it.sendResponseHeaders(302, -1); it.close()
        }
        assertEquals(302, executeGatedJsoup(Jsoup.connect("$base/loop").followRedirects(false)) {}.statusCode())
        assertEquals(1, hits.get())
        hits.set(0)
        assertThrows(java.io.IOException::class.java) { executeGatedJsoup(Jsoup.connect("$base/loop")) {} }
        assertEquals(21, hits.get())
    }
}
