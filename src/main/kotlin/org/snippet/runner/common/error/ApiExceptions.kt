package org.snippet.runner.common.error

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Errors the API returns on purpose. Rendered as Problem Details (RFC 9457);
 * [properties] become extra members of the body (e.g. `diagnostics`).
 */
open class ApiException(
    val status: HttpStatus,
    message: String,
    val properties: Map<String, Any> = emptyMap(),
) : RuntimeException(message)

class BadRequestException(
    message: String,
) : ApiException(HttpStatus.BAD_REQUEST, message)

class NotFoundException(
    message: String,
) : ApiException(HttpStatus.NOT_FOUND, message)

class ForbiddenException(
    message: String = "You don't have permission on this resource",
) : ApiException(HttpStatus.FORBIDDEN, message)

class ConflictException(
    message: String,
) : ApiException(HttpStatus.CONFLICT, message)

/** The request is well formed but its content can't be processed, e.g. code that doesn't parse. */
open class UnprocessableContentException(
    message: String,
    properties: Map<String, Any> = emptyMap(),
) : ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message, properties)

/** Extends Spring's handler so validation and binding errors also come out as Problem Details (400). */
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    @ExceptionHandler(ApiException::class)
    fun handleApiException(exception: ApiException): ProblemDetail =
        ProblemDetail
            .forStatusAndDetail(exception.status, exception.message ?: exception.status.reasonPhrase)
            .apply { exception.properties.forEach(::setProperty) }
}
