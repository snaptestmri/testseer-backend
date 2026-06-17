package io.testseer.backend.analysis;

import java.time.Instant;

public record ServiceDescriptionResponse(
        String serviceId,
        String description,
        Instant generatedAt,
        String model
) {}
