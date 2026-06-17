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
public class RoutingRulePackLoader {

    private static final Logger log = LoggerFactory.getLogger(RoutingRulePackLoader.class);

    private final Resource rulePackPath;
    private final AtomicReference<RoutingRulePack> rulePack;

    public RoutingRulePackLoader(
            @Value("${testseer.routing.rule-pack-path:file:../config/rule-packs/quotient-routing.yml}")
            Resource rulePackPath) {
        this.rulePackPath = rulePackPath;
        this.rulePack = new AtomicReference<>(load(rulePackPath));
    }

    public RoutingRulePack getRulePack() {
        return rulePack.get();
    }

    public void reload() {
        rulePack.set(load(rulePackPath));
    }

    @SuppressWarnings("unchecked")
    private RoutingRulePack load(Resource resource) {
        try (InputStream in = openStream(resource)) {
            if (in == null) {
                log.info("Routing rule pack not found at {}; using AST-only routing", resource);
                return RoutingRulePack.empty();
            }
            Map<String, Object> raw = new Yaml().load(in);
            if (raw == null) {
                return RoutingRulePack.empty();
            }

            List<RoutingRulePack.BeanLinkRule> beanLinks = list(raw.get("beanLinks")).stream()
                    .filter(Map.class::isInstance)
                    .map(m -> (Map<String, Object>) m)
                    .map(m -> new RoutingRulePack.BeanLinkRule(
                            string(m.get("beanName")), string(m.get("classFqn"))))
                    .filter(r -> r.beanName() != null && r.classFqn() != null)
                    .toList();

            List<RoutingRulePack.FactoryRoutingRule> factoryRules = list(raw.get("factoryRouting")).stream()
                    .filter(Map.class::isInstance)
                    .map(m -> (Map<String, Object>) m)
                    .map(m -> new RoutingRulePack.FactoryRoutingRule(
                            string(m.get("factoryFqn")),
                            string(m.get("selectorMethod")),
                            string(m.get("discriminatorType"))))
                    .filter(r -> r.factoryFqn() != null)
                    .toList();

            return new RoutingRulePack(beanLinks, factoryRules);
        } catch (Exception ex) {
            log.warn("Failed to load routing rule pack from {}: {}", resource, ex.getMessage());
            return RoutingRulePack.empty();
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
