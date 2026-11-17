package org.snippet.runner.printscript

import com.fasterxml.jackson.databind.ObjectMapper
import org.printscript.diagnostics.Phase
import org.printscript.toolchain.CommandResult
import org.printscript.toolchain.JsonPrintScriptConfigReader
import org.printscript.toolchain.LanguageVersion
import org.printscript.toolchain.PrintScript
import org.printscript.toolchain.ProgressReporter
import org.snippet.runner.common.error.BadRequestException
import org.springframework.stereotype.Component
import java.io.StringReader
import java.util.Optional
import org.printscript.diagnostics.Diagnostic as LibraryDiagnostic

/**
 * Adapter over the PrintScript library (`org.printscript:toolchain`). The only class that sees
 * library types: everything going in and out is the runner's own model.
 */
@Component
class PrintScriptRunner {
    private val printScript = PrintScript()
    private val configReader = JsonPrintScriptConfigReader()
    private val json = ObjectMapper()

    val versions = listOf("1.0", "1.1")
    val latestVersion = "1.1"

    /** Syntax and semantic check, without running the code. */
    fun validate(
        content: String,
        version: String,
    ): ValidationResult {
        val result = printScript.validate(StringReader(content), parseVersion(version), ProgressReporter.NONE)
        return ValidationResult(result.isSuccess, result.diagnostics.map { it.toDiagnostic() })
    }

    /** Formats with the active rules. Code with syntax errors is rejected; semantic errors don't stop formatting. */
    fun format(
        content: String,
        version: String,
        rules: List<RuleConfig>,
    ): String {
        val languageVersion = parseVersion(version)
        val config = configReader.readFormatterConfig(toJson(RuleCatalog.resolve(RuleType.FORMAT, rules)))
        // The formatter works on tokens and only catches lexical errors, so parse first
        val syntaxErrors =
            printScript
                .validate(StringReader(content), languageVersion, ProgressReporter.NONE)
                .diagnostics
                .filter { it.phase() == Phase.SYNTAX }
        if (syntaxErrors.isNotEmpty()) throw InvalidCodeException(syntaxErrors.map { it.toDiagnostic() })

        val output = StringBuilder()
        printScript
            .format(StringReader(content), languageVersion, config, output, ProgressReporter.NONE)
            .orInvalidCode()
        return output.toString()
    }

    /** Violations of the active rules, sorted by position. Code that isn't valid can't be linted. */
    fun lint(
        content: String,
        version: String,
        rules: List<RuleConfig>,
    ): List<Diagnostic> {
        val languageVersion = parseVersion(version)
        val active = RuleCatalog.resolve(RuleType.LINT, rules)
        printScript.validate(StringReader(content), languageVersion, ProgressReporter.NONE).orInvalidCode()

        // The library doesn't say which rule a violation comes from, so each rule runs on its own
        return active
            .flatMap { (name, value) ->
                val violations = mutableListOf<Diagnostic>()
                printScript
                    .analyze(
                        StringReader(content),
                        languageVersion,
                        configReader.readAnalyzerConfig(toJson(mapOf(name to value))),
                        { violations += it.toDiagnostic(rule = name) },
                        ProgressReporter.NONE,
                    ).orInvalidCode()
                violations
            }.sortedWith(compareBy({ it.line }, { it.column }))
    }

    /**
     * Runs the code feeding [inputs] to `readInput` in order and compares each `println` with
     * [expectedOutputs]. Asking for more input than given fails the run instead of waiting.
     */
    fun test(
        content: String,
        version: String,
        inputs: List<String>,
        expectedOutputs: List<String>,
    ): TestResult {
        val languageVersion = parseVersion(version)
        val pending = ArrayDeque(inputs)
        val outputs = mutableListOf<String>()
        val result =
            printScript.execute(
                StringReader(content),
                languageVersion,
                { outputs += it },
                // The interpreter turns this exception into a runtime error at the readInput call
                {
                    pending.removeFirstOrNull() ?: throw UnsupportedOperationException(
                        "readInput needs more inputs than the test provides",
                    )
                },
                { Optional.empty() },
                ProgressReporter.NONE,
            )
        val errors = result.diagnostics.map { it.toDiagnostic() }
        return TestResult(result.isSuccess && outputs == expectedOutputs, outputs, errors)
    }

    private fun parseVersion(version: String): LanguageVersion =
        when (version) {
            "1.0" -> LanguageVersion.V1_0_0
            "1.1" -> LanguageVersion.V1_1_0
            else -> throw BadRequestException("Unsupported PrintScript version '$version' (supported: ${versions.joinToString()})")
        }

    private fun toJson(config: Map<String, Any>) = json.writeValueAsBytes(config).inputStream()

    private fun CommandResult<*>.orInvalidCode() {
        if (!isSuccess) throw InvalidCodeException(diagnostics.map { it.toDiagnostic() })
    }

    private fun LibraryDiagnostic.toDiagnostic(rule: String? = null) =
        Diagnostic(rule, message(), span().start().row(), span().start().column())
}
