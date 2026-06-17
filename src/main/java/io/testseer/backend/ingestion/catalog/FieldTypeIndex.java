package io.testseer.backend.ingestion.catalog;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maps field/parameter names to declared type FQNs within a Java source file. */
final class FieldTypeIndex {

    private static final Pattern FIELD =
            Pattern.compile("(?:private|protected|public)\\s+(?:final\\s+)?([\\w<>,.\\s\\[\\]]+?)\\s+(\\w+)\\s*[;=]");
    private static final Pattern CONSTRUCTOR_PARAM =
            Pattern.compile("(?:public|protected)\\s+\\w+\\s*\\(([^)]*)\\)");
    private static final Pattern PARAM =
            Pattern.compile("([\\w<>,.\\s\\[\\]]+?)\\s+(\\w+)\\s*(?:,|\\))");

    private final Map<String, String> nameToTypeFqn = new HashMap<>();

    private FieldTypeIndex(Map<String, String> nameToTypeFqn) {
        this.nameToTypeFqn.putAll(nameToTypeFqn);
    }

    static FieldTypeIndex build(String content, ImportIndex imports) {
        return build(content, imports, null, null);
    }

    static FieldTypeIndex build(
            String content,
            ImportIndex imports,
            TypeFqnResolver resolver,
            TypeFqnResolver.CompilationContext ctx) {
        Map<String, String> map = new HashMap<>();
        Matcher fm = FIELD.matcher(content);
        while (fm.find()) {
            put(map, fm.group(2), resolveType(resolver, imports, ctx, fm.group(1)));
        }
        Matcher cm = CONSTRUCTOR_PARAM.matcher(content);
        while (cm.find()) {
            Matcher pm = PARAM.matcher(cm.group(1) + ")");
            while (pm.find()) {
                put(map, pm.group(2), resolveType(resolver, imports, ctx, pm.group(1)));
            }
        }
        Matcher assign = Pattern.compile("this\\.(\\w+)\\s*=\\s*(\\w+)").matcher(content);
        while (assign.find()) {
            String field = assign.group(1);
            String param = assign.group(2);
            if (map.containsKey(param) && !map.containsKey(field)) {
                map.put(field, map.get(param));
            }
        }
        return new FieldTypeIndex(map);
    }

    private static String resolveType(
            TypeFqnResolver resolver,
            ImportIndex imports,
            TypeFqnResolver.CompilationContext ctx,
            String rawType) {
        if (resolver != null && ctx != null) {
            return resolver.resolve(rawType, imports, ctx).fqn();
        }
        return imports.resolveType(rawType);
    }

    private static void put(Map<String, String> map, String name, String typeFqn) {
        if (name != null && typeFqn != null && !typeFqn.isBlank()) {
            map.put(name, typeFqn);
        }
    }

    String resolveAccessorFqn(String fieldName, ImportIndex imports) {
        String type = nameToTypeFqn.get(fieldName);
        if (type != null) return type;
        return imports.resolve(fieldName);
    }
}
