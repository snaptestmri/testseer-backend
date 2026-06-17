package io.testseer.backend.query;

import java.util.List;

/** Recommended poll order for a consistency scenario. */
public record ConsistencyPollStrategyView(
        List<String> order,
        String primaryPollHint,
        List<String> notes
) {}
