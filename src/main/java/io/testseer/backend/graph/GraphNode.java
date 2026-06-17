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

    public static GraphNode method(String id, String orgId, String repo,
                                    String service, String methodFqn) {
        return new GraphNode(id, orgId, repo, service, "service", "METHOD", methodFqn);
    }

    public static GraphNode sharedType(String id, String orgId, String repo,
                                        String service, String fqn) {
        return new GraphNode(id, orgId, repo, service, "library", "SHARED_TYPE", fqn);
    }

    public static GraphNode mavenModule(String id, String orgId, String repo, String service, String label) {
        return new GraphNode(id, orgId, repo, service, "service", "MAVEN_MODULE", label);
    }

    public static GraphNode artifact(String id, String orgId, String repo, String service, String gavLabel) {
        return new GraphNode(id, orgId, repo, service, "library", "ARTIFACT", gavLabel);
    }
}
