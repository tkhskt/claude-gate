package com.tkhskt.claude.notification.server

import co.touchlab.kermit.Logger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import com.tkhskt.claude.notification.permission.HookResponse
import com.tkhskt.claude.notification.permission.PermissionRequest
import com.tkhskt.claude.notification.permission.PermissionRequestHolder
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class PermissionServer(
    private val holder: PermissionRequestHolder,
    private val port: Int = DEFAULT_PORT,
) {
    private val log = Logger.withTag("PermissionServer")
    private val json = Json { ignoreUnknownKeys = true }
    private var server: HttpServer? = null

    fun start() {
        if (server != null) return
        val address = InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)
        val created = HttpServer.create(address, 0).apply {
            createContext("/permission-request", Handler())
            executor = Executors.newFixedThreadPool(4)
            start()
        }
        server = created
        log.i { "Listening on http://127.0.0.1:$port/permission-request" }
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private inner class Handler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            exchange.use {
                if (exchange.requestMethod != "POST") {
                    exchange.sendResponseHeaders(405, -1)
                    return
                }
                val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                val request = try {
                    json.decodeFromString(PermissionRequest.serializer(), body)
                } catch (t: Throwable) {
                    log.w(t) { "Failed to parse request body: $body" }
                    exchange.sendResponseHeaders(400, -1)
                    return
                }
                val decision = try {
                    runBlocking { holder.submit(request) }
                } catch (e: TimeoutCancellationException) {
                    log.i { "PermissionRequest for ${request.toolName} timed out; returning 503 so the hook falls through" }
                    // Empty 503 → hook script sees empty stdout → exits 0 →
                    // Claude Code falls back to its own terminal prompt.
                    runCatching { exchange.sendResponseHeaders(503, -1) }
                    return
                }
                val responseJson = HookResponse.forDecision(decision)
                val bytes = responseJson.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 44215
    }
}
