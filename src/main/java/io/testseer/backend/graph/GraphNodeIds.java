package io.testseer.backend.graph;

public final class GraphNodeIds {

    private GraphNodeIds() {}

    public static String serviceNode(String serviceId) {
        return serviceId;
    }

    public static String classNode(String serviceId, String classFqn) {
        return serviceId + "::class::" + classFqn;
    }

    public static String endpointNode(String serviceId, String endpointFqn) {
        return serviceId + "::endpoint::" + endpointFqn;
    }

    public static String sharedTypeNode(String serviceId, String typeFqn) {
        return serviceId + "::type::" + typeFqn;
    }
}
