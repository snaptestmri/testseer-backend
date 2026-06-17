package io.testseer.backend.ingestion.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static import index for resolving {@code NameExpr} field references in annotations (TRG-18-R).
 */
public final class StaticImportIndex {

    private static final Pattern STATIC_IMPORT = Pattern.compile(
            "^import\\s+static\\s+([\\w.]+)(\\.\\*|\\.[\\w]+);\\s*$",
            Pattern.MULTILINE);

    private final Map<String, String> staticFieldImports;
    private final List<String> wildcardStaticTypes;

    private StaticImportIndex(Map<String, String> staticFieldImports, List<String> wildcardStaticTypes) {
        this.staticFieldImports = Map.copyOf(staticFieldImports);
        this.wildcardStaticTypes = List.copyOf(wildcardStaticTypes);
    }

    public static StaticImportIndex build(String sourceContent) {
        if (sourceContent == null || sourceContent.isBlank()) {
            return new StaticImportIndex(Map.of(), List.of());
        }
        Map<String, String> fields = new HashMap<>();
        List<String> wildcards = new ArrayList<>();
        Matcher matcher = STATIC_IMPORT.matcher(sourceContent);
        while (matcher.find()) {
            String target = matcher.group(1);
            String suffix = matcher.group(2);
            if (".*".equals(suffix)) {
                wildcards.add(target);
            } else if (suffix.startsWith(".")) {
                String fieldName = suffix.substring(1);
                fields.put(fieldName, StringConstantIndex.fieldFqn(target, fieldName));
            }
        }
        return new StaticImportIndex(fields, wildcards);
    }

    public String resolveStaticFieldFqn(String simpleName) {
        if (simpleName == null) {
            return null;
        }
        return staticFieldImports.get(simpleName);
    }

    public List<String> wildcardStaticTypes() {
        return wildcardStaticTypes;
    }
}
