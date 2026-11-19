package org.snippet.runner.common.security

import org.snippet.runner.common.logging.UserMdcFilter
import org.snippet.runner.common.observability.MetricsRequests
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain

/**
 * Every endpoint requires a valid Auth0 JWT except the health checks and the metrics (on the
 * internal port only).
 * Signature, `iss`, `aud` and `exp` are validated by Spring from the
 * `spring.security.oauth2.resourceserver.jwt.*` properties.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    @param:Value("\${observability.metrics-port:0}") private val metricsPort: Int,
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                authorize("/health", permitAll)
                authorize("/health/**", permitAll)
                // /prometheus only on the internal metrics port, without a token (MetricsPortConfig)
                MetricsRequests(metricsPort).let {
                    authorize(it.onMetricsPort, permitAll)
                    authorize(it.elsewhere, denyAll)
                }
                authorize(anyRequest, authenticated)
            }
            oauth2ResourceServer { jwt { } }
            addFilterAfter<BearerTokenAuthenticationFilter>(UserMdcFilter())
        }
        return http.build()
    }
}
