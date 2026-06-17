package io.testseer.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ContractRulePackLoader {

    private static final Logger log = LoggerFactory.getLogger(ContractRulePackLoader.class);

    private final ContractRulePack rulePack;

    public ContractRulePackLoader(
            @Value("${testseer.contracts.rule-pack-path:file:../config/rule-packs/quotient-api-contracts.yml}")
            Resource rulePackPath) {
        this.rulePack = load(rulePackPath);
    }

    public ContractRulePack getRulePack() {
        return rulePack;
    }

    @SuppressWarnings("unchecked")
    private ContractRulePack load(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            Object parsed = new Yaml().load(in);
            if (!(parsed instanceof Map<?, ?> raw)) {
                return ContractRulePack.empty();
            }
            Object domainsRaw = raw.get("domainServices");
            if (!(domainsRaw instanceof Map<?, ?> domains)) {
                return ContractRulePack.empty();
            }
            Map<String, ContractRulePack.DomainServiceMapping> mappings = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : domains.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> m)) continue;
                String primary = string(m.get("primaryService"));
                List<String> also = listOfStrings(m.get("alsoImplementedBy"));
                mappings.put(String.valueOf(e.getKey()), new ContractRulePack.DomainServiceMapping(primary, also));
            }
            return new ContractRulePack(mappings);
        } catch (Exception ex) {
            log.warn("Failed to load contract rule pack from {}: {}", resource, ex.getMessage());
            return ContractRulePack.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> listOfStrings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
