package com.tkhskt.claude.notification.server

import co.touchlab.kermit.Logger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import com.tkhskt.claude.notification.permission.HookResponse
import com.tkhskt.claude.notification.permission.PermissionRequest
import com.tkhskt.claude.notification.permission.PermissionRequestHolder
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
            createContext("/tool-resolved", ToolResolvedHandler())
            // Cached pool: each blocked PermissionRequest holds a thread for
            // up to 60s, so a fixed pool would starve `/tool-resolved`. With
            // the cached pool, idle threads die after 60s and new ones spawn
            // on demand for the lightweight tool-resolved hits.
            executor = Executors.newCachedThreadPool()
            start()
        }
        server = created
        log.i { "Listening on http://127.0.0.1:$port (/permission-request, /tool-resolved)" }
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
                log.i { "PermissionRequest received: tool=${request.toolName} session=${request.sessionId}" }
                val decision = runBlocking { holder.submit(request) }
                log.i { "PermissionRequest for ${request.toolName} → $decision" }
                val responseJson = HookResponse.forDecision(decision)
                val bytes = responseJson.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
    }

    /**
     * Receives `PreToolUse` events. If the event matches the request that the
     * popover is currently waiting on, the in-flight `submit` is cancelled so
     * the popover closes immediately — this covers the case where Claude
     * resolved the permission via the terminal (or any other channel) while
     * we were still showing it.
     */
    private inner class ToolResolvedHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            exchange.use {
                if (exchange.requestMethod != "POST") {
                    exchange.sendResponseHeaders(405, -1)
                    return
                }
                val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                val event = try {
                    json.decodeFromString(PermissionRequest.serializer(), body)
                } catch (t: Throwable) {
                    log.w(t) { "Failed to parse tool-resolved body: $body" }
                    exchange.sendResponseHeaders(400, -1)
                    return
                }
                val resolved = holder.resolveExternally(event)
                if (resolved) {
                    log.i { "tool-resolved HIT: event=${event.hookEventName} tool=${event.toolName}" }
                }
                // 200 with empty body (instead of 204) — avoid any "no decision"
                // ambiguity at Claude's side. PostToolUse hooks ignore the body anyway.
                val empty = "{}".toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, empty.size.toLong())
                exchange.responseBody.use { it.write(empty) }
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 44215
    }
}
