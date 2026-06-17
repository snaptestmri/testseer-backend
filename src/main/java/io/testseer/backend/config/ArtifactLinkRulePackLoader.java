package io.testseer.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class ArtifactLinkRulePackLoader {

    private static final Logger log = LoggerFactory.getLogger(ArtifactLinkRulePackLoader.class);

    private final Resource rulePackPath;
    private final AtomicReference<ArtifactLinkRulePack> rulePack;

    public ArtifactLinkRulePackLoader(
            @Value("${testseer.artifact.rule-pack-path:file:../config/rule-packs/quotient-artifacts.yml}")
            Resource rulePackPath) {
        this.rulePackPath = rulePackPath;
        this.rulePack = new AtomicReference<>(load(rulePackPath));
    }

    public ArtifactLinkRulePack getRulePack() {
        return rulePack.get();
    }

    public void reload() {
        rulePack.set(load(rulePackPath));
    }

    @SuppressWarnings("unchecked")
    private ArtifactLinkRulePack load(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            Map<String, Object> raw = new Yaml().load(in);
            if (raw == null) {
                return ArtifactLinkRulePack.empty();
            }
            List<ArtifactLinkRulePack.ArtifactAliasRule> links = list(raw.get("artifactLinks")).stream()
                    .filter(Map.class::isInstance)
                    .map(m -> (Map<String, Object>) m)
                    .map(m -> new ArtifactLinkRulePack.ArtifactAliasRule(
                            string(m.get("groupId")),
                            string(m.get("artifactId")),
                            string(m.get("catalogLibrary"))))
                    .toList();
            log.info("Loaded {} artifact link alias rules from {}", links.size(), resource);
            return new ArtifactLinkRulePack(links);
        } catch (Exception e) {
            log.info("Artifact link rule pack not found at {}; using empty aliases ({})",
                    resource, e.getMessage());
            return ArtifactLinkRulePack.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object raw) {
        if (raw instanceof List<?> list) {
            return (List<Object>) list;
        }
        return List.of();
    }

    private static String string(Object value) {
        return value != null ? String.valueOf(value) : null;
    }
}
