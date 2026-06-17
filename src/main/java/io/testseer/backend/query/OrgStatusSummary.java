package io.testseer.backend.query;

import java.util.List;

public record OrgStatusSummary(
        String orgId,
        int serviceCount,
        List<OrgServiceStatus> services
) {}
