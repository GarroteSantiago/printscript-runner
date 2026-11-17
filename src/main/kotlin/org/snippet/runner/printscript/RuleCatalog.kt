package org.snippet.runner.printscript

import org.snippet.runner.common.error.BadRequestException

/**
 * The formatter and analyzer options PrintScript understands. Each name is the key the library's
 * JSON config uses (`JsonPrintScriptConfigReader`), so a user's rules translate to a library
 * config one to one. The library itself has no defaults (an absent rule leaves the code as
 * written); the defaults here are the style a new user starts with.
 */
object RuleCatalog {
    private sealed interface Kind {
        /** On or off; the value is ignored. */
        data object Flag : Kind

        data class Number(
            val range: IntRange,
        ) : Kind

        data class Choice(
            val options: List<String>,
        ) : Kind
    }

    private class Rule(
        val name: String,
        val kind: Kind,
        val active: Boolean,
        val value: Any?,
        val description: String,
    )

    private val format =
        listOf(
            Rule("enforce-spacing-around-equals", Kind.Flag, true, null, "One space before and after '=' in assignments"),
            Rule("enforce-no-spacing-around-equals", Kind.Flag, false, null, "No space around '=' in assignments"),
            Rule("mandatory-space-surrounding-operations", Kind.Flag, true, null, "One space around arithmetic operators"),
            Rule(
                "enforce-spacing-before-colon-in-declaration",
                Kind.Flag,
                false,
                null,
                "One space before ':' in declarations",
            ),
            Rule("enforce-spacing-after-colon-in-declaration", Kind.Flag, true, null, "One space after ':' in declarations"),
            Rule(
                "mandatory-single-space-separation",
                Kind.Flag,
                false,
                null,
                "Exactly one space between tokens that no other rule configures",
            ),
            Rule("mandatory-line-break-after-statement", Kind.Flag, true, null, "Each statement on its own line"),
            Rule("line-breaks-after-println", Kind.Number(0..2), false, 1, "Blank lines after each println (0 to 2)"),
            Rule("indent-inside-if", Kind.Number(0..16), true, 4, "Spaces of indentation inside if/else blocks (1.1)"),
            Rule("if-brace-same-line", Kind.Flag, true, null, "Opening brace of if/else on the same line (1.1)"),
            Rule("if-brace-below-line", Kind.Flag, false, null, "Opening brace of if/else on its own line (1.1)"),
        )

    private val lint =
        listOf(
            Rule(
                "identifier_format",
                Kind.Choice(listOf("camel case", "snake case")),
                true,
                "camel case",
                "Naming style of identifiers: 'camel case' or 'snake case'",
            ),
            Rule(
                "mandatory-variable-or-literal-in-println",
                Kind.Flag,
                true,
                null,
                "println only takes an identifier or a literal",
            ),
            Rule(
                "mandatory-variable-or-literal-in-readInput",
                Kind.Flag,
                false,
                null,
                "readInput only takes an identifier or a literal (1.1)",
            ),
        )

    // Pairs the library resolves by silently picking one; asking for both is a mistake.
    private val exclusive =
        listOf(
            "enforce-spacing-around-equals" to "enforce-no-spacing-around-equals",
            "if-brace-same-line" to "if-brace-below-line",
        )

    fun defaults(type: RuleType): List<RuleDefinition> = rulesOf(type).map { RuleDefinition(it.name, it.active, it.value, it.description) }

    /**
     * Validates the user's rules and returns the active ones as library config entries
     * (`name -> true | number | string`). Rules not in the list count as inactive.
     */
    fun resolve(
        type: RuleType,
        configs: List<RuleConfig>,
    ): Map<String, Any> {
        val known = rulesOf(type).associateBy { it.name }
        configs.groupBy { it.name }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            throw BadRequestException("Rule '$it' appears more than once")
        }
        val active =
            configs.filter { it.isActive }.associate { config ->
                val rule = known[config.name] ?: throw BadRequestException("Unknown ${type.label} rule '${config.name}'")
                rule.name to valueOf(rule, config.value)
            }
        exclusive.firstOrNull { (a, b) -> a in active && b in active }?.let { (a, b) ->
            throw BadRequestException("Rules '$a' and '$b' can't both be active")
        }
        return active
    }

    private fun valueOf(
        rule: Rule,
        value: Any?,
    ): Any =
        when (val kind = rule.kind) {
            Kind.Flag -> true

            is Kind.Number -> {
                val number =
                    when (value) {
                        null -> rule.value as Int
                        is Int -> value
                        is Number -> value.toDouble().takeIf { it % 1 == 0.0 }?.toInt()
                        is String -> value.trim().toIntOrNull()
                        else -> null
                    }
                number?.takeIf { it in kind.range }
                    ?: throw BadRequestException(
                        "Rule '${rule.name}' needs an integer between ${kind.range.first} and ${kind.range.last}",
                    )
            }

            is Kind.Choice -> {
                val choice = value ?: rule.value
                choice.takeIf { it in kind.options } as String?
                    ?: throw BadRequestException("Rule '${rule.name}' must be one of ${kind.options.joinToString { "'$it'" }}")
            }
        }

    private fun rulesOf(type: RuleType) =
        when (type) {
            RuleType.FORMAT -> format
            RuleType.LINT -> lint
        }

    private val RuleType.label get() = name.lowercase()
}
