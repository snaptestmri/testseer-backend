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
import java.util.concurrent.atomic.AtomicReference;

@Component
public class DomainActorRulePackLoader {

    private static final Logger log = LoggerFactory.getLogger(DomainActorRulePackLoader.class);

    private final Resource rulePackPath;
    private final AtomicReference<DomainActorRulePack> rulePack;

    public DomainActorRulePackLoader(
            @Value("${testseer.domain-actors.rule-pack-path:file:../config/rule-packs/quotient-domain-actors.yml}")
            Resource rulePackPath) {
        this.rulePackPath = rulePackPath;
        this.rulePack = new AtomicReference<>(load(rulePackPath));
    }

    public DomainActorRulePack getRulePack() {
        return rulePack.get();
    }

    public void reload() {
        rulePack.set(load(rulePackPath));
    }

    @SuppressWarnings("unchecked")
    private DomainActorRulePack load(Resource resource) {
        try (InputStream in = openStream(resource)) {
            if (in == null) {
                log.info("Domain actor rule pack not found at {}; using empty pack", resource);
                return DomainActorRulePack.empty();
            }
            Map<String, Object> raw = new Yaml().load(in);
            if (raw == null) {
                return DomainActorRulePack.empty();
            }

            List<DomainActorRulePack.DomainActor> actors = list(raw.get("domainActors")).stream()
                    .filter(Map.class::isInstance)
                    .map(m -> (Map<String, Object>) m)
                    .map(m -> new DomainActorRulePack.DomainActor(
                            string(m.get("classFqn")),
                            string(m.get("role")),
                            string(m.get("manualNodeId"))))
                    .filter(a -> a.classFqn() != null)
                    .toList();

            List<DomainActorRulePack.ConsumerRole> consumerRoles = list(raw.get("consumerRoles")).stream()
                    .filter(Map.class::isInstance)
                    .map(m -> (Map<String, Object>) m)
                    .map(m -> new DomainActorRulePack.ConsumerRole(
                            string(m.get("packagePrefix")),
                            string(m.get("defaultRole"))))
                    .filter(r -> r.packagePrefix() != null)
                    .toList();

            List<String> egressTopics = list(raw.get("expectedEgressTopics")).stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();

            return new DomainActorRulePack(actors, consumerRoles, egressTopics);
        } catch (Exception ex) {
            log.warn("Failed to load domain actor rule pack from {}: {}", resource, ex.getMessage());
            return DomainActorRulePack.empty();
        }
    }

    private static InputStream openStream(Resource resource) throws java.io.IOException {
        if (resource == null || !resource.exists()) {
            return null;
        }
        return resource.getInputStream();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object raw) {
        if (raw instanceof List<?> l) {
            return (List<Object>) l;
        }
        return List.of();
    }

    private static String string(Object raw) {
        return raw == null ? null : String.valueOf(raw).trim();
    }
}
