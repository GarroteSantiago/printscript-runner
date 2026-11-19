package org.snippet.runner.jobs

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.snippet.runner.common.streams.RedisStreamClient
import org.snippet.runner.common.streams.StreamClient
import org.snippet.runner.common.streams.StreamStats
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.Range
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The consumer of `snippet:jobs` against a real Redis: XREADGROUP, XADD with trimming, XACK, XAUTOCLAIM and XPENDING. */
@SpringBootTest
@ActiveProfiles("test")
// Needs Docker for Redis: skipped where there is none, runs in CI.
@Testcontainers(disabledWithoutDocker = true)
class RedisJobsIntegrationTest(
    @param:Autowired private val redis: StringRedisTemplate,
    @param:Autowired private val streams: StreamClient,
    @param:Autowired private val json: ObjectMapper,
    @param:Autowired private val meters: MeterRegistry,
) {
    companion object {
        @Container
        @JvmStatic
        val redisContainer: GenericContainer<*> = GenericContainer("redis:7-alpine").withExposedPorts(6379)

        @DynamicPropertySource
        @JvmStatic
        fun redisProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host") { redisContainer.host }
            registry.add("spring.data.redis.port") { redisContainer.getMappedPort(6379) }
            registry.add("streams.enabled") { true }
            registry.add("streams.consumer-name") { "runner-test" }
            registry.add("streams.block") { "200ms" }
        }
    }

    private fun waitFor(
        timeout: Duration = Duration.ofSeconds(10),
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out" }
            Thread.sleep(50)
        }
    }

    private fun resultFor(jobId: String) =
        redis
            .opsForStream<String, String>()
            .range(Streams.RESULTS, Range.unbounded())
            .orEmpty()
            .map { it.value }
            .find { json.readTree(it[Streams.PAYLOAD].toString())["jobId"].asText() == jobId }

    @Test
    fun `processes a job, publishes its result and acks it`() {
        // Idempotent: the app already created it, or is about to
        streams.createGroup(Streams.JOBS, Streams.GROUP)
        streams.createGroup(Streams.JOBS, Streams.GROUP)
        val jobId = UUID.randomUUID().toString()
        val payload = """{"jobId":"$jobId","snippetId":"s-1","version":"1.1","content":"println(1);"}"""
        streams.add(Streams.JOBS, mapOf("type" to "VALIDATE", "correlationId" to "corr-it", "payload" to payload), 10_000)

        waitFor { resultFor(jobId) != null }

        val result = resultFor(jobId)!!
        assertEquals("VALIDATE", result[Streams.TYPE])
        assertEquals("corr-it", result[Streams.CORRELATION_ID])
        val body = json.readTree(result[Streams.PAYLOAD].toString())
        assertEquals("OK", body["status"].asText())
        assertTrue(body["result"]["valid"].asBoolean())
        waitFor { redis.opsForStream<String, String>().pending(Streams.JOBS, Streams.GROUP)?.totalPendingMessages == 0L }
    }

    @Test
    fun `claims idle entries and counts their deliveries`() {
        val client = RedisStreamClient(redis)
        val stream = "test:claim:${UUID.randomUUID()}"
        client.createGroup(stream, "g")
        val id = client.add(stream, mapOf("type" to "LINT"), 10_000)

        assertEquals(listOf(id), client.readNew(stream, "g", "crashed", 10, Duration.ofMillis(100)).map { it.id })
        assertEquals(1, client.deliveries(stream, "g", id))

        Thread.sleep(150)
        val claimed = client.autoClaim(stream, "g", "me", Duration.ofMillis(100), "0-0", 10)
        assertEquals(listOf(id), claimed.entries.map { it.id })
        assertEquals("LINT", claimed.entries.single().fields["type"])
        assertEquals("0-0", claimed.next)
        assertEquals(2, client.deliveries(stream, "g", id))

        client.ack(stream, "g", id)
        assertEquals(0, client.deliveries(stream, "g", id))
        assertTrue(client.autoClaim(stream, "g", "me", Duration.ZERO, "0-0", 10).entries.isEmpty())
    }

    @Test
    fun `trims the stream to about max-length`() {
        val client = RedisStreamClient(redis)
        val stream = "test:trim:${UUID.randomUUID()}"
        repeat(500) { client.add(stream, mapOf("n" to "$it"), 100) }

        // `~` trims whole nodes, so it keeps a bit more than asked, never all
        assertTrue(redis.opsForStream<String, String>().size(stream)!! < 500)
    }

    @Test
    fun `stream stats come from XLEN and XINFO GROUPS, and both streams have gauges`() {
        val stream = "stats-${UUID.randomUUID()}"
        assertEquals(StreamStats(0, null, null), streams.stats(stream, "g"))
        streams.createGroup(stream, "g")
        streams.add(stream, mapOf(Streams.TYPE to "LINT"), 10_000)
        streams.readNew(stream, "g", "c", 10, Duration.ofMillis(10))
        repeat(2) { streams.add(stream, mapOf(Streams.TYPE to "LINT"), 10_000) }
        assertEquals(StreamStats(3, 2, 1), streams.stats(stream, "g"))

        listOf(Streams.JOBS to Streams.GROUP, Streams.RESULTS to Streams.SNIPPET_GROUP).forEach { (s, group) ->
            assertNotNull(meters.find("redis.stream.lag").tags("stream", s, "group", group).gauge())
        }
    }
}
