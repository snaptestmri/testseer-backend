package io.testseer.backend.ingestion.triggers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.ParsedModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * BL-054: indexes long-running {@code @SpringBootApplication} mains as {@code SPRING_BOOT_MAIN}
 * (deploy-time wiring only — not a business ingress).
 */
@Component
public class SpringBootMainTriggerExtractor {

    private final ObjectMapper mapper = new ObjectMapper();

    public List<FactBatch.EntryTriggerFact> extract(
            List<ParsedModel> models,
            Map<String, String> contentByPath,
            String defaultEnvLane) {
        return extract(models, contentByPath, defaultEnvLane, Map.of());
    }

    public List<FactBatch.EntryTriggerFact> extract(
            List<ParsedModel> models,
            Map<String, String> contentByPath,
            String defaultEnvLane,
            Map<String, String> pomHints) {

        List<FactBatch.EntryTriggerFact> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (models == null) {
            return results;
        }

        Map<String, String> moduleToMain = pomModuleToMainClass(pomHints);
        Set<String> pomMainClasses = pomMainClassFqns(pomHints);

        for (ParsedModel model : models) {
            if (model.classFqn() == null) {
                continue;
            }
            String content = contentByPath != null ? contentByPath.get(model.filePath()) : null;
            if (!SpringBootLauncherDetector.isLongRunningSpringBootMain(model, content)) {
                continue;
            }

            String moduleDir = SpringBootLauncherDetector.moduleDirFromJavaPath(model.filePath());
            if (moduleDir != null && moduleToMain.containsKey(moduleDir)) {
                String expectedMain = moduleToMain.get(moduleDir);
                if (!expectedMain.equals(model.classFqn())) {
                    continue;
                }
            }

            String triggerId = "spring-boot:" + model.classFqn();
            if (!seen.add(triggerId)) {
                continue;
            }

            boolean pomDeclared = pomMainClasses.contains(model.classFqn());
            String pathPattern = "/deploy/" + (moduleDir != null ? moduleDir : simpleName(model.classFqn()));

            results.add(new FactBatch.EntryTriggerFact(
                    triggerId,
                    "SPRING_BOOT_MAIN",
                    "INBOUND",
                    defaultEnvLane != null ? defaultEnvLane : "unknown",
                    "deploy",
                    "INTERNAL",
                    null,
                    pathPattern,
                    model.classFqn(),
                    "main",
                    null,
                    model.filePath(),
                    pomDeclared ? "JAVA_SPRING_BOOT_MAIN+POM" : "JAVA_SPRING_BOOT_MAIN",
                    pomDeclared ? 0.95 : 0.85,
                    attributes(model.classFqn(), moduleDir, pomDeclared)
            ));
        }
        return results;
    }

    private static Map<String, String> pomModuleToMainClass(Map<String, String> pomHints) {
        Map<String, String> moduleToMain = new LinkedHashMap<>();
        if (pomHints == null) {
            return moduleToMain;
        }
        for (Map.Entry<String, String> e : pomHints.entrySet()) {
            if (e.getKey().contains(".") && e.getValue() != null && !e.getValue().contains(".")) {
                moduleToMain.put(e.getValue(), e.getKey());
            }
        }
        return moduleToMain;
    }

    private static Set<String> pomMainClassFqns(Map<String, String> pomHints) {
        Set<String> mains = new LinkedHashSet<>();
        if (pomHints == null) {
            return mains;
        }
        for (String key : pomHints.keySet()) {
            if (key.contains(".")) {
                mains.add(key);
            }
        }
        return mains;
    }

    private String attributes(String classFqn, String moduleDir, boolean pomDeclared) {
        try {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("role", "wiring_only");
            attrs.put("workloadKind", "long_running_consumer");
            if (moduleDir != null) {
                attrs.put("moduleDir", moduleDir);
            }
            attrs.put("mainClassDeclaredInPom", pomDeclared);
            return mapper.writeValueAsString(attrs);
        } catch (JsonProcessingException ex) {
            return "{\"role\":\"wiring_only\"}";
        }
    }

    private static String simpleName(String fqn) {
        int idx = fqn.lastIndexOf('.');
        return idx >= 0 ? fqn.substring(idx + 1) : fqn;
    }
}
