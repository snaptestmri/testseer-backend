package io.testseer.backend.graph;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class GraphNodeIds {

    /** Postgres graph_nodes.id limit after V20 migration. */
    static final int MAX_NODE_ID_LEN = 1024;

    private GraphNodeIds() {}

    public static String serviceNode(String serviceId) {
        return serviceId;
    }

    public static String classNode(String serviceId, String classFqn) {
        return bounded(serviceId + "::class::" + classFqn);
    }

    public static String endpointNode(String serviceId, String endpointFqn) {
        return bounded(serviceId + "::endpoint::" + endpointFqn);
    }

    public static String methodNode(String serviceId, String classFqn, String methodName) {
        return bounded(serviceId + "::method::" + classFqn + "#" + methodName);
    }

    public static String sharedTypeNode(String serviceId, String typeFqn) {
        return bounded(serviceId + "::type::" + typeFqn);
    }

    public static String externalEndpointNode(String orgId, String envLane, String endpointId) {
        return bounded(orgId + "::external::" + envLane + "::" + endpointId);
    }

    public static String entryTriggerNode(String orgId, String serviceId, String envLane, String triggerId) {
        return bounded(orgId + "::entry::" + serviceId + "::" + envLane + "::" + triggerId);
    }

    public static String mavenModuleNode(String serviceId, String modulePath) {
        String path = modulePath == null ? "" : modulePath;
        return bounded(serviceId + "::maven::" + path);
    }

    public static String artifactNode(String groupId, String artifactId, String version) {
        String ver = version != null ? version : "UNRESOLVED";
        return bounded("gav::" + groupId + ":" + artifactId + ":" + ver);
    }

    /** Stable short suffix for embedding long symbols in composite node ids (e.g. gate nodes). */
    public static String compactSuffix(String value, int maxLen) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.length() <= maxLen) {
            return value;
        }
        return "h:" + digestHex(value, 8);
    }

    static String bounded(String raw) {
        if (raw == null) {
            return "";
        }
        if (raw.length() <= MAX_NODE_ID_LEN) {
            return raw;
        }
        String suffix = "::h:" + digestHex(raw, 8);
        int prefixLen = MAX_NODE_ID_LEN - suffix.length();
        return raw.substring(0, prefixLen) + suffix;
    }

    private static String digestHex(String raw, int bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
