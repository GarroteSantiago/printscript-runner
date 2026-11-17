package org.snippet.runner.run

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.snippet.runner.common.logging.CorrelationIdFilter
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * `ws://printscript-runner:8080/ws/run` (`contracts/websocket.md`). The handshake goes through the
 * HTTP security chain, so it needs `Authorization: Bearer` like any other endpoint; the Snippet
 * Service forwards the user's token and has already checked the permission.
 */
@Configuration
@EnableWebSocket
class RunWebSocketConfig(
    private val handler: RunWebSocketHandler,
) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        // Only the Snippet Service connects, and it sends no Origin
        registry.addHandler(handler, "/ws/run")
    }
}

/**
 * One run per socket. Messages in: `start` (once), `input`, `stop`. Messages out: `output`,
 * `input-request`, `error`, `done`; the socket is closed right after `done`. Closing the socket
 * stops the run.
 */
@Component
class RunWebSocketHandler(
    private val runs: InteractiveRunner,
    private val json: ObjectMapper,
) : TextWebSocketHandler() {
    private val log = LoggerFactory.getLogger(javaClass)
    private val connections = ConcurrentHashMap<String, Connection>()

    private class Connection(
        val socket: WebSocketSession,
    ) {
        @Volatile var run: InteractiveRun? = null
    }

    override fun afterConnectionEstablished(session: WebSocketSession) {
        // Messages go out from the run's thread and from this one: the decorator serializes them
        // and closes a socket whose client doesn't keep up instead of buffering without bound
        connections[session.id] = Connection(ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT))
    }

    override fun handleTextMessage(
        session: WebSocketSession,
        message: TextMessage,
    ) {
        val connection = connections[session.id] ?: return
        val incoming =
            runCatching { json.readValue(message.payload, Incoming::class.java) }.getOrNull()
                ?: return send(connection.socket, RunEvent.Error("Invalid message"))
        when (incoming.type) {
            "start" -> start(connection, incoming)
            "input" -> connection.run?.input(incoming.value.orEmpty())
            "stop" -> connection.run?.stop()
            else -> send(connection.socket, RunEvent.Error("Unknown message type '${incoming.type}'"))
        }
    }

    override fun afterConnectionClosed(
        session: WebSocketSession,
        status: CloseStatus,
    ) {
        connections.remove(session.id)?.run?.stop()
    }

    private fun start(
        connection: Connection,
        start: Incoming,
    ) {
        val socket = connection.socket
        if (connection.run != null) return close(socket, ALREADY_RUNNING)
        val correlationId =
            listOfNotNull(start.correlationId, socket.handshakeHeaders.getFirst(CorrelationIdFilter.HEADER))
                .firstOrNull { CorrelationIdFilter.isSafe(it) }
                ?: UUID.randomUUID().toString()
        val run =
            runs.create { event ->
                send(socket, event)
                if (event is RunEvent.Done) close(socket, CloseStatus.NORMAL)
            }
        connection.run = run
        if (start.content == null || start.version == null) {
            send(socket, RunEvent.Error("start needs content and version"))
            send(socket, RunEvent.Done(DoneStatus.ERROR))
            return close(socket, CloseStatus.NORMAL)
        }
        log.info("Interactive run {} for {}", correlationId, socket.principal?.name)
        run.start(start.content, start.version, correlationId)
    }

    private fun send(
        socket: WebSocketSession,
        event: RunEvent,
    ) {
        if (!socket.isOpen) return
        try {
            socket.sendMessage(TextMessage(json.writeValueAsString(event.toMessage())))
        } catch (e: Exception) {
            // Closed meanwhile, or the client is too slow (the decorator closes it): the run ends with the socket
            log.debug("Could not send to the socket", e)
        }
    }

    private fun close(
        socket: WebSocketSession,
        status: CloseStatus,
    ) {
        try {
            socket.close(status)
        } catch (e: IOException) {
            log.debug("Could not close the socket", e)
        }
    }

    private fun RunEvent.toMessage(): Map<String, Any?> =
        when (this) {
            is RunEvent.Output -> mapOf("type" to "output", "value" to value)
            is RunEvent.InputRequest -> mapOf("type" to "input-request", "prompt" to prompt)
            is RunEvent.Error -> mapOf("type" to "error", "message" to message, "diagnostics" to diagnostics)
            is RunEvent.Done -> mapOf("type" to "done", "status" to status.value)
        }

    /** Any message from the client; which fields matter depends on [type]. */
    private data class Incoming(
        val type: String? = null,
        val content: String? = null,
        val version: String? = null,
        val correlationId: String? = null,
        val value: String? = null,
    )

    companion object {
        /** `contracts/websocket.md`: there is already a run in this session. */
        val ALREADY_RUNNING = CloseStatus(4409, "A run is already in progress")

        private const val SEND_TIME_LIMIT_MS = 10_000
        private const val SEND_BUFFER_LIMIT = 512 * 1024
    }
}
