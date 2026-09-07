package com.heyheyon.armbandbot

import java.net.ServerSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Tiny loopback HTTP server: no Android/JDK HttpServer dependency. */
internal class LocalHttpServer private constructor(address: InetSocketAddress) {
    private val socket = ServerSocket().apply { bind(address) }
    val address = InetSocketAddress("127.0.0.1", socket.localPort)
    private val handlers = ConcurrentHashMap<String, (Exchange) -> Unit>()
    fun start() { thread(isDaemon = true) {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (_: Exception) { break }
            client.use { val (path, exchange) = Exchange.read(client); handlers[path]?.invoke(exchange) }
        }
    } }
    fun stop(delay: Int) { socket.close() }
    fun createContext(path: String, handler: (Exchange) -> Unit) { handlers[path] = handler }
    class Headers : LinkedHashMap<String, String>() {
        fun add(key: String, value: String) { put(key, value) }
        fun getFirst(key: String) = entries.firstOrNull { it.key.equals(key, true) }?.value
    }
    class Exchange(private val socket: Socket, val requestMethod: String, val requestHeaders: Headers, body: ByteArray) {
        val requestBody = body.inputStream()
        val responseHeaders = Headers()
        val responseBody = socket.getOutputStream()
        fun sendResponseHeaders(status: Int, length: Long) {
            responseBody.write(("HTTP/1.1 $status Response\r\nContent-Length: ${length.coerceAtLeast(0)}\r\nConnection: close\r\n" + responseHeaders.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n").toByteArray())
        }
        fun close() { socket.close() }
        companion object {
            fun read(socket: Socket): Pair<String, Exchange> {
                val input = socket.getInputStream()
                fun line(): String {
                    val bytes = ByteArrayOutputStream()
                    while (true) { val b = input.read(); if (b == -1 || b == 10) break; if (b != 13) bytes.write(b) }
                    return bytes.toString("UTF-8")
                }
                val first = line().split(" ")
                val headers = Headers()
                while (true) { val value = line(); if (value.isEmpty()) break; headers.add(value.substringBefore(":"), value.substringAfter(":").trim()) }
                val body = ByteArray(headers.getFirst("Content-Length")?.toInt() ?: 0)
                var offset = 0
                while (offset < body.size) { val n = input.read(body, offset, body.size-offset); if (n < 0) break; offset += n }
                return first[1].substringBefore("?") to Exchange(socket, first[0], headers, body)
            }
        }
    }
    companion object { fun create(address: InetSocketAddress, backlog: Int) = LocalHttpServer(address) }
}
