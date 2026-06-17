package io.testseer.backend.query;

import io.testseer.backend.graph.GraphNode;

/**
 * KFK-08 / SFD-13: filter symbols and triggers by Java package prefix.
 */
public final class PackagePrefixFilter {

    private PackagePrefixFilter() {}

    public static boolean matches(String symbolFqn, String packagePrefix) {
        if (packagePrefix == null || packagePrefix.isBlank()) {
            return true;
        }
        if (symbolFqn == null || symbolFqn.isBlank()) {
            return false;
        }
        String classFqn = symbolFqn.contains("#")
                ? symbolFqn.substring(0, symbolFqn.indexOf('#'))
                : symbolFqn;
        return classFqn.startsWith(packagePrefix);
    }

    public static boolean matchesNode(GraphNode node, String packagePrefix) {
        if (packagePrefix == null || packagePrefix.isBlank()) {
            return true;
        }
        if (node == null) {
            return false;
        }
        String type = node.nodeType();
        if ("TOPIC".equals(type) || "SUBSCRIPTION".equals(type) || "GATE".equals(type)) {
            return true;
        }
        return matches(node.symbolFqn(), packagePrefix);
    }

    public static boolean matchesTrigger(EntryFlowService.EntryTriggerView trigger, String packagePrefix) {
        if (packagePrefix == null || packagePrefix.isBlank()) {
            return true;
        }
        if (trigger.linkedHandlerFqn() != null && matches(trigger.linkedHandlerFqn(), packagePrefix)) {
            return true;
        }
        return "KAFKA_SUBSCRIBE".equals(trigger.triggerKind())
                || "PUBSUB_SUBSCRIBE".equals(trigger.triggerKind());
    }
}
