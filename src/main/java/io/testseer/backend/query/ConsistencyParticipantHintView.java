package io.testseer.backend.query;

/** Store participant in a consistency hint (trace/API surface). */
public record ConsistencyParticipantHintView(
        String storeType,
        String physicalName,
        String role,
        String via,
        String lagClass
) {}
