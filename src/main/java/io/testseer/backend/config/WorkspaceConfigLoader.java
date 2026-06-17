package io.testseer.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class WorkspaceConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceConfigLoader.class);

    private final WorkspaceConfig config;

    public WorkspaceConfigLoader(
            @Value("${testseer.workspace-config-path:file:../config/workspace.yml}") Resource workspaceConfig) {
        this.config = load(workspaceConfig);
    }

    public WorkspaceConfig getConfig() {
        return config;
    }

    @SuppressWarnings("unchecked")
    private WorkspaceConfig load(Resource resource) {
        try (InputStream in = openStream(resource)) {
            if (in == null) {
                log.warn("Workspace config not found at {}; bundle checks disabled", resource);
                return empty();
            }
            Map<String, Object> raw = new Yaml().load(in);
            if (raw == null) {
                return empty();
            }

            Map<String, WorkspaceConfig.BundleConfig> bundles = new LinkedHashMap<>();
            Object bundlesRaw = raw.get("bundles");
            if (bundlesRaw instanceof Map<?, ?> bundleMap) {
                for (Map.Entry<?, ?> entry : bundleMap.entrySet()) {
                    if (!(entry.getValue() instanceof Map<?, ?> bundleDef)) continue;
                    List<String> repos = listOfStrings(bundleDef.get("repos"));
                    List<WorkspaceConfig.BundleIndexEntry> indexOrder = parseIndexOrder(bundleDef.get("indexOrder"));
                    Map<String, Object> traceRaw = map(bundleDef.get("trace"));
                    WorkspaceConfig.TraceConfig trace = traceRaw == null ? null : new WorkspaceConfig.TraceConfig(
                            string(traceRaw.get("shortId")),
                            string(traceRaw.get("env"))
                    );
                    List<WorkspaceConfig.PropagationEdgeConfig> propagationEdges =
                            parsePropagationEdges(bundleDef.get("propagationEdges"));
                    bundles.put(String.valueOf(entry.getKey()),
                            new WorkspaceConfig.BundleConfig(repos, indexOrder, trace, propagationEdges));
                }
            }

            return new WorkspaceConfig(
                    string(raw.get("githubDir")),
                    string(raw.get("defaultOrgId")),
                    string(raw.get("defaultBundle")),
                    listOfStrings(raw.get("repos")),
                    parseCatalogLibraries(raw.get("catalogLibraries")),
                    parseServiceModules(raw.get("serviceModules")),
                    bundles
            );
        } catch (Exception ex) {
            log.warn("Failed to load workspace config from {}: {}", resource, ex.getMessage());
            return empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<WorkspaceConfig.CatalogLibraryConfig> parseCatalogLibraries(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(m -> (Map<String, Object>) m)
                .map(m -> new WorkspaceConfig.CatalogLibraryConfig(
                        string(m.get("id")),
                        string(m.get("repo")),
                        string(m.get("serviceName")),
                        listOfStrings(m.get("sourceRoots")),
                        bool(m.get("indexDdl")),
                        bool(m.get("indexOpenApi"))
                ))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<WorkspaceConfig.ServiceModuleConfig> parseServiceModules(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(m -> (Map<String, Object>) m)
                .map(m -> new WorkspaceConfig.ServiceModuleConfig(
                        string(m.get("id")),
                        string(m.get("repo")),
                        listOfStrings(m.get("sourceRoots")),
                        parseSymbolClasspath(m.get("symbolClasspath")),
                        listOfStrings(m.get("configRoots"))
                ))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<WorkspaceConfig.SymbolClasspathEntry> parseSymbolClasspath(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(m -> (Map<String, Object>) m)
                .map(m -> new WorkspaceConfig.SymbolClasspathEntry(
                        string(m.get("catalogLibrary")),
                        string(m.get("repo")),
                        listOfStrings(m.get("roots"))
                ))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<WorkspaceConfig.BundleIndexEntry> parseIndexOrder(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(m -> (Map<String, Object>) m)
                .map(m -> new WorkspaceConfig.BundleIndexEntry(
                        string(m.get("catalogLibrary")),
                        string(m.get("serviceModule")),
                        string(m.get("repo"))
                ))
                .toList();
    }

    private InputStream openStream(Resource resource) throws IOException {
        if (resource.exists()) {
            return resource.getInputStream();
        }
        String path = System.getenv("TESTSEER_CONFIG");
        if (path != null && !path.isBlank() && Files.isRegularFile(Path.of(path))) {
            return Files.newInputStream(Path.of(path));
        }
        return null;
    }

    private static WorkspaceConfig empty() {
        return new WorkspaceConfig(null, null, null, List.of(), List.of(), List.of(), Map.of());
    }

    @SuppressWarnings("unchecked")
    private static List<String> listOfStrings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return null;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean b) return b;
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static List<WorkspaceConfig.PropagationEdgeConfig> parsePropagationEdges(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<WorkspaceConfig.PropagationEdgeConfig> edges = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            Map<String, Object> edge = (Map<String, Object>) m;
            WorkspaceConfig.PropagationStoreRef authoritative = parseStoreRef(edge.get("authoritative"));
            List<WorkspaceConfig.PropagationPeripheralConfig> peripheral = new ArrayList<>();
            if (edge.get("peripheral") instanceof List<?> perList) {
                for (Object p : perList) {
                    if (p instanceof Map<?, ?> pm) {
                        peripheral.add(new WorkspaceConfig.PropagationPeripheralConfig(
                                string(pm.get("serviceId")),
                                string(pm.get("storeType")),
                                string(pm.get("physicalName")),
                                string(pm.get("lagClass"))));
                    }
                }
            }
            List<WorkspaceConfig.PropagationConsumerConfig> consumers = new ArrayList<>();
            if (edge.get("consumers") instanceof List<?> conList) {
                for (Object c : conList) {
                    if (c instanceof Map<?, ?> cm) {
                        consumers.add(new WorkspaceConfig.PropagationConsumerConfig(
                                string(cm.get("serviceId")),
                                string(cm.get("flowStep"))));
                    }
                }
            }
            WorkspaceConfig.PropagationPollStrategy poll = null;
            if (edge.get("pollStrategy") instanceof Map<?, ?> ps) {
                poll = new WorkspaceConfig.PropagationPollStrategy(
                        listOfStrings(ps.get("order")),
                        string(ps.get("primaryPollHint")),
                        listOfStrings(ps.get("notes")));
            }
            edges.add(new WorkspaceConfig.PropagationEdgeConfig(
                    string(edge.get("id")),
                    string(edge.get("pattern")),
                    authoritative,
                    peripheral,
                    consumers,
                    listOfStrings(edge.get("correlationKeys")),
                    poll));
        }
        return edges;
    }

    @SuppressWarnings("unchecked")
    private static WorkspaceConfig.PropagationStoreRef parseStoreRef(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        return new WorkspaceConfig.PropagationStoreRef(
                string(m.get("serviceId")),
                string(m.get("storeType")),
                string(m.get("physicalName")));
    }
}
