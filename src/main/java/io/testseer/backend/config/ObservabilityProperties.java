package io.testseer.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "testseer.observability")
public record ObservabilityProperties(
        @DefaultValue("X-Request-Id") String requestIdHeader,
        @DefaultValue("X-Job-Id") String jobIdHeader,
        @DefaultValue("X-TestSeer-Client") String mcpClientHeader,
        @DefaultValue("X-MCP-Tool") String mcpToolHeader,
        @DefaultValue("false") boolean jsonLogging,
        @DefaultValue("3") int maxJobAttempts,
        /** Base delay in ms for exponential retry backoff (attempt 1 = base, attempt 2 = base×2, …). */
        @DefaultValue("30000") long retryBackoffBaseMs
) {}
