package com.tkhskt.claude.notification.server

import co.touchlab.kermit.Logger
import com.tkhskt.claude.notification.permission.HookResponse
import com.tkhskt.claude.notification.permission.PermissionRequest
import com.tkhskt.claude.notification.permission.PermissionRequestHolder
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

/**
 * Local IPC over HTTP. Replaces the JDK-only `com.sun.net.httpserver.HttpServer`
 * with Ktor (CIO engine) so the embedded jpackage runtime doesn't have to
 * include the `jdk.httpserver` module.
 */
class PermissionServer(
    private val holder: PermissionRequestHolder,
    private val port: Int = DEFAULT_PORT,
) {
    private val log = Logger.withTag("PermissionServer")
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun start() {
        if (server != null) return
        val engine = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            routing {
                post("/permission-request") { handlePermissionRequest() }
                post("/tool-resolved") { handleToolResolved() }
            }
        }
        engine.start(wait = false)
        server = engine
        log.i { "Listening on http://127.0.0.1:$port (/permission-request, /tool-resolved)" }
    }

    fun stop() {
        server?.stop(GRACE_MS, TIMEOUT_MS)
        server = null
    }

    private suspend fun RoutingContext.handlePermissionRequest() {
        val body = call.receiveText()
        val request = try {
            json.decodeFromString(PermissionRequest.serializer(), body)
        } catch (t: Throwable) {
            log.w(t) { "Failed to parse request body: $body" }
            call.respondText("", status = HttpStatusCode.BadRequest)
            return
        }
        log.i { "PermissionRequest received: tool=${request.toolName} session=${request.sessionId}" }
        val decision = holder.submit(request)
        log.i { "PermissionRequest for ${request.toolName} → $decision" }
        val responseJson = HookResponse.forDecision(decision)
        call.respondText(responseJson, ContentType.Application.Json, HttpStatusCode.OK)
    }

    private suspend fun RoutingContext.handleToolResolved() {
        val body = call.receiveText()
        val event = try {
            json.decodeFromString(PermissionRequest.serializer(), body)
        } catch (t: Throwable) {
            log.w(t) { "Failed to parse tool-resolved body: $body" }
            call.respondText("", status = HttpStatusCode.BadRequest)
            return
        }
        val resolved = holder.resolveExternally(event)
        if (resolved) {
            log.i { "tool-resolved HIT: event=${event.hookEventName} tool=${event.toolName}" }
        }
        // 200 with empty {} — avoid any "no decision" ambiguity at Claude's side.
        // PostToolUse hooks ignore the body anyway.
        call.respondText("{}", ContentType.Application.Json, HttpStatusCode.OK)
    }

    companion object {
        const val DEFAULT_PORT = 44215
        private const val GRACE_MS = 100L
        private const val TIMEOUT_MS = 500L
    }
}
