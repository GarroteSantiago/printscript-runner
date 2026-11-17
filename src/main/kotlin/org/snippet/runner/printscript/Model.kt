package org.snippet.runner.printscript

import org.snippet.runner.common.error.UnprocessableContentException

// What the runner speaks, free of library types: the controller (and later the Redis consumer)
// only sees these, so another language would be another adapter producing the same shapes.

/** A problem in user code, 1-based. [rule] is the lint rule that produced it, null for syntax or semantic errors. */
data class Diagnostic(
    val rule: String?,
    val message: String,
    val line: Int,
    val column: Int,
)

/** A rule as the user configured it. [value] is a string or a number, for the rules that take one. */
data class RuleConfig(
    val name: String,
    val isActive: Boolean,
    val value: Any? = null,
)

/** A catalog entry: the rule with its default configuration. */
data class RuleDefinition(
    val name: String,
    val isActive: Boolean,
    val value: Any?,
    val description: String,
)

enum class RuleType { FORMAT, LINT }

data class ValidationResult(
    val valid: Boolean,
    val diagnostics: List<Diagnostic>,
)

data class TestResult(
    val passed: Boolean,
    val outputs: List<String>,
    val errors: List<Diagnostic>,
)

/** The code doesn't parse, so it can't be formatted or linted. Rendered as 422 with the diagnostics. */
class InvalidCodeException(
    val diagnostics: List<Diagnostic>,
) : UnprocessableContentException("The code is not valid PrintScript", mapOf("diagnostics" to diagnostics))
