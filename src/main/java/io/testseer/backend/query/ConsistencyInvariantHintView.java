package io.testseer.backend.query;

/** Invariant attached to a consistency hint for test planning. */
public record ConsistencyInvariantHintView(
        String kind,
        String description,
        String pollHint
) {}
