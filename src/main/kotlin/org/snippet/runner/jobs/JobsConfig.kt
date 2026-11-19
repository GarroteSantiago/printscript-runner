package org.snippet.runner.jobs

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.snippet.runner.common.streams.RedisStreamClient
import org.snippet.runner.common.streams.StreamClient
import org.snippet.runner.common.streams.StreamMetrics
import org.snippet.runner.common.streams.StreamMetricsLifecycle
import org.snippet.runner.common.streams.StreamProperties
import org.snippet.runner.common.streams.StreamWorker
import org.snippet.runner.common.streams.StreamWorkerLifecycle
import org.snippet.runner.common.streams.WatchedStream
import org.snippet.runner.printscript.PrintScriptRunner
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration

/** Consumer of `snippet:jobs` (group `printscript-runner`, one consumer per hostname). Off with `streams.enabled=false`. */
@Configuration
@EnableConfigurationProperties(StreamProperties::class)
@ConditionalOnProperty("streams.enabled", havingValue = "true", matchIfMissing = true)
class JobsConfig {
    @Bean
    fun streamClient(redis: StringRedisTemplate): StreamClient = RedisStreamClient(redis)

    @Bean
    fun jobWorker(
        client: StreamClient,
        runner: PrintScriptRunner,
        properties: StreamProperties,
        json: ObjectMapper,
        meters: MeterRegistry,
    ) = StreamWorker(
        client = client,
        stream = Streams.JOBS,
        group = Streams.GROUP,
        consumer = properties.consumer(),
        handler = JobHandler(runner, client, properties, json),
        properties = properties,
        meters = meters,
    )

    @Bean
    fun jobWorkerLifecycle(
        worker: StreamWorker,
        properties: StreamProperties,
    ) = StreamWorkerLifecycle(worker, properties)

    /** Backlog of both streams: the results too, so it shows even while every Snippet Service is down. */
    @Bean
    fun streamMetrics(
        client: StreamClient,
        meters: MeterRegistry,
    ) = StreamMetrics(
        client,
        listOf(WatchedStream(Streams.JOBS, Streams.GROUP), WatchedStream(Streams.RESULTS, Streams.SNIPPET_GROUP)),
        meters,
    )

    @Bean
    fun streamMetricsLifecycle(
        metrics: StreamMetrics,
        @Value("\${observability.stream-metrics-interval:15s}") interval: Duration,
    ) = StreamMetricsLifecycle(metrics, interval)
}
