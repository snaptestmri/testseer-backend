package io.testseer.backend.query;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Query — Messaging Flow", description = "Pub/Sub inventory, event flow, gates, and validation hints (Option C)")
@RestController
@RequestMapping("/v1")
public class MessagingQueryController {

    private final MessagingFlowService flowService;
    private final FreshnessResolver freshnessResolver;
    private final int staleThresholdMinutes;

    public MessagingQueryController(
            MessagingFlowService flowService,
            FreshnessResolver freshnessResolver,
            @Value("${testseer.stale-threshold-minutes:60}") int staleThresholdMinutes) {
        this.flowService = flowService;
        this.freshnessResolver = freshnessResolver;
        this.staleThresholdMinutes = staleThresholdMinutes;
    }

    @Operation(summary = "Org-wide Pub/Sub inventory for cross-repo flows")
    @GetMapping("/facts/pubsub/org")
    public ResponseEntity<ResponseEnvelope<List<MessagingFlowService.PubSubOrgView>>> getOrgPubSubInventory(
            @RequestParam String orgId,
            @RequestParam(required = false) String env,
            @RequestParam(required = false) String resourceKind,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "false") boolean liveVerify) {

        MessagingFlowService.PubSubLiveInventoryResult<MessagingFlowService.PubSubOrgView> result =
                flowService.queryPubSubForOrgWithLive(orgId, env, liveVerify);
        List<MessagingFlowService.PubSubOrgView> data = result.items();
        if (resourceKind != null && !resourceKind.isBlank()) {
            data = data.stream().filter(p -> resourceKind.equalsIgnoreCase(p.resourceKind())).toList();
        }
        if (role != null && !role.isBlank()) {
            data = data.stream().filter(p -> role.equalsIgnoreCase(p.role())).toList();
        }
        if (data.isEmpty()) {
            return ResponseEntity.status(404).body(ResponseEnvelope.notIndexed());
        }
        MessagingFlowService.LivePubSubSummary summary = result.summary();
        return FreshnessHttp.respondWithLivePubSub(
                FreshnessStatus.CURRENT, null, null, data,
                summary.status(), summary.verifiedCount(), summary.skippedCount());
    }

    @Operation(summary = "Pub/Sub resource inventory (C-P1)")
    @GetMapping("/facts/pubsub")
    public ResponseEntity<ResponseEnvelope<List<MessagingFlowService.PubSubView>>> getPubSubInventory(
            @RequestParam String serviceId,
            @RequestParam(required = false) String env,
            @RequestParam(required = false) String shortId,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "false") boolean liveVerify) {

        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        if (status == FreshnessStatus.NOT_INDEXED) {
            return FreshnessHttp.respond(status, null);
        }
        MessagingFlowService.PubSubLiveInventoryResult<MessagingFlowService.PubSubView> result =
                flowService.queryPubSubWithLive(serviceId, shortId, env, liveVerify);
        List<MessagingFlowService.PubSubView> data = result.items();
        if (role != null && !role.isBlank()) {
            data = data.stream().filter(p -> role.equalsIgnoreCase(p.role())).toList();
        }
        MessagingFlowService.LivePubSubSummary summary = result.summary();
        return FreshnessHttp.respondWithLivePubSub(
                status, null, null, data,
                summary.status(), summary.verifiedCount(), summary.skippedCount());
    }

    @Operation(summary = "Message schema facts (C-P2)")
    @GetMapping("/facts/message-schema")
    public ResponseEntity<ResponseEnvelope<List<MessagingFlowService.MessageSchemaView>>> getMessageSchemas(
            @RequestParam String serviceId,
            @RequestParam(required = false) String topicShortId) {

        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        return FreshnessHttp.respond(status, flowService.querySchemas(serviceId, topicShortId));
    }

    @Operation(summary = "Data access facts along event handlers (C-P3)")
    @GetMapping("/facts/data-access")
    public ResponseEntity<ResponseEnvelope<List<MessagingFlowService.DataAccessView>>> getDataAccess(
            @RequestParam String serviceId,
            @RequestParam(required = false) String packagePrefix,
            @RequestParam(defaultValue = "true") boolean excludeTestHandlers) {

        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        return FreshnessHttp.respond(status,
                flowService.queryDataAccess(serviceId, packagePrefix, excludeTestHandlers));
    }

    @Operation(summary = "Flow gate and config facts (C-P6)")
    @GetMapping("/facts/gates")
    public ResponseEntity<ResponseEnvelope<List<MessagingFlowService.FlowGateView>>> getGates(
            @RequestParam String serviceId,
            @RequestParam(required = false) String env,
            @RequestParam(required = false) String flowStep,
            @RequestParam(required = false) String packagePrefix,
            @RequestParam(defaultValue = "false") boolean refreshLive) {

        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        List<MessagingFlowService.FlowGateView> gates =
                flowService.queryGatesWithLive(serviceId, env, refreshLive, packagePrefix);
        if (flowStep != null && !flowStep.isBlank()) {
            gates = gates.stream().filter(g -> flowStep.equals(g.guardedFlowStep())).toList();
        }
        LiveConfigSnapshotService.OverlayContext overlay = flowService.queryGatesOverlayContext(env);
        return FreshnessHttp.respondWithLiveConfig(
                status, null, null, gates, overlay.liveConfigStatus(), overlay.liveConfigEnv());
    }

    @Operation(summary = "Validation hints for test plan Expected columns (C-P4)")
    @GetMapping("/facts/validation-hints")
    public ResponseEntity<ResponseEnvelope<List<MessagingFlowService.ValidationHintView>>> getValidationHints(
            @RequestParam String serviceId,
            @RequestParam(required = false) String env,
            @RequestParam(required = false) String flowStep) {

        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        List<MessagingFlowService.ValidationHintView> hints = flowService.queryHints(serviceId, env);
        if (flowStep != null && !flowStep.isBlank()) {
            hints = hints.stream().filter(h -> flowStep.equals(h.flowStep())).toList();
        }
        return FreshnessHttp.respond(status, hints);
    }

    @Operation(summary = "Trace topic through publishers, schemas, DB, gates (C-P2/P4/P6)")
    @GetMapping("/graph/event-flow")
    public ResponseEntity<ResponseEnvelope<MessagingFlowService.EventFlowReport>> traceEventFlow(
            @RequestParam String serviceId,
            @RequestParam(required = false) String shortId,
            @RequestParam(defaultValue = "pdn") String env,
            @RequestParam(defaultValue = "false") boolean includeExternal,
            @RequestParam(defaultValue = "false") boolean liveVerify) {

        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        MessagingFlowService.EventFlowReport report =
                flowService.traceTopicFlow(serviceId, shortId, env, includeExternal, liveVerify);
        return FreshnessHttp.respondWithLivePubSub(
                status, null, null, report,
                report.livePubSubStatus(), report.livePubSubVerifiedCount(), report.livePubSubSkippedCount());
    }

    @Operation(summary = "Trace topic across all indexed services in an org (cross-repo)")
    @GetMapping("/graph/event-flow/cross-repo")
    public ResponseEntity<ResponseEnvelope<MessagingFlowService.CrossRepoFlowReport>> traceCrossRepoFlow(
            @RequestParam String orgId,
            @RequestParam String shortId,
            @RequestParam(defaultValue = "pdn") String env,
            @RequestParam(defaultValue = "12") int maxHops,
            @RequestParam(required = false) String bundle,
            @RequestParam(defaultValue = "false") boolean includeExternal,
            @RequestParam(defaultValue = "false") boolean liveVerify,
            @RequestParam(defaultValue = "runtime") String followMode,
            @RequestParam(defaultValue = "false") boolean includeManifest) {

        MessagingFlowService.CrossRepoFlowReport report =
                flowService.traceCrossRepo(
                        orgId, shortId, env, maxHops, bundle, includeExternal, liveVerify,
                        followMode, includeManifest);
        if (report.indexedServiceIds().isEmpty()) {
            return ResponseEntity.status(404).body(ResponseEnvelope.notIndexed());
        }
        return FreshnessHttp.respondWithLivePubSub(
                FreshnessStatus.CURRENT, null, null, report,
                report.livePubSubStatus(), report.livePubSubVerifiedCount(), report.livePubSubSkippedCount());
    }

    @Operation(summary = "Messaging flow gap report (C-P5)")
    @GetMapping("/gaps/messaging")
    public ResponseEntity<ResponseEnvelope<MessagingFlowService.EventFlowReport>> getMessagingGaps(
            @RequestParam String serviceId,
            @RequestParam(defaultValue = "pdn") String env) {

        FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
        return FreshnessHttp.respond(status, flowService.traceTopicFlow(serviceId, null, env));
    }
}
