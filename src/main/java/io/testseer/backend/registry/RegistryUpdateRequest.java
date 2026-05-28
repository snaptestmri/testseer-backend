package io.testseer.backend.registry;

import java.util.List;

public record RegistryUpdateRequest(
        Boolean enabled,
        List<String> sourceRoots,
        List<String> testRoots,
        String ownerTeam
) {}
