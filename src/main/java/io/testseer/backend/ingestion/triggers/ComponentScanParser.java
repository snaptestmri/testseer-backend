package io.testseer.backend.ingestion.triggers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses {@code @ComponentScan(basePackageClasses = { ... })} targets from Java source. */
public final class ComponentScanParser {

    private static final Pattern IMPORT =
            Pattern.compile("^import\\s+([\\w.]+)\\.([\\w]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern COMPONENT_SCAN_BLOCK =
            Pattern.compile("@ComponentScan\\s*\\(\\s*basePackageClasses\\s*=\\s*\\{([^}]+)}",
                    Pattern.DOTALL);
    private static final Pattern CLASS_REF = Pattern.compile("([\\w]+)\\.class");

    private ComponentScanParser() {}

    public static List<String> resolveScanTargetFqns(String source, String declaringClassFqn) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        Matcher block = COMPONENT_SCAN_BLOCK.matcher(source);
        if (!block.find()) {
            return List.of();
        }
        String body = block.group(1);
        Map<String, String> imports = parseImports(source);
        String defaultPackage = packageName(declaringClassFqn);

        List<String> targets = new ArrayList<>();
        Matcher ref = CLASS_REF.matcher(body);
        while (ref.find()) {
            String simple = ref.group(1);
            String fqn = imports.getOrDefault(simple, defaultPackage != null ? defaultPackage + "." + simple : simple);
            if (fqn != null && !fqn.isBlank()) {
                targets.add(fqn);
            }
        }
        return targets;
    }

    private static Map<String, String> parseImports(String source) {
        Map<String, String> imports = new LinkedHashMap<>();
        Matcher m = IMPORT.matcher(source);
        while (m.find()) {
            imports.put(m.group(2), m.group(1) + "." + m.group(2));
        }
        return imports;
    }

    private static String packageName(String classFqn) {
        if (classFqn == null) {
            return null;
        }
        int dot = classFqn.lastIndexOf('.');
        return dot > 0 ? classFqn.substring(0, dot) : null;
    }
}
