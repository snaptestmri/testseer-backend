package io.testseer.backend.ingestion.catalog;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import io.testseer.backend.ingestion.ParsedModel;
import io.testseer.backend.ingestion.messaging.MessagingFactOrchestrator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Re-resolves {@link ParsedModel.EndpointDef} paths using {@link StringConstantIndex} (BL-062 / UP-GAP-01).
 */
@Component
public class EndpointPathEnricher {

    private final JavaParser parser = new JavaParser();

    public List<MessagingFactOrchestrator.SourceFile> enrich(List<MessagingFactOrchestrator.SourceFile> sources) {
        if (sources == null || sources.isEmpty()) {
            return sources == null ? List.of() : sources;
        }
        Map<String, String> pathToSource = new LinkedHashMap<>();
        for (MessagingFactOrchestrator.SourceFile src : sources) {
            if (src.content() != null) {
                pathToSource.put(src.path(), src.content());
            }
        }
        StringConstantIndex constants = StringConstantIndex.build(pathToSource);

        List<MessagingFactOrchestrator.SourceFile> enriched = new ArrayList<>(sources.size());
        for (MessagingFactOrchestrator.SourceFile src : sources) {
            enriched.add(enrichOne(src, constants));
        }
        return enriched;
    }

    private MessagingFactOrchestrator.SourceFile enrichOne(
            MessagingFactOrchestrator.SourceFile src, StringConstantIndex constants) {

        ParsedModel model = src.parsedModel();
        if (model == null || model.parseError() || model.endpoints().isEmpty()
                || src.content() == null || src.content().isBlank()) {
            return src;
        }

        ParseResult<CompilationUnit> result = parser.parse(src.content());
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            return src;
        }
        CompilationUnit cu = result.getResult().get();
        Optional<ClassOrInterfaceDeclaration> primary = primaryClass(cu);
        if (primary.isEmpty()) {
            return src;
        }
        ClassOrInterfaceDeclaration cls = primary.get();
        String classFqn = cu.getPackageDeclaration()
                .map(p -> p.getNameAsString() + "." + cls.getNameAsString())
                .orElse(cls.getNameAsString());
        if (!classFqn.equals(model.classFqn())) {
            return src;
        }

        ImportIndex typeImports = ImportIndex.build(src.content());
        StaticImportIndex staticImports = StaticImportIndex.build(src.content());
        List<ParsedModel.EndpointDef> resolvedEndpoints = ResolvedEndpointExtractor.extract(
                cls, constants, staticImports, typeImports);
        if (resolvedEndpoints.isEmpty()) {
            return src;
        }

        ParsedModel updated = new ParsedModel(
                model.filePath(),
                model.classFqn(),
                model.annotations(),
                model.constructorParamTypes(),
                model.fieldInjectionTypes(),
                resolvedEndpoints,
                model.outboundCalls(),
                model.parseError(),
                model.parseErrorDetail(),
                model.classJavadoc(),
                model.publicMethods(),
                model.enumValues(),
                model.fieldInjections(),
                model.methodCalls(),
                model.factoryRouting(),
                model.componentBeanName(),
                model.implementedInterfaces());

        return new MessagingFactOrchestrator.SourceFile(src.path(), src.content(), updated);
    }

    private static Optional<ClassOrInterfaceDeclaration> primaryClass(CompilationUnit cu) {
        Optional<ClassOrInterfaceDeclaration> primary = cu.getPrimaryType()
                .filter(t -> t instanceof ClassOrInterfaceDeclaration)
                .map(t -> (ClassOrInterfaceDeclaration) t);
        if (primary.isPresent()) {
            return primary;
        }
        return cu.getTypes().stream()
                .filter(t -> t instanceof ClassOrInterfaceDeclaration)
                .map(t -> (ClassOrInterfaceDeclaration) t)
                .findFirst();
    }
}
