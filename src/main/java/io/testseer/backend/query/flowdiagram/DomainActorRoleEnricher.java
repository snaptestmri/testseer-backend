package io.testseer.backend.query.flowdiagram;

import io.testseer.backend.config.DomainActorRulePack;
import io.testseer.backend.config.DomainActorRulePackLoader;
import io.testseer.backend.graph.GraphNode;
import org.springframework.stereotype.Component;

@Component
public class DomainActorRoleEnricher {

    private final DomainActorRulePackLoader loader;

    public DomainActorRoleEnricher(DomainActorRulePackLoader loader) {
        this.loader = loader;
    }

    public String resolveRole(GraphNode node, String packagePrefix) {
        if (node == null || node.symbolFqn() == null) {
            return null;
        }
        String classFqn = classFqn(node.symbolFqn());
        DomainActorRulePack pack = loader.getRulePack();

        for (DomainActorRulePack.DomainActor actor : pack.domainActors()) {
            if (classFqn.equals(actor.classFqn())) {
                return actor.role();
            }
        }
        for (DomainActorRulePack.ConsumerRole role : pack.consumerRoles()) {
            if (classFqn.startsWith(role.packagePrefix())) {
                return role.defaultRole();
            }
        }
        return null;
    }

    public String resolveModuleScope(GraphNode node, String packagePrefix) {
        if (node == null) {
            return null;
        }
        String type = node.nodeType();
        if ("TOPIC".equals(type) || "SUBSCRIPTION".equals(type)) {
            return "messaging";
        }
        if ("GATE".equals(type)) {
            return "gate";
        }
        if (packagePrefix != null && !packagePrefix.isBlank()
                && node.symbolFqn() != null
                && classFqn(node.symbolFqn()).startsWith(packagePrefix)) {
            return "consumer";
        }
        if (node.symbolFqn() != null && classFqn(node.symbolFqn()).contains(".transaction.eval.")) {
            return "consumer";
        }
        if (node.symbolFqn() != null) {
            return "external-domain";
        }
        return null;
    }

    private static String classFqn(String symbolFqn) {
        int hash = symbolFqn.indexOf('#');
        return hash > 0 ? symbolFqn.substring(0, hash) : symbolFqn;
    }
}
