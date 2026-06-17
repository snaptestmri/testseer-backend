package io.testseer.backend.ingestion.catalog;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import io.testseer.backend.ingestion.ParsedModel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Extracts REST mapping endpoints with compile-time constant path resolution (UP-GAP-01 / TRG-18-R).
 */
public final class ResolvedEndpointExtractor {

    private static final List<String> SUPPORTED_HTTP_METHODS = List.of(
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    private ResolvedEndpointExtractor() {}

    public static List<ParsedModel.EndpointDef> extract(
            ClassOrInterfaceDeclaration cls,
            StringConstantIndex constants,
            StaticImportIndex staticImports,
            ImportIndex typeImports) {

        List<ParsedModel.EndpointDef> result = new ArrayList<>();
        String classMapping = resolveClassMapping(cls, constants, staticImports, typeImports);

        for (MethodDeclaration method : cls.getMethods()) {
            for (AnnotationExpr ann : method.getAnnotations()) {
                List<String> httpMethods = httpMethodsForMappingAnnotation(ann);
                if (httpMethods.isEmpty()) {
                    continue;
                }
                ResolvedPathParts methodPath = resolveMappingPath(ann, constants, staticImports, typeImports);
                String requestParams = extractMappingParams(ann);
                String combined = joinPaths(classMapping, methodPath.path());
                for (String httpMethod : httpMethods) {
                    result.add(new ParsedModel.EndpointDef(
                            httpMethod,
                            combined,
                            method.getNameAsString(),
                            requestParams,
                            methodPath.fieldFqn(),
                            methodPath.resolutionKind().name()));
                }
            }
        }
        return result;
    }

    private record ResolvedPathParts(String path, String fieldFqn, AnnotationPathResolver.ResolutionKind resolutionKind) {}

    private static String resolveClassMapping(
            ClassOrInterfaceDeclaration cls,
            StringConstantIndex constants,
            StaticImportIndex staticImports,
            ImportIndex typeImports) {

        Optional<AnnotationExpr> classMappingAnn = cls.getAnnotations().stream()
                .filter(a -> "RequestMapping".equals(a.getNameAsString()))
                .findFirst();
        if (classMappingAnn.isEmpty()) {
            return "";
        }
        return resolveMappingPath(classMappingAnn.get(), constants, staticImports, typeImports).path();
    }

    private static ResolvedPathParts resolveMappingPath(
            AnnotationExpr ann,
            StringConstantIndex constants,
            StaticImportIndex staticImports,
            ImportIndex typeImports) {

        Optional<Expression> pathExpr = mappingPathExpression(ann);
        if (pathExpr.isEmpty()) {
            return new ResolvedPathParts("", null, AnnotationPathResolver.ResolutionKind.LITERAL);
        }
        AnnotationPathResolver.ResolvedPath resolved =
                AnnotationPathResolver.resolve(pathExpr.get(), constants, staticImports, typeImports);
        return new ResolvedPathParts(
                normalizePath(resolved.path()),
                resolved.fieldFqn(),
                resolved.kind());
    }

    private static Optional<Expression> mappingPathExpression(AnnotationExpr ann) {
        if (ann.isSingleMemberAnnotationExpr()) {
            return Optional.of(ann.asSingleMemberAnnotationExpr().getMemberValue());
        }
        if (ann.isNormalAnnotationExpr()) {
            return ann.asNormalAnnotationExpr().getPairs().stream()
                    .filter(p -> "value".equals(p.getNameAsString()) || "path".equals(p.getNameAsString()))
                    .map(p -> p.getValue())
                    .findFirst();
        }
        return Optional.empty();
    }

    private static String joinPaths(String classPrefix, String methodPath) {
        if (classPrefix == null || classPrefix.isBlank()) {
            return methodPath == null || methodPath.isBlank() ? "/" : methodPath;
        }
        if (methodPath == null || methodPath.isBlank()) {
            return classPrefix;
        }
        String left = classPrefix.endsWith("/") ? classPrefix.substring(0, classPrefix.length() - 1) : classPrefix;
        String right = methodPath.startsWith("/") ? methodPath : "/" + methodPath;
        return left + right;
    }

    public static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private static List<String> httpMethodsForMappingAnnotation(AnnotationExpr ann) {
        return switch (ann.getNameAsString()) {
            case "GetMapping" -> List.of("GET");
            case "PostMapping" -> List.of("POST");
            case "PutMapping" -> List.of("PUT");
            case "DeleteMapping" -> List.of("DELETE");
            case "PatchMapping" -> List.of("PATCH");
            case "RequestMapping" -> extractHttpMethodsFromRequestMapping(ann);
            default -> List.of();
        };
    }

    private static List<String> extractHttpMethodsFromRequestMapping(AnnotationExpr ann) {
        if (ann.isNormalAnnotationExpr()) {
            Optional<String> methodExpr = ann.asNormalAnnotationExpr().getPairs().stream()
                    .filter(p -> "method".equals(p.getNameAsString()))
                    .map(p -> p.getValue().toString())
                    .findFirst();
            if (methodExpr.isPresent()) {
                List<String> parsed = parseHttpMethodExpression(methodExpr.get());
                if (!parsed.isEmpty()) {
                    return parsed;
                }
            }
        }
        return List.copyOf(SUPPORTED_HTTP_METHODS);
    }

    private static List<String> parseHttpMethodExpression(String expr) {
        Set<String> found = new LinkedHashSet<>();
        String upper = expr.toUpperCase();
        for (String method : SUPPORTED_HTTP_METHODS) {
            if (upper.contains("METHOD." + method) || upper.contains("." + method)) {
                found.add(method);
            }
        }
        if (found.isEmpty()) {
            for (String method : SUPPORTED_HTTP_METHODS) {
                if (upper.matches("(?i).*\\b" + method + "\\b.*")) {
                    found.add(method);
                }
            }
        }
        return List.copyOf(found);
    }

    private static String extractMappingParams(AnnotationExpr ann) {
        if (!ann.isNormalAnnotationExpr()) {
            return null;
        }
        return ann.asNormalAnnotationExpr().getPairs().stream()
                .filter(p -> "params".equals(p.getNameAsString()))
                .map(p -> p.getValue().toString().replace("\"", "").trim())
                .filter(v -> !v.isBlank())
                .findFirst()
                .orElse(null);
    }
}
