package org.snippet.runner.jobs

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import org.snippet.runner.printscript.RuleConfig

// Messages of `contracts/redis-streams.md`. Each stream entry has the fields
// `type`, `correlationId` and `payload` (JSON, one of the classes below).

object Streams {
    const val JOBS = "snippet:jobs"
    const val RESULTS = "snippet:results"
    const val GROUP = "printscript-runner"

    const val TYPE = "type"
    const val CORRELATION_ID = "correlationId"
    const val PAYLOAD = "payload"
}

enum class JobType { VALIDATE, LINT, FORMAT, TEST }

/** Payload of `snippet:jobs`. Every job has the first four fields; the rest depend on [JobType]. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Job(
    val jobId: String,
    val snippetId: String,
    val version: String,
    val content: String,
    // LINT and FORMAT
    val rules: List<RuleConfig> = emptyList(),
    // TEST
    val testId: String? = null,
    val inputs: List<String> = emptyList(),
    val expectedOutputs: List<String> = emptyList(),
)

enum class ResultStatus { OK, FAILED }

/** Payload of `snippet:results`. [error] only when FAILED, [result] only when OK. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class JobResult(
    val jobId: String,
    val snippetId: String,
    val status: ResultStatus,
    val error: String? = null,
    val result: Any? = null,
)

/** `result` of a TEST job. */
data class TestJobResult(
    val testId: String?,
    val status: String,
    val outputs: List<String>,
)
