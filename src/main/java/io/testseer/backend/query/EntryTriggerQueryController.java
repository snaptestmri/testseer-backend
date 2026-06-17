package io.testseer.backend.query;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Query — Entry Triggers", description = "Inbound entry triggers and entry-flow trace (TRG P1)")
@RestController
public class EntryTriggerQueryController {

    private final EntryFlowService entryFlowService;
    private final FreshnessResolver freshnessResolver;
    private final int staleThresholdMinutes;

    public EntryTriggerQueryController(
            EntryFlowService entryFlowService,
            FreshnessResolver freshnessResolver,
            @Value("${testseer.stale-threshold-minutes:60}") int staleThresholdMinutes) {
        this.entryFlowService = entryFlowService;
        this.freshnessResolver = freshnessResolver;
        this.staleThresholdMinutes = staleThresholdMinutes;
    }

    @Operation(summary = "Inbound entry trigger inventory",
               description = "REST/webhook ingress points that can start processing in a service.")
    @GetMapping("/v1/facts/entry-triggers")
    public ResponseEntity<ResponseEnvelope<List<EntryFlowService.EntryTriggerView>>> getEntryTriggers(
            @RequestParam String serviceId,
            @RequestParam(required = false) String env,
            @RequestParam(required = false) String triggerKind,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String boundary,
            @RequestParam(required = false) String orgId,
            @RequestParam(required = false) String serviceModuleId,
            @RequestParam(required = false) String sourceRootPrefix,
            @RequestParam(required = false) String packagePrefix,
            @RequestParam(required = false, defaultValue = "false") boolean includeWiring) {

        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        List<EntryFlowService.EntryTriggerView> data =
                entryFlowService.queryTriggers(
                        serviceId, env, triggerKind, actor, boundary,
                        orgId, serviceModuleId, sourceRootPrefix, packagePrefix, includeWiring);
        return FreshnessHttp.respond(status, data);
    }

    @Operation(summary = "Trace flow from an inbound entry trigger",
               description = "Forward walk from triggerId or HTTP path through handler data access, flow gates, "
                       + "optional Option C messaging hops, cross-repo trace, and outbound external endpoints (TRG-12).")
    @GetMapping("/v1/graph/entry-flow")
    public ResponseEntity<ResponseEnvelope<EntryFlowService.EntryFlowReport>> traceEntryFlow(
            @RequestParam String serviceId,
            @RequestParam(required = false) String triggerId,
            @RequestParam(required = false) String path,
            @RequestParam(required = false, defaultValue = "unknown") String env,
            @RequestParam(required = false, defaultValue = "false") boolean includeMessaging,
            @RequestParam(required = false, defaultValue = "false") boolean includeExternal,
            @RequestParam(required = false, defaultValue = "false") boolean crossRepo,
            @RequestParam(required = false) String orgId,
            @RequestParam(required = false, defaultValue = "12") int maxHops,
            @RequestParam(required = false) String serviceModuleId,
            @RequestParam(required = false) String sourceRootPrefix,
            @RequestParam(required = false, defaultValue = "false") boolean includeWiring) {

        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        EntryFlowService.EntryFlowReport report = entryFlowService.traceEntryFlow(
                serviceId, triggerId, path, env,
                includeMessaging, includeExternal, crossRepo, orgId, maxHops,
                serviceModuleId, sourceRootPrefix, orgId, includeWiring);
        return FreshnessHttp.respond(status, report);
    }

    @Operation(summary = "Reverse impact: inbound triggers for a changed handler",
               description = "Lists entry triggers that fan in to handlerFqn (reverse TRIGGERED_BY). "
                       + "Org-wide by default; optional serviceId narrows scope.")
    @GetMapping("/v1/graph/entry-flow/impact")
    public ResponseEntity<ResponseEnvelope<EntryFlowService.EntryTriggerImpactReport>> entryFlowImpact(
            @RequestParam String orgId,
            @RequestParam String handlerFqn,
            @RequestParam(required = false) String serviceId,
            @RequestParam(required = false) String env) {

        FreshnessStatus status;
        if (serviceId != null && !serviceId.isBlank()) {
            status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        } else if (!entryFlowService.orgHasEntryTriggers(orgId)) {
            status = FreshnessStatus.NOT_INDEXED;
        } else {
            status = FreshnessStatus.CURRENT;
        }

        EntryFlowService.EntryTriggerImpactReport report =
                entryFlowService.impactByHandler(orgId, handlerFqn, serviceId, env);
        return FreshnessHttp.respond(status, report);
    }
}
