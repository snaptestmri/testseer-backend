package io.testseer.backend.config;

import java.util.List;
import java.util.Map;

public record ContractRulePack(Map<String, DomainServiceMapping> domainServices) {

    public record DomainServiceMapping(String primaryService, List<String> alsoImplementedBy) {}

    public static ContractRulePack empty() {
        return new ContractRulePack(Map.of());
    }

    public String primaryServiceForDomain(String specDomain) {
        if (specDomain == null || domainServices == null) return null;
        DomainServiceMapping mapping = domainServices.get(specDomain);
        return mapping != null ? mapping.primaryService() : null;
    }
}
