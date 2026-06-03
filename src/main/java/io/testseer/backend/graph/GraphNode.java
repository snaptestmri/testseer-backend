package io.testseer.backend.graph;

public record GraphNode(
        String id,
        String orgId,
        String repo,
        String service,
        String moduleType,    // "service" | "library"
        String nodeType,      // "SERVICE" | "CLASS" | "ENDPOINT" | "SHARED_TYPE"
        String symbolFqn
) {
    public static GraphNode service(String id, String orgId, String repo, String service) {
        return new GraphNode(id, orgId, repo, service, "service", "SERVICE", null);
    }

    public static GraphNode clazz(String id, String orgId, String repo,
                                   String service, String fqn) {
        return new GraphNode(id, orgId, repo, service, "service", "CLASS", fqn);
    }

    public static GraphNode endpoint(String id, String orgId, String repo,
                                      String service, String fqn) {
        return new GraphNode(id, orgId, repo, service, "service", "ENDPOINT", fqn);
    }

    public static GraphNode sharedType(String id, String orgId, String repo,
                                        String service, String fqn) {
        return new GraphNode(id, orgId, repo, service, "library", "SHARED_TYPE", fqn);
    }
}
