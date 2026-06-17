package io.testseer.backend.ingestion.maven;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Parses Maven {@code pom.xml} structure — modules, coordinates, declared dependencies. */
public final class PomStructureExtractor {

    private PomStructureExtractor() {}

    public record ParsedPom(
            String relativePomPath,
            String modulePath,
            String groupId,
            String artifactId,
            String version,
            String packaging,
            String parentGroupId,
            String parentArtifactId,
            String parentVersion,
            boolean rootModule,
            List<String> childModules,
            List<DeclaredDependency> dependencies
    ) {}

    public record DeclaredDependency(
            String groupId,
            String artifactId,
            String versionLiteral,
            String scope,
            boolean optional
    ) {}

    public static List<ParsedPom> extractAll(List<PomInput> poms) {
        Map<String, ParsedPom> byPath = new LinkedHashMap<>();
        for (PomInput pom : poms) {
            ParsedPom parsed = parseOne(pom.relativePomPath(), pom.content());
            if (parsed != null) {
                byPath.put(parsed.relativePomPath(), parsed);
            }
        }
        resolveInheritedCoordinates(byPath);
        return List.copyOf(byPath.values());
    }

    public record PomInput(String relativePomPath, String content) {}

    private static ParsedPom parseOne(String relativePomPath, String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setNamespaceAware(false);
            Document doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
            Element project = doc.getDocumentElement();
            if (project == null) {
                return null;
            }

            String modulePath = modulePathFromPom(relativePomPath);
            String packaging = textOf(project, "packaging");
            if (packaging == null || packaging.isBlank()) {
                packaging = "jar";
            }

            Element parent = firstChild(project, "parent");
            String parentGroupId = parent != null ? textOf(parent, "groupId") : null;
            String parentArtifactId = parent != null ? textOf(parent, "artifactId") : null;
            String parentVersion = parent != null ? textOf(parent, "version") : null;

            List<String> modules = childModulePaths(project, modulePath);
            List<DeclaredDependency> deps = parseDependencies(project);

            boolean root = "pom.xml".equalsIgnoreCase(relativePomPath)
                    || relativePomPath.isBlank();

            return new ParsedPom(
                    relativePomPath,
                    modulePath,
                    textOf(project, "groupId"),
                    textOf(project, "artifactId"),
                    textOf(project, "version"),
                    packaging,
                    parentGroupId,
                    parentArtifactId,
                    parentVersion,
                    root,
                    modules,
                    deps
            );
        } catch (Exception ex) {
            return null;
        }
    }

    private static void resolveInheritedCoordinates(Map<String, ParsedPom> byPath) {
        Map<String, ParsedPom> rebuilt = new LinkedHashMap<>();
        for (ParsedPom pom : byPath.values()) {
            String groupId = pom.groupId();
            String version = pom.version();
            if ((groupId == null || groupId.isBlank()) && pom.parentGroupId() != null) {
                groupId = pom.parentGroupId();
            }
            if ((version == null || version.isBlank()) && pom.parentVersion() != null) {
                version = pom.parentVersion();
            }
            rebuilt.put(pom.relativePomPath(), new ParsedPom(
                    pom.relativePomPath(), pom.modulePath(),
                    groupId, pom.artifactId(), version, pom.packaging(),
                    pom.parentGroupId(), pom.parentArtifactId(), pom.parentVersion(),
                    pom.rootModule(), pom.childModules(), pom.dependencies()
            ));
        }
        byPath.clear();
        byPath.putAll(rebuilt);
    }

    private static List<DeclaredDependency> parseDependencies(Element project) {
        Element depsEl = firstChild(project, "dependencies");
        if (depsEl == null) {
            return List.of();
        }
        List<DeclaredDependency> result = new ArrayList<>();
        NodeList children = depsEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element el) || !"dependency".equals(el.getTagName())) {
                continue;
            }
            String groupId = textOf(el, "groupId");
            String artifactId = textOf(el, "artifactId");
            if (groupId == null || artifactId == null) {
                continue;
            }
            String versionLiteral = textOf(el, "version");
            String scope = textOf(el, "scope");
            if (scope == null || scope.isBlank()) {
                scope = "compile";
            }
            boolean optional = "true".equalsIgnoreCase(textOf(el, "optional"));
            result.add(new DeclaredDependency(groupId, artifactId, versionLiteral, scope, optional));
        }
        return result;
    }

    private static List<String> childModulePaths(Element project, String parentModulePath) {
        Element modulesEl = firstChild(project, "modules");
        if (modulesEl == null) {
            return List.of();
        }
        Set<String> modules = new LinkedHashSet<>();
        NodeList children = modulesEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element el) || !"module".equals(el.getTagName())) {
                continue;
            }
            String raw = el.getTextContent();
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String child = raw.trim().replace('\\', '/');
            if (parentModulePath.isBlank()) {
                modules.add(child);
            } else {
                modules.add(parentModulePath + "/" + child);
            }
        }
        return List.copyOf(modules);
    }

    static String modulePathFromPom(String relativePomPath) {
        if (relativePomPath == null || relativePomPath.isBlank() || "pom.xml".equalsIgnoreCase(relativePomPath)) {
            return "";
        }
        String normalized = relativePomPath.replace('\\', '/');
        if (!normalized.toLowerCase(Locale.ROOT).endsWith("/pom.xml")) {
            return normalized;
        }
        return normalized.substring(0, normalized.length() - "/pom.xml".length());
    }

    public static boolean isUnresolvedVersion(String versionLiteral) {
        if (versionLiteral == null || versionLiteral.isBlank()) {
            return true;
        }
        return versionLiteral.contains("${");
    }

    public static String unresolvedReason(String versionLiteral) {
        if (versionLiteral == null || versionLiteral.isBlank()) {
            return "MISSING_VERSION";
        }
        if (versionLiteral.contains("${")) {
            return "PROPERTY";
        }
        return null;
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node node = list.item(i);
            if (node instanceof Element el && tag.equals(el.getTagName())) {
                return el;
            }
        }
        return null;
    }

    private static String textOf(Element parent, String tag) {
        Element child = firstChild(parent, tag);
        if (child == null) {
            return null;
        }
        String text = child.getTextContent();
        return text != null ? text.trim() : null;
    }
}
