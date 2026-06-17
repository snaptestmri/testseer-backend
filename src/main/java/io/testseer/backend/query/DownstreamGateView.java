package io.testseer.backend.query;

/** Gate in a later cross-repo hop that reads/asserts on tables written upstream. */
public record DownstreamGateView(
        String serviceId,
        String repo,
        int hopOrder,
        String gateKey,
        String requiredValue,
        String effectWhenFail,
        String testPrecondition
) {}
