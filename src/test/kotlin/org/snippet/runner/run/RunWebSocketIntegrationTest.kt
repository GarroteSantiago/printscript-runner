package org.snippet.runner.run

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.net.URI
import java.time.Instant
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The real endpoint over a real socket, with a token decoder that accepts one fixed token. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RunWebSocketIntegrationTest(
    @param:Autowired private val json: ObjectMapper,
) {
    @LocalServerPort
    private var port = 0

    @TestConfiguration
    class Tokens {
        @Bean
        fun jwtDecoder() =
            JwtDecoder { token ->
                if (token != TOKEN) throw BadJwtException("bad token")
                Jwt
                    .withTokenValue(token)
                    .header("alg", "none")
                    .subject("auth0|alice")
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(60))
                    .build()
            }
    }

    private val sessions = mutableListOf<WebSocketSession>()

    @AfterEach
    fun close() = sessions.forEach { runCatching { it.close() } }

    /** A client socket that keeps every message and the close status. */
    private inner class Client(
        token: String? = TOKEN,
    ) : TextWebSocketHandler() {
        val messages = LinkedBlockingQueue<Map<*, *>>()
        val closed = LinkedBlockingQueue<CloseStatus>()
        val session: WebSocketSession

        init {
            val headers = WebSocketHttpHeaders()
            token?.let { headers.setBearerAuth(it) }
            session =
                StandardWebSocketClient()
                    .execute(this, headers, URI("ws://localhost:$port/ws/run"))
                    .get(5, TimeUnit.SECONDS)
                    .also { sessions += it }
        }

        override fun handleTextMessage(
            session: WebSocketSession,
            message: TextMessage,
        ) {
            messages.put(json.readValue(message.payload, Map::class.java))
        }

        override fun afterConnectionClosed(
            session: WebSocketSession,
            status: CloseStatus,
        ) {
            closed.put(status)
        }

        fun send(vararg fields: Pair<String, String>) = session.sendMessage(TextMessage(json.writeValueAsString(mapOf(*fields))))

        fun start(content: String) = send("type" to "start", "content" to content, "version" to "1.1", "correlationId" to "corr-1")

        fun next(): Map<*, *> = messages.poll(5, TimeUnit.SECONDS) ?: error("no message in 5 s")

        fun closeStatus(): CloseStatus = closed.poll(5, TimeUnit.SECONDS) ?: error("not closed in 5 s")
    }

    companion object {
        const val TOKEN = "alice-token"
    }

    @Test
    fun `the handshake needs a valid token`() {
        assertThrows<ExecutionException> { Client(token = null) }
        assertThrows<ExecutionException> { Client(token = "forged") }
    }

    @Test
    fun `runs interactively and closes after done`() {
        val client = Client()
        client.start("println(\"hi\");\nlet name: string = readInput(\"Name?\");\nprintln(\"Hello \" + name);")

        assertEquals(mapOf("type" to "output", "value" to "hi"), client.next())
        assertEquals(mapOf("type" to "input-request", "prompt" to "Name?"), client.next())
        client.send("type" to "input", "value" to "Ada")
        assertEquals(mapOf("type" to "output", "value" to "Hello Ada"), client.next())
        assertEquals(mapOf("type" to "done", "status" to "ok"), client.next())
        assertEquals(CloseStatus.NORMAL.code, client.closeStatus().code)
    }

    @Test
    fun `invalid code sends the diagnostics`() {
        val client = Client()
        client.start("let y: number = ;")

        val error = client.next()
        assertEquals("error", error["type"])
        assertEquals(1, ((error["diagnostics"] as List<*>).single() as Map<*, *>)["line"])
        assertEquals(mapOf("type" to "done", "status" to "error"), client.next())
    }

    @Test
    fun `stop ends a run waiting for input`() {
        val client = Client()
        client.start("let a: string = readInput(\"a\");")
        assertEquals("input-request", client.next()["type"])

        client.send("type" to "stop")

        assertEquals(mapOf("type" to "done", "status" to "stopped"), client.next())
        assertEquals(CloseStatus.NORMAL.code, client.closeStatus().code)
    }

    @Test
    fun `a second start closes with 4409`() {
        val client = Client()
        client.start("let a: string = readInput(\"a\");")
        assertEquals("input-request", client.next()["type"])

        client.start("println(1);")

        assertEquals(4409, client.closeStatus().code)
    }

    @Test
    fun `a large snippet fits in the start message`() {
        val client = Client()
        val content = (1..2000).joinToString("\n") { "println($it);" }
        client.start(content)

        repeat(2000) { assertEquals("output", client.next()["type"]) }
        assertEquals(mapOf("type" to "done", "status" to "ok"), client.next())
        assertTrue(client.messages.isEmpty())
    }
}
