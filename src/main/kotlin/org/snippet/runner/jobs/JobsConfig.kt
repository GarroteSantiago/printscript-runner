package org.snippet.runner.jobs

import com.fasterxml.jackson.databind.ObjectMapper
import org.snippet.runner.common.streams.RedisStreamClient
import org.snippet.runner.common.streams.StreamClient
import org.snippet.runner.common.streams.StreamProperties
import org.snippet.runner.common.streams.StreamWorker
import org.snippet.runner.common.streams.StreamWorkerLifecycle
import org.snippet.runner.printscript.PrintScriptRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate

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
    ) = StreamWorker(
        client = client,
        stream = Streams.JOBS,
        group = Streams.GROUP,
        consumer = properties.consumer(),
        handler = JobHandler(runner, client, properties, json),
        properties = properties,
    )

    @Bean
    fun jobWorkerLifecycle(
        worker: StreamWorker,
        properties: StreamProperties,
    ) = StreamWorkerLifecycle(worker, properties)
}
