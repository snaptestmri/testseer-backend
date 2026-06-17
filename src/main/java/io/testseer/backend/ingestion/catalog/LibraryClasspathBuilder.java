package io.testseer.backend.ingestion.catalog;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import io.testseer.backend.config.WorkspaceCatalogService;
import io.testseer.backend.config.WorkspaceConfig;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Builds symbol classpath from workspace.yml for cross-module FQN resolution (SYM-CAT-01). */
@Component
public class LibraryClasspathBuilder {

    private final WorkspaceCatalogService workspaceCatalog;

    public LibraryClasspathBuilder(WorkspaceCatalogService workspaceCatalog) {
        this.workspaceCatalog = workspaceCatalog;
    }

    public SymbolResolutionContext build(
            String orgId,
            String serviceId,
            String owningClassFqn,
            List<String> pinnedCatalogLibraryIds,
            List<Path> extraSourceRoots) {

        CombinedTypeSolver solver = new CombinedTypeSolver();
        solver.add(new ReflectionTypeSolver());

        Path githubRoot = workspaceCatalog.resolveGithubRoot(orgId);
        Map<String, String> simpleNameToFqn = new LinkedHashMap<>();

        List<Path> roots = new ArrayList<>();
        if (extraSourceRoots != null) roots.addAll(extraSourceRoots);

        if (githubRoot != null && pinnedCatalogLibraryIds != null) {
            for (String libId : pinnedCatalogLibraryIds) {
                workspaceCatalog.findCatalogLibrary(orgId, libId).ifPresent(lib -> {
                    Path repoRoot = githubRoot.resolve(lib.repo());
                    for (String root : lib.sourceRoots()) {
                        Path p = repoRoot.resolve(root);
                        if (Files.isDirectory(p)) {
                            roots.add(p);
                            solver.add(new JavaParserTypeSolver(p));
                        }
                    }
                });
            }
        }

        for (Path root : roots) {
            indexSimpleNames(root, simpleNameToFqn);
        }

        JavaParser parser = new JavaParser();
        parser.getParserConfiguration().setSymbolResolver(new JavaSymbolSolver(solver));

        return new SymbolResolutionContext(
                orgId, serviceId, owningClassFqn,
                pinnedCatalogLibraryIds != null ? pinnedCatalogLibraryIds : List.of(),
                parser, solver, simpleNameToFqn
        );
    }

    public SymbolResolutionContext forServiceModule(
            String orgId,
            String serviceModuleId,
            String owningClassFqn,
            Path serviceRepoRoot,
            List<String> serviceSourceRoots) {

        List<String> pinned = workspaceCatalog.pinnedCatalogLibraryIdsForService(orgId, serviceModuleId);
        List<Path> extra = new ArrayList<>();
        if (serviceRepoRoot != null && serviceSourceRoots != null) {
            for (String root : serviceSourceRoots) {
                extra.add(serviceRepoRoot.resolve(root));
            }
        }
        return build(orgId, serviceModuleId, owningClassFqn, pinned, extra);
    }

    private static void indexSimpleNames(Path sourceRoot, Map<String, String> simpleNameToFqn) {
        if (!Files.isDirectory(sourceRoot)) return;
        try (var walk = Files.walk(sourceRoot)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            ParseResult<CompilationUnit> parsed = new JavaParser().parse(p);
                            if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) return;
                            CompilationUnit cu = parsed.getResult().get();
                            String pkg = cu.getPackageDeclaration()
                                    .map(pd -> pd.getNameAsString())
                                    .orElse("");
                            for (TypeDeclaration<?> type : cu.getTypes()) {
                                if (type instanceof ClassOrInterfaceDeclaration cls) {
                                    String fqn = pkg.isBlank()
                                            ? cls.getNameAsString()
                                            : pkg + "." + cls.getNameAsString();
                                    simpleNameToFqn.putIfAbsent(cls.getNameAsString(), fqn);
                                }
                            }
                        } catch (Exception ignored) {
                            // skip unreadable files
                        }
                    });
        } catch (Exception ignored) {
            // skip unreadable roots
        }
    }

    public record SymbolResolutionContext(
            String orgId,
            String serviceId,
            String owningClassFqn,
            List<String> pinnedCatalogLibraryIds,
            JavaParser parser,
            CombinedTypeSolver typeSolver,
            Map<String, String> classpathSimpleNames
    ) {
        public Optional<String> resolveFromClasspath(String simpleName) {
            if (simpleName == null || classpathSimpleNames == null) return Optional.empty();
            return Optional.ofNullable(classpathSimpleNames.get(simpleName));
        }
    }
}
