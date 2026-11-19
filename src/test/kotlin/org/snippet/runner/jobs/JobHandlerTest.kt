package org.snippet.runner.jobs

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.snippet.runner.common.logging.CorrelationIdFilter
import org.snippet.runner.common.streams.StreamClient
import org.snippet.runner.common.streams.StreamProperties
import org.snippet.runner.common.streams.StreamWorker
import org.snippet.runner.printscript.PrintScriptRunner
import org.snippet.runner.printscript.RuleConfig
import org.snippet.runner.support.FakeStreamClient
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobHandlerTest {
    private val json: ObjectMapper = jacksonObjectMapper()
    private val client = FakeStreamClient()
    private val properties = StreamProperties()
    private val handler = JobHandler(PrintScriptRunner(), client, properties, json)
    private val worker =
        StreamWorker(client, Streams.JOBS, Streams.GROUP, "runner-1", handler, properties).apply { createGroup() }

    private fun send(
        type: String,
        payload: Map<String, Any?>,
        correlationId: String = "corr-1",
    ) {
        client.add(
            Streams.JOBS,
            mapOf(Streams.TYPE to type, Streams.CORRELATION_ID to correlationId, Streams.PAYLOAD to json.writeValueAsString(payload)),
            10_000,
        )
    }

    private fun job(
        content: String,
        vararg extra: Pair<String, Any?>,
    ) = mapOf("jobId" to "job-1", "snippetId" to "snippet-1", "version" to "1.1", "content" to content) + extra

    private fun results() = client.entries(Streams.RESULTS)

    private fun onlyResult(): Pair<Map<String, String>, JsonNode> {
        val entry = results().single()
        return entry.fields to json.readTree(entry.fields.getValue(Streams.PAYLOAD))
    }

    @Test
    fun `validate answers whether the code parses, keeping type and correlation id`() {
        send("VALIDATE", job("let x: number = ;"), correlationId = "abc-123")
        worker.pollOnce()

        val (fields, payload) = onlyResult()
        assertEquals("VALIDATE", fields[Streams.TYPE])
        assertEquals("abc-123", fields[Streams.CORRELATION_ID])
        assertEquals("job-1", payload["jobId"].asText())
        assertEquals("snippet-1", payload["snippetId"].asText())
        assertEquals("OK", payload["status"].asText())
        assertFalse(payload.has("error"))
        assertFalse(payload["result"]["valid"].asBoolean())
        assertTrue(payload["result"]["diagnostics"][0]["line"].asInt() >= 1)
        // Acked after publishing
        assertTrue(client.pending(Streams.JOBS, Streams.GROUP).isEmpty())
    }

    @Test
    fun `lint reports the violations of the given rules`() {
        val rules = listOf(RuleConfig("identifier_format", true, "camel case"))
        send("LINT", job("let my_var: number = 5;\nprintln(my_var);", "rules" to rules))
        worker.pollOnce()

        val (_, payload) = onlyResult()
        val violations = payload["result"]["violations"]
        assertTrue(violations.size() > 0)
        assertEquals("identifier_format", violations[0]["rule"].asText())
    }

    @Test
    fun `format returns the formatted content`() {
        val rules = listOf(RuleConfig("enforce-spacing-around-equals", true), RuleConfig("mandatory-line-break-after-statement", true))
        send("FORMAT", job("let x:number=5;println(x);", "rules" to rules))
        worker.pollOnce()

        val (_, payload) = onlyResult()
        assertEquals("OK", payload["status"].asText())
        assertTrue(payload["result"]["content"].asText().contains("= 5;"))
    }

    @Test
    fun `test compares the outputs and carries the test id`() {
        send(
            "TEST",
            job("println(\"hi\");", "testId" to "test-1", "inputs" to emptyList<String>(), "expectedOutputs" to listOf("hi")),
        )
        send(
            "TEST",
            job("println(\"hi\");", "testId" to "test-2", "inputs" to emptyList<String>(), "expectedOutputs" to listOf("bye")),
        )
        worker.pollOnce()

        val (first, second) = results().map { json.readTree(it.fields.getValue(Streams.PAYLOAD))["result"] }
        assertEquals("test-1", first["testId"].asText())
        assertEquals("success", first["status"].asText())
        assertEquals("hi", first["outputs"][0].asText())
        assertEquals("fail", second["status"].asText())
    }

    @Test
    fun `code the job can't handle is a FAILED result, not a retry`() {
        send("LINT", job("let x: number = ;"))
        send("FORMAT", job("println(1);", "rules" to listOf(RuleConfig("no-such-rule", true))))
        send("VALIDATE", job("println(1);") + ("version" to "9.9"))
        worker.pollOnce()

        val payloads = results().map { json.readTree(it.fields.getValue(Streams.PAYLOAD)) }
        assertEquals(3, payloads.size)
        payloads.forEach {
            assertEquals("FAILED", it["status"].asText())
            assertTrue(it["error"].asText().isNotBlank())
            assertNull(it["result"])
        }
        assertTrue(client.pending(Streams.JOBS, Streams.GROUP).isEmpty())
    }

    @Test
    fun `a job that keeps failing ends as a FAILED result after more than three deliveries`() {
        send("VALIDATE", job("println(1);"))
        // Publishing the result fails, so the job is never acked
        client.failAdd = true
        worker.pollOnce()
        repeat(2) {
            client.advance(Duration.ofSeconds(61))
            worker.reclaimOnce()
        }
        client.failAdd = false
        client.advance(Duration.ofSeconds(61))
        worker.reclaimOnce()

        val (fields, payload) = onlyResult()
        assertEquals("VALIDATE", fields[Streams.TYPE])
        assertEquals("FAILED", payload["status"].asText())
        assertTrue(payload["error"].asText().contains("4 deliveries"))
        assertTrue(client.pending(Streams.JOBS, Streams.GROUP).isEmpty())
    }

    @Test
    fun `a malformed job is dropped without an answer`() {
        client.add(Streams.JOBS, mapOf(Streams.TYPE to "LINT", Streams.PAYLOAD to "not json"), 10_000)
        client.add(Streams.JOBS, mapOf(Streams.TYPE to "EXPLODE", Streams.PAYLOAD to "{}"), 10_000)
        worker.pollOnce()

        assertTrue(results().isEmpty())
        assertTrue(client.pending(Streams.JOBS, Streams.GROUP).isEmpty())
    }

    @Test
    fun `the job's correlation id is in the MDC while it runs and is cleared afterwards`() {
        val seen = mutableListOf<String?>()
        val recording =
            object : StreamClient by client {
                override fun add(
                    stream: String,
                    fields: Map<String, String>,
                    maxLength: Long,
                ): String {
                    seen += MDC.get(CorrelationIdFilter.MDC_KEY)
                    return client.add(stream, fields, maxLength)
                }
            }
        val worker =
            StreamWorker(
                client,
                Streams.JOBS,
                Streams.GROUP,
                "runner-1",
                JobHandler(PrintScriptRunner(), recording, properties, json),
                properties,
            )
        send("VALIDATE", job("println(1);"), correlationId = "abc-123")
        worker.pollOnce()

        assertEquals(listOf<String?>("abc-123"), seen)
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY))
    }

    @Test
    fun `a job without a correlation id gets a new one in its result`() {
        send("VALIDATE", job("println(1);"), correlationId = "")
        worker.pollOnce()

        val (fields, _) = onlyResult()
        assertTrue(fields.getValue(Streams.CORRELATION_ID).isNotBlank())
    }
}
