package org.snippet.runner.printscript

import org.snippet.runner.common.error.BadRequestException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

data class CodeRequest(
    val content: String,
    val version: String,
)

data class RulesRequest(
    val content: String,
    val version: String,
    val rules: List<RuleConfig>,
)

data class TestRequest(
    val content: String,
    val version: String,
    val inputs: List<String>,
    val expectedOutputs: List<String>,
)

data class VersionsResponse(
    val parserVersion: String,
    val language: String,
    val versions: List<String>,
    val latest: String,
)

data class FormatResponse(
    val content: String,
)

data class LintResponse(
    val violations: List<Diagnostic>,
)

data class TestResponse(
    val status: String,
    val outputs: List<String>,
    val errors: List<Diagnostic>,
)

/** Synchronous endpoints of `contracts/printscript-runner.openapi.yaml`. */
@RestController
class PrintScriptController(
    private val runner: PrintScriptRunner,
) {
    @GetMapping("/versions")
    fun versions() = VersionsResponse(runner.parserVersion, "printscript", runner.versions, runner.latestVersion)

    @GetMapping("/rules/{ruleType}")
    fun rules(
        @PathVariable ruleType: String,
    ): List<RuleDefinition> {
        val type =
            RuleType.entries.firstOrNull { it.name.lowercase() == ruleType }
                ?: throw BadRequestException("Unknown rule type '$ruleType' (expected format or lint)")
        return RuleCatalog.defaults(type)
    }

    @PostMapping("/validate")
    fun validate(
        @RequestBody request: CodeRequest,
    ): ValidationResult = runner.validate(request.content, request.version)

    @PostMapping("/format")
    fun format(
        @RequestBody request: RulesRequest,
    ) = FormatResponse(runner.format(request.content, request.version, request.rules))

    @PostMapping("/lint")
    fun lint(
        @RequestBody request: RulesRequest,
    ) = LintResponse(runner.lint(request.content, request.version, request.rules))

    @PostMapping("/test")
    fun test(
        @RequestBody request: TestRequest,
    ): TestResponse {
        val result = runner.test(request.content, request.version, request.inputs, request.expectedOutputs)
        return TestResponse(if (result.passed) "success" else "fail", result.outputs, result.errors)
    }
}
