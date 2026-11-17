package org.snippet.runner.printscript

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.snippet.runner.common.error.BadRequestException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrintScriptRunnerTest {
    private val runner = PrintScriptRunner()

    private fun active(vararg names: String) = names.map { RuleConfig(it, true) }

    @Test
    fun `valid code validates in both versions`() {
        for (version in listOf("1.0", "1.1")) {
            val result = runner.validate("let x: number = 5;\nprintln(x);", version)
            assertTrue(result.valid, version)
            assertEquals(emptyList(), result.diagnostics)
        }
    }

    @Test
    fun `syntax errors come back with position and no rule`() {
        val result = runner.validate("let x: number = 5;\nlet y: number = ;", "1.0")

        assertFalse(result.valid)
        val diagnostic = result.diagnostics.single()
        assertNull(diagnostic.rule)
        assertEquals(2, diagnostic.line)
        assertTrue(diagnostic.column >= 1)
    }

    @Test
    fun `features of 1_1 are invalid in 1_0`() {
        val code = "const x: boolean = true;\nif (x) {\n  println(\"yes\");\n}"

        assertTrue(runner.validate(code, "1.1").valid)
        assertFalse(runner.validate(code, "1.0").valid)
    }

    @Test
    fun `semantic errors make the code invalid`() {
        val result = runner.validate("let x: number = \"text\";", "1.0")

        assertFalse(result.valid)
        assertNull(result.diagnostics.single().rule)
    }

    @Test
    fun `unknown versions are rejected`() {
        assertThrows<BadRequestException> { runner.validate("println(1);", "2.0") }
        assertThrows<BadRequestException> { runner.validate("println(1);", "1.0.0") }
    }

    @Test
    fun `formats with the given rules`() {
        val code = "let x:number=5;let y:number=x+1;println(y);"

        val formatted =
            runner.format(
                code,
                "1.0",
                active(
                    "enforce-spacing-around-equals",
                    "enforce-spacing-after-colon-in-declaration",
                    "mandatory-space-surrounding-operations",
                    "mandatory-line-break-after-statement",
                ),
            )

        assertEquals("let x: number = 5;\nlet y: number = x + 1;\nprintln(y);", formatted)
    }

    @Test
    fun `inactive or missing rules leave the code as written`() {
        val code = "let x:number=5;"

        assertEquals(code, runner.format(code, "1.0", listOf(RuleConfig("enforce-spacing-around-equals", false))))
        assertEquals(code, runner.format(code, "1.0", emptyList()))
    }

    @Test
    fun `numeric rules take their value`() {
        val code = "if (true) {\nprintln(1);\n}"

        val formatted = runner.format(code, "1.1", listOf(RuleConfig("indent-inside-if", true, 2)))

        assertEquals("if (true) {\n  println(1);\n}", formatted)
    }

    @Test
    fun `formatting code with syntax errors fails with its diagnostics`() {
        val exception = assertThrows<InvalidCodeException> { runner.format("let x: number = ;", "1.0", emptyList()) }

        assertNull(exception.diagnostics.single().rule)
    }

    @Test
    fun `bad rules are rejected`() {
        assertThrows<BadRequestException> { runner.format("println(1);", "1.0", active("no-such-rule")) }
        assertThrows<BadRequestException> {
            runner.format("println(1);", "1.0", active("if-brace-same-line", "if-brace-below-line"))
        }
        assertThrows<BadRequestException> {
            runner.format("println(1);", "1.0", listOf(RuleConfig("line-breaks-after-println", true, 9)))
        }
        assertThrows<BadRequestException> {
            runner.lint("println(1);", "1.0", listOf(RuleConfig("identifier_format", true, "kebab case")))
        }
    }

    @Test
    fun `lint reports each violation with its rule`() {
        val code = "let my_var: number = 1;\nprintln(my_var + 1);"

        val violations =
            runner.lint(
                code,
                "1.0",
                listOf(
                    RuleConfig("identifier_format", true, "camel case"),
                    RuleConfig("mandatory-variable-or-literal-in-println", true),
                ),
            )

        assertEquals(
            listOf("identifier_format" to 1, "mandatory-variable-or-literal-in-println" to 2),
            violations.map { it.rule to it.line },
        )
    }

    @Test
    fun `lint without violations returns an empty list`() {
        val violations = runner.lint("let myVar: number = 1;\nprintln(myVar);", "1.0", RuleCatalog.defaults(RuleType.LINT).toConfigs())

        assertEquals(emptyList(), violations)
    }

    @Test
    fun `linting invalid code fails with its diagnostics`() {
        assertThrows<InvalidCodeException> { runner.lint("let x: number = ;", "1.0", emptyList()) }
    }

    @Test
    fun `a test passes when the outputs match`() {
        val code = "let name: string = readInput(\"Name?\");\nprintln(\"Hello \" + name);\nprintln(1 + 1);"

        val result = runner.test(code, "1.1", listOf("Ada"), listOf("Hello Ada", "2"))

        assertTrue(result.passed)
        assertEquals(listOf("Hello Ada", "2"), result.outputs)
        assertEquals(emptyList(), result.errors)
    }

    @Test
    fun `a test fails when the outputs differ`() {
        val result = runner.test("println(1);\nprintln(2);", "1.0", emptyList(), listOf("1"))

        assertFalse(result.passed)
        assertEquals(listOf("1", "2"), result.outputs)
        assertEquals(emptyList(), result.errors)
    }

    @Test
    fun `asking for more input than given fails instead of waiting`() {
        val code = "let a: string = readInput(\"a\");\nprintln(a);\nlet b: string = readInput(\"b\");\nprintln(b);"

        val result = runner.test(code, "1.1", listOf("first"), listOf("first", "second"))

        assertFalse(result.passed)
        assertEquals(listOf("first"), result.outputs)
        assertEquals(3, result.errors.single().line)
    }

    @Test
    fun `a test of code that does not parse fails with errors`() {
        val result = runner.test("println(;", "1.0", emptyList(), emptyList())

        assertFalse(result.passed)
        assertTrue(result.errors.isNotEmpty())
    }

    private fun List<RuleDefinition>.toConfigs() = map { RuleConfig(it.name, it.isActive, it.value) }
}
