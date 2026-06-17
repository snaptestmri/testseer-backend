package io.testseer.backend.config;

import java.util.List;

public record RoutingRulePack(
        List<BeanLinkRule> beanLinks,
        List<FactoryRoutingRule> factoryRouting
) {
    public static RoutingRulePack empty() {
        return new RoutingRulePack(List.of(), List.of());
    }

    public record BeanLinkRule(String beanName, String classFqn) {}

    public record FactoryRoutingRule(
            String factoryFqn,
            String selectorMethod,
            String discriminatorType
    ) {}
}
