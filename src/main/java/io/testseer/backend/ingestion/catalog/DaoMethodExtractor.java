package io.testseer.backend.ingestion.catalog;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.messaging.ProtoSchemaExtractor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DaoMethodExtractor {

    private static final Pattern INTERFACE_DECL =
            Pattern.compile("(?:public\\s+)?interface\\s+(\\w+)");
    private static final Pattern IMPL_DECL =
            Pattern.compile("(?:public\\s+)?class\\s+(\\w+)[^{]*implements\\s+([\\w.]+)");
    private static final Pattern METHOD_SIG =
            Pattern.compile("\\s*(?:public\\s+)?([\\w<>,\\[\\].\\s]+?)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:;|\\{)", Pattern.MULTILINE);
    private static final Pattern REPO_SAVE =
            Pattern.compile("(\\w+(?:Repo|Repository))\\.(save|delete|insert|update\\w*)\\s*\\(");
    private static final Pattern PARAM_TYPE =
            Pattern.compile("([\\w<>,.\\[\\]]+?)\\s+\\w+");

    private final StoreTypeInferencer storeTypeInferencer;

    public DaoMethodExtractor(StoreTypeInferencer storeTypeInferencer) {
        this.storeTypeInferencer = storeTypeInferencer;
    }

    public List<FactBatch.AccessorMethodFact> extract(
            List<ProtoSchemaExtractor.JavaSourceFile> javaFiles,
            List<FactBatch.DataObjectFact> catalogEntities) {

        Map<String, FactBatch.DataObjectFact> entityByFqn = indexEntities(catalogEntities);
        Map<String, String> implToInterface = new HashMap<>();
        Map<String, String> interfaceFqnBySimple = new HashMap<>();
        Map<String, Map<String, String>> implDomainMaps = new HashMap<>();

        for (ProtoSchemaExtractor.JavaSourceFile file : javaFiles) {
            if (file.classFqn() == null) continue;
            ImportIndex imports = ImportIndex.build(file.content());
            Matcher impl = IMPL_DECL.matcher(file.content());
            if (impl.find()) {
                String ifaceFqn = imports.resolveType(impl.group(2));
                if (ifaceFqn != null) {
                    implToInterface.put(file.classFqn(), ifaceFqn);
                    int dot = ifaceFqn.lastIndexOf('.');
                    if (dot >= 0) interfaceFqnBySimple.put(ifaceFqn.substring(dot + 1), ifaceFqn);
                }
                implDomainMaps.put(file.classFqn(), DomainEntityLinker.parseDomainToEntity(file.content(), imports));
            }
            Matcher iface = INTERFACE_DECL.matcher(file.content());
            if (iface.find() && file.classFqn().endsWith("Dao") && !file.classFqn().endsWith("DaoImpl")) {
                int dot = file.classFqn().lastIndexOf('.');
                if (dot >= 0) interfaceFqnBySimple.put(file.classFqn().substring(dot + 1), file.classFqn());
            }
        }

        List<FactBatch.AccessorMethodFact> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (ProtoSchemaExtractor.JavaSourceFile file : javaFiles) {
            if (file.classFqn() == null) continue;
            if (!isDaoSource(file)) continue;

            ImportIndex imports = ImportIndex.build(file.content());
            String accessorFqn = resolveAccessorFqn(file, implToInterface, interfaceFqnBySimple);
            if (accessorFqn == null) continue;

            Map<String, String> domainMap = findDomainMap(file.classFqn(), implToInterface, implDomainMaps);
            String implBody = findImplBody(file.classFqn(), implToInterface, javaFiles);

            Matcher mm = METHOD_SIG.matcher(file.content());
            while (mm.find()) {
                String returnType = mm.group(1).trim();
                String methodName = mm.group(2);
                if ("class".equals(methodName) || methodName.startsWith("_")) continue;

                String operation = operationOf(methodName);
                String domainFqn = firstDomainParam(mm.group(3), imports, domainMap);
                String entityFqn = resolveEntity(domainFqn, domainMap, entityByFqn, implBody, methodName);
                CatalogRef ref = catalogRef(entityFqn, entityByFqn);
                String storeType = resolveStoreType(ref, accessorFqn);

                String key = accessorFqn + "|" + methodName;
                if (!seen.add(key)) continue;

                double confidence = entityFqn != null ? 0.93 : (domainFqn != null ? 0.80 : 0.75);
                results.add(new FactBatch.AccessorMethodFact(
                        accessorKind(accessorFqn),
                        accessorFqn,
                        methodName,
                        operation,
                        entityFqn,
                        domainFqn,
                        storeType,
                        ref.physicalName(),
                        entityFqn != null ? "DAO_METHOD+ENTITY" : "DAO_METHOD",
                        confidence
                ));
            }
        }
        return results;
    }

    private static boolean isDaoSource(ProtoSchemaExtractor.JavaSourceFile file) {
        String fqn = file.classFqn();
        return fqn.endsWith("DaoImpl") || (fqn.endsWith("Dao") && !fqn.endsWith("DaoImpl"));
    }

    private static String resolveAccessorFqn(
            ProtoSchemaExtractor.JavaSourceFile file,
            Map<String, String> implToInterface,
            Map<String, String> interfaceFqnBySimple) {
        String iface = implToInterface.get(file.classFqn());
        if (iface != null) return iface;
        if (file.classFqn().endsWith("Dao") && !file.classFqn().endsWith("DaoImpl")) {
            return file.classFqn();
        }
        int dot = file.classFqn().lastIndexOf('.');
        if (dot >= 0) {
            String simple = file.classFqn().substring(dot + 1);
            if (simple.endsWith("DaoImpl")) {
                return interfaceFqnBySimple.get(simple.replace("Impl", ""));
            }
        }
        return null;
    }

    private static Map<String, String> findDomainMap(
            String classFqn,
            Map<String, String> implToInterface,
            Map<String, Map<String, String>> implDomainMaps) {
        Map<String, String> direct = implDomainMaps.get(classFqn);
        if (direct != null) return direct;
        for (var e : implToInterface.entrySet()) {
            if (classFqn.equals(e.getValue())) {
                return implDomainMaps.getOrDefault(e.getKey(), Map.of());
            }
        }
        return Map.of();
    }

    private static String findImplBody(
            String classFqn,
            Map<String, String> implToInterface,
            List<ProtoSchemaExtractor.JavaSourceFile> javaFiles) {
        if (classFqn.endsWith("DaoImpl")) {
            return javaFiles.stream()
                    .filter(f -> classFqn.equals(f.classFqn()))
                    .map(ProtoSchemaExtractor.JavaSourceFile::content)
                    .findFirst().orElse("");
        }
        for (var e : implToInterface.entrySet()) {
            if (classFqn.equals(e.getValue())) {
                return javaFiles.stream()
                        .filter(f -> e.getKey().equals(f.classFqn()))
                        .map(ProtoSchemaExtractor.JavaSourceFile::content)
                        .findFirst().orElse("");
            }
        }
        return "";
    }

    private static String firstDomainParam(String params, ImportIndex imports, Map<String, String> domainMap) {
        if (params == null || params.isBlank()) return null;
        Matcher pm = PARAM_TYPE.matcher(params + ",");
        while (pm.find()) {
            String typeFqn = imports.resolveType(pm.group(1).trim());
            if (typeFqn == null) continue;
            if (domainMap.containsKey(typeFqn)) return typeFqn;
            if (!typeFqn.endsWith("Entity") && typeFqn.contains(".domain.")) return typeFqn;
        }
        return null;
    }

    private static String resolveEntity(
            String domainFqn,
            Map<String, String> domainMap,
            Map<String, FactBatch.DataObjectFact> entityByFqn,
            String implBody,
            String methodName) {
        if (domainFqn != null && domainMap.containsKey(domainFqn)) {
            return domainMap.get(domainFqn);
        }
        if (domainFqn != null) {
            for (FactBatch.DataObjectFact e : entityByFqn.values()) {
                if (domainFqn.equals(e.domainFqn())) return e.entityFqn();
            }
        }
        Matcher rm = REPO_SAVE.matcher(extractMethodBody(implBody, methodName));
        if (rm.find()) {
            // entity type inferred from mapDomainToEntity call in same method body
            Matcher dm = Pattern.compile("(\\w+Entity)\\s*=\\s*mapDomainToEntity").matcher(extractMethodBody(implBody, methodName));
            if (dm.find()) {
                ImportIndex imports = ImportIndex.build(implBody);
                return imports.resolve(dm.group(1));
            }
        }
        return null;
    }

    private static String extractMethodBody(String content, String methodName) {
        Pattern start = Pattern.compile("(public|protected)\\s+[\\w<>,\\[\\].\\s]+\\s+" + Pattern.quote(methodName) + "\\s*\\([^)]*\\)\\s*\\{");
        Matcher m = start.matcher(content);
        if (!m.find()) return "";
        int brace = m.end();
        int depth = 1;
        int i = brace;
        while (i < content.length() && depth > 0) {
            char c = content.charAt(i++);
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return content.substring(m.start(), Math.min(i, content.length()));
    }

    private static Map<String, FactBatch.DataObjectFact> indexEntities(List<FactBatch.DataObjectFact> entities) {
        Map<String, FactBatch.DataObjectFact> map = new LinkedHashMap<>();
        for (FactBatch.DataObjectFact e : entities) {
            map.put(e.entityFqn(), e);
        }
        return map;
    }

    private record CatalogRef(String storeType, String physicalName) {
        static CatalogRef empty() { return new CatalogRef(null, null); }
    }

    private static CatalogRef catalogRef(String entityFqn, Map<String, FactBatch.DataObjectFact> entityByFqn) {
        if (entityFqn == null) return CatalogRef.empty();
        FactBatch.DataObjectFact e = entityByFqn.get(entityFqn);
        if (e == null) return CatalogRef.empty();
        return new CatalogRef(e.storeType(), e.physicalName());
    }

    private String resolveStoreType(CatalogRef ref, String accessorFqn) {
        if (ref.storeType() != null) {
            return ref.storeType();
        }
        StoreType inferred = StoreType.fromPackageHint(accessorFqn);
        return (inferred != StoreType.UNKNOWN ? inferred : StoreType.UNKNOWN).dbValue();
    }

    static String operationOf(String methodName) {
        String lower = methodName.toLowerCase(Locale.ROOT);
        if (lower.startsWith("find") || lower.startsWith("get") || lower.startsWith("is")
                || lower.startsWith("load") || lower.startsWith("count") || lower.startsWith("exists")) {
            return "READ";
        }
        if (lower.startsWith("save") || lower.startsWith("insert") || lower.startsWith("mark")
                || lower.startsWith("update") || lower.startsWith("delete") || lower.contains("persist")) {
            return "WRITE";
        }
        return "READ";
    }

    static String accessorKind(String accessorFqn) {
        if (accessorFqn == null) return "UNKNOWN";
        if (accessorFqn.endsWith("Dao")) return "DAO";
        if (accessorFqn.endsWith("Repository") || accessorFqn.endsWith("Repo")) return "REPO";
        if (accessorFqn.contains("Template")) return "TEMPLATE";
        return "ACCESSOR";
    }
}
