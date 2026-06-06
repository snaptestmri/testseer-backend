package io.testseer.backend.admin;

import java.util.List;

public record DiscoveryResult(
        List<String> registered,
        List<String> alreadyKnown,
        List<String> skipped
) {
    public int total() {
        return registered.size() + alreadyKnown.size() + skipped.size();
    }
}
