package org.snippet.runner.jobs

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.snippet.runner.common.error.ApiException
import org.snippet.runner.common.logging.CorrelationIdFilter
import org.snippet.runner.common.streams.StreamClient
import org.snippet.runner.common.streams.StreamEntry
import org.snippet.runner.common.streams.StreamHandler
import org.snippet.runner.common.streams.StreamProperties
import org.snippet.runner.printscript.FormatResponse
import org.snippet.runner.printscript.LintResponse
import org.snippet.runner.printscript.PrintScriptRunner
import java.util.UUID

/**
 * Runs each job of `snippet:jobs` with the same adapter the HTTP endpoints use and publishes its
 * result to `snippet:results`. The worker acks the job only after this returns, i.e. after the
 * result is in Redis.
 *
 * Bad input (an unsupported version, invalid rules, code that doesn't parse for LINT/FORMAT) is a
 * `FAILED` result right away: retrying gives the same answer. Anything else (Redis down, a bug)
 * propagates, so the job stays pending and is retried until it's discarded.
 */
class JobHandler(
    private val runner: PrintScriptRunner,
    private val streams: StreamClient,
    private val properties: StreamProperties,
    private val json: ObjectMapper,
) : StreamHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(entry: StreamEntry) {
        withCorrelationId(entry) { correlationId ->
            val message = parse(entry) ?: return@withCorrelationId
            val (type, job) = message
            val result =
                try {
                    JobResult(job.jobId, job.snippetId, ResultStatus.OK, result = run(type, job))
                } catch (e: ApiException) {
                    log.info("{} job {} of snippet {} failed: {}", type, job.jobId, job.snippetId, e.message)
                    JobResult(job.jobId, job.snippetId, ResultStatus.FAILED, error = e.message)
                }
            publish(correlationId, type, result)
        }
    }

    override fun discard(
        entry: StreamEntry,
        deliveries: Long,
    ) {
        withCorrelationId(entry) { correlationId ->
            val (type, job) = parse(entry) ?: return@withCorrelationId
            val error = "Gave up after $deliveries deliveries"
            publish(correlationId, type, JobResult(job.jobId, job.snippetId, ResultStatus.FAILED, error = error))
        }
    }

    private fun run(
        type: JobType,
        job: Job,
    ): Any =
        when (type) {
            JobType.VALIDATE -> {
                runner.validate(job.content, job.version)
            }

            JobType.LINT -> {
                LintResponse(runner.lint(job.content, job.version, job.rules))
            }

            JobType.FORMAT -> {
                FormatResponse(runner.format(job.content, job.version, job.rules))
            }

            JobType.TEST -> {
                val result = runner.test(job.content, job.version, job.inputs, job.expectedOutputs)
                TestJobResult(job.testId, if (result.passed) "success" else "fail", result.outputs)
            }
        }

    private fun publish(
        correlationId: String,
        type: JobType,
        result: JobResult,
    ) {
        val fields =
            mapOf(
                Streams.TYPE to type.name,
                Streams.CORRELATION_ID to correlationId,
                Streams.PAYLOAD to json.writeValueAsString(result),
            )
        streams.add(Streams.RESULTS, fields, properties.maxLength)
        log.info("{} job {} of snippet {}: {}", type, result.jobId, result.snippetId, result.status)
    }

    /** null (logged) when the entry can't be understood: without a jobId there is nobody to answer, so it's just acked. */
    private fun parse(entry: StreamEntry): Pair<JobType, Job>? {
        val type = JobType.entries.find { it.name == entry.fields[Streams.TYPE] }
        val job = runCatching { json.readValue<Job>(entry.fields[Streams.PAYLOAD].orEmpty()) }.getOrNull()
        if (type == null || job == null) {
            log.error("Dropping malformed job {} (type {})", entry.id, entry.fields[Streams.TYPE])
            return null
        }
        return type to job
    }

    /**
     * Runs [block] with the job's correlation id in the MDC, so its log lines and its result carry it.
     * A job without one (it shouldn't happen) gets a new one, so its result can still be followed.
     */
    private inline fun withCorrelationId(
        entry: StreamEntry,
        block: (String) -> Unit,
    ) {
        val correlationId = entry.fields[Streams.CORRELATION_ID]?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId)
        try {
            block(correlationId)
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY)
        }
    }
}
