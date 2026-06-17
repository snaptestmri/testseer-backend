package io.testseer.backend.query;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bulk-loaded facts for all services participating in a cross-repo trace. */
public record CrossRepoTraceContext(
        String orgId,
        String env,
        List<MessagingFlowService.CrossRepoHop> hops,
        Set<String> serviceIds,
        Map<String, List<MessagingFlowService.DataAccessView>> dataAccessByService,
        Map<String, List<MessagingFlowService.FlowGateView>> gatesByService,
        String bundleName
) {}
