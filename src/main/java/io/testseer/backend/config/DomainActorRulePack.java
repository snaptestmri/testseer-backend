package io.testseer.backend.config;

import java.util.List;

public record DomainActorRulePack(
        List<DomainActor> domainActors,
        List<ConsumerRole> consumerRoles,
        List<String> expectedEgressTopics
) {
    public static DomainActorRulePack empty() {
        return new DomainActorRulePack(List.of(), List.of(), List.of());
    }

    public record DomainActor(String classFqn, String role, String manualNodeId) {}

    public record ConsumerRole(String packagePrefix, String defaultRole) {}
}
