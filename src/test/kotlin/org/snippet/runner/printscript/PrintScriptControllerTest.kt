package org.snippet.runner.printscript

import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.snippet.runner.common.security.SecurityConfig
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/** The HTTP contract, over the real adapter (it's pure and fast, no need to mock it). */
@WebMvcTest(controllers = [PrintScriptController::class])
@Import(SecurityConfig::class, PrintScriptRunner::class)
@ActiveProfiles("test")
class PrintScriptControllerTest(
    @param:Autowired private val mockMvc: MockMvc,
) {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    private fun post(
        path: String,
        body: String,
    ): ResultActionsDsl =
        mockMvc.post(path) {
            with(jwt())
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    @Test
    fun `needs a token`() {
        mockMvc.get("/versions").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `lists the versions`() {
        mockMvc.get("/versions") { with(jwt()) }.andExpect {
            status { isOk() }
            jsonPath("$.language") { value("printscript") }
            jsonPath("$.versions") { value(contains("1.0", "1.1")) }
            jsonPath("$.latest") { value("1.1") }
        }
    }

    @Test
    fun `lists the rules with their defaults`() {
        mockMvc.get("/rules/format") { with(jwt()) }.andExpect {
            status { isOk() }
            jsonPath("$[?(@.name == 'indent-inside-if')].isActive") { value(hasItem(true)) }
            jsonPath("$[?(@.name == 'indent-inside-if')].value") { value(hasItem(4)) }
            jsonPath("$[0].description") { isString() }
        }
        mockMvc.get("/rules/lint") { with(jwt()) }.andExpect {
            status { isOk() }
            jsonPath("$[?(@.name == 'identifier_format')].value") { value(hasItem("camel case")) }
        }
    }

    @Test
    fun `rejects an unknown rule type`() {
        mockMvc.get("/rules/style") { with(jwt()) }.andExpect {
            status { isBadRequest() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
        }
    }

    @Test
    fun `validate answers 200 also for invalid code`() {
        post("/validate", """{"content": "let x: number = 1;", "version": "1.0"}""").andExpect {
            status { isOk() }
            jsonPath("$.valid") { value(true) }
            jsonPath("$.diagnostics") { isEmpty() }
        }
        post("/validate", """{"content": "let x: number = ;", "version": "1.0"}""").andExpect {
            status { isOk() }
            jsonPath("$.valid") { value(false) }
            jsonPath("$.diagnostics[0].rule") { value(nullValue()) }
            jsonPath("$.diagnostics[0].line") { value(1) }
            jsonPath("$.diagnostics[0].column") { isNumber() }
            jsonPath("$.diagnostics[0].message") { isString() }
        }
    }

    @Test
    fun `rejects an unknown version`() {
        post("/validate", """{"content": "println(1);", "version": "9.9"}""").andExpect {
            status { isBadRequest() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.detail") { value("Unsupported PrintScript version '9.9' (supported: 1.0, 1.1)") }
        }
    }

    @Test
    fun `rejects a malformed request`() {
        post("/validate", """{"content": "println(1);"}""").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `formats`() {
        val rules = """[{"name": "enforce-spacing-around-equals", "isActive": true}]"""
        post("/format", """{"content": "let x:number=5;", "version": "1.0", "rules": $rules}""").andExpect {
            status { isOk() }
            jsonPath("$.content") { value("let x:number = 5;") }
        }
    }

    @Test
    fun `format of code that does not parse is 422 with diagnostics`() {
        post("/format", """{"content": "let x: number = ;", "version": "1.0", "rules": []}""").andExpect {
            status { isUnprocessableEntity() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.status") { value(422) }
            jsonPath("$.diagnostics[0].line") { value(1) }
            jsonPath("$.diagnostics[0].rule") { value(nullValue()) }
        }
    }

    @Test
    fun `rejects an unknown rule`() {
        post("/format", """{"content": "println(1);", "version": "1.0", "rules": [{"name": "nope", "isActive": true}]}""")
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `lints`() {
        val rules = """[{"name": "identifier_format", "isActive": true, "value": "snake case"}]"""
        post("/lint", """{"content": "let myVar: number = 1;", "version": "1.0", "rules": $rules}""").andExpect {
            status { isOk() }
            jsonPath("$.violations[0].rule") { value("identifier_format") }
            jsonPath("$.violations[0].line") { value(1) }
            jsonPath("$.violations[0].column") { value(5) }
        }
    }

    @Test
    fun `lint of code that does not parse is 422 with diagnostics`() {
        post("/lint", """{"content": "println(;", "version": "1.0", "rules": []}""").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.diagnostics") { isNotEmpty() }
        }
    }

    @Test
    fun `runs a test`() {
        val code = "let a: string = readInput(\\\"a\\\");\\nprintln(a);"
        post("/test", """{"content": "$code", "version": "1.1", "inputs": ["hi"], "expectedOutputs": ["hi"]}""").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("success") }
            jsonPath("$.outputs") { value(contains("hi")) }
        }
        post("/test", """{"content": "$code", "version": "1.1", "inputs": [], "expectedOutputs": ["hi"]}""").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("fail") }
            jsonPath("$.outputs") { isEmpty() }
            jsonPath("$.errors[0].line") { value(1) }
        }
    }
}
