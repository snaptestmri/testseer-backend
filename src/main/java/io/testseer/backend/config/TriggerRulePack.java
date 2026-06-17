package io.testseer.backend.config;

import java.util.List;

public record TriggerRulePack(
        List<InboundRestTriggerRule> inboundRestTriggers,
        List<AirflowTriggerRule> airflowRules,
        List<CronHandlerLinkRule> cronHandlerLinks) {

    public static TriggerRulePack empty() {
        return new TriggerRulePack(List.of(), List.of(), List.of());
    }

    public record InboundRestTriggerRule(
            String match,
            String pathPrefix,
            String triggerKind,
            String actor,
            String boundary,
            String flowStep,
            String envLane
    ) {}

    public record AirflowTriggerRule(
            String match,
            String dagId,
            String taskId,
            String flowStep,
            String linkedServiceModule,
            String actor,
            String boundary,
            String envLane
    ) {}

    public record CronHandlerLinkRule(String cronJobName, String classFqn, String method) {}
}
