package io.testseer.backend.ingestion.catalog;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Phase 2: simple import-line index for FQN resolution (Phase 4 replaces with full PSI). */
public final class ImportIndex {

    private static final Pattern IMPORT =
            Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+(?:\\.\\*)?);\\s*$", Pattern.MULTILINE);

    private final Map<String, String> simpleToFqn;
    private final String packageName;

    private ImportIndex(String packageName, Map<String, String> simpleToFqn) {
        this.packageName = packageName;
        this.simpleToFqn = simpleToFqn;
    }

    public static ImportIndex build(String content) {
        String pkg = null;
        Matcher pkgM = Pattern.compile("^package\\s+([\\w.]+);\\s*$", Pattern.MULTILINE).matcher(content);
        if (pkgM.find()) pkg = pkgM.group(1);

        Map<String, String> map = new HashMap<>();
        Matcher im = IMPORT.matcher(content);
        while (im.find()) {
            String fqn = im.group(1);
            if (fqn.endsWith(".*")) continue;
            int dot = fqn.lastIndexOf('.');
            if (dot >= 0) map.put(fqn.substring(dot + 1), fqn);
        }
        return new ImportIndex(pkg, map);
    }

    public String resolveExplicit(String simpleName) {
        if (simpleName == null) return null;
        return simpleToFqn.get(simpleName);
    }

    public String samePackageResolve(String simpleName) {
        if (simpleName == null) return null;
        if (packageName != null) return packageName + "." + simpleName;
        return simpleName;
    }

    public String packageName() {
        return packageName;
    }

    public String resolve(String simpleName) {
        if (simpleName == null) return null;
        String fqn = simpleToFqn.get(simpleName);
        if (fqn != null) return fqn;
        return samePackageResolve(simpleName);
    }

    public String resolveType(String typeWithGenerics) {
        if (typeWithGenerics == null) return null;
        String base = typeWithGenerics.trim();
        int generic = base.indexOf('<');
        if (generic >= 0) base = base.substring(0, generic).trim();
        int dot = base.lastIndexOf('.');
        if (dot >= 0) return base;
        String explicit = resolveExplicit(base);
        if (explicit != null) return explicit;
        return samePackageResolve(base);
    }
}
