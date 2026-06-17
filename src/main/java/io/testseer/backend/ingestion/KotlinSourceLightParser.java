package io.testseer.backend.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Regex-based Kotlin metadata for catalog indexing (P6 — no full Kotlin AST). */
public final class KotlinSourceLightParser {

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)", Pattern.MULTILINE);
    private static final Pattern TOP_LEVEL_TYPE =
            Pattern.compile("(?:^|\\n)\\s*(?:data\\s+)?(?:class|interface|object|enum\\s+class)\\s+(\\w+)");
    private static final Pattern KOTLIN_DOCUMENT =
            Pattern.compile(
                    "@Document\\s*\\(\\s*collection\\s*=\\s*[\"']([^\"']+)[\"']\\s*\\)[\\s\\S]*?"
                            + "(?:data\\s+)?class\\s+(\\w+)",
                    Pattern.MULTILINE);

    private KotlinSourceLightParser() {}

    public static Optional<String> packageName(String content) {
        if (content == null) return Optional.empty();
        Matcher m = PACKAGE.matcher(content);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    public static Optional<String> firstTopLevelTypeFqn(String filePath, String content) {
        Optional<String> pkg = packageName(content);
        if (content == null) return Optional.empty();
        Matcher m = TOP_LEVEL_TYPE.matcher(content);
        if (!m.find()) return Optional.empty();
        String simple = m.group(1);
        return pkg.map(p -> p + "." + simple).or(() -> Optional.of(simple));
    }

    public record KotlinDocumentType(String classFqn, String collection) {}

    /** All @Document(collection=...) types in a Kotlin file (multi-class files like MongoModels.kt). */
    public static List<KotlinDocumentType> documentTypes(String filePath, String content) {
        List<KotlinDocumentType> out = new ArrayList<>();
        if (content == null || !content.contains("@Document")) return out;
        Optional<String> pkg = packageName(content);
        Matcher m = KOTLIN_DOCUMENT.matcher(content);
        while (m.find()) {
            String collection = m.group(1);
            String simple = m.group(2);
            String fqn = pkg.map(p -> p + "." + simple).orElse(simple);
            out.add(new KotlinDocumentType(fqn, collection));
        }
        return out;
    }

    public static boolean isKotlinPath(String path) {
        return path != null && path.endsWith(".kt");
    }
}
