package io.testseer.backend.ingestion;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import io.testseer.backend.ingestion.catalog.ImportIndex;
import io.testseer.backend.ingestion.catalog.TypeFqnResolver;
import io.testseer.backend.ingestion.graph.FactoryRoutingExtractor;
import io.testseer.backend.ingestion.graph.MethodCallGraphExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class JavaParserService {

    private static final Logger log = LoggerFactory.getLogger(JavaParserService.class);

    private static final Map<String, String> HTTP_VERBS = Map.of(
            "get", "GET", "post", "POST", "put", "PUT",
            "delete", "DELETE", "patch", "PATCH"
    );

    /** Verbs recognized on @*Mapping / RequestMethod / HttpMethod annotations. */
    private static final List<String> SUPPORTED_HTTP_METHODS = List.of(
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    private static final Map<String, String> TEMPLATE_METHODS = Map.of(
            "getForEntity",   "GET",
            "getForObject",   "GET",
            "postForEntity",  "POST",
            "postForObject",  "POST",
            "delete",         "DELETE",
            "patchForEntity", "PATCH"
    );

    private static final List<String> CLIENTS = List.of(
            "RestClient", "WebClient", "RestTemplate", "FeignClient"
    );

    private final JavaParser parser;
    private final TypeFqnResolver typeFqnResolver;

    public JavaParserService(TypeFqnResolver typeFqnResolver) {
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        this.parser = new JavaParser(config);
        this.typeFqnResolver = typeFqnResolver;
    }

    /** Test / legacy no-arg — FQN resolution degrades to simple names. */
    public JavaParserService() {
        this(null);
    }

    public ParsedModel parse(String filePath, String sourceContent) {
        if (sourceContent == null || sourceContent.isBlank()) {
            String detail = sourceContent == null ? "null source content" : "empty source content";
            log.warn("Skipping parse for {}: {}", filePath, detail);
            return parseErrorModel(filePath, detail);
        }

        ParseResult<CompilationUnit> result;
        try {
            result = parser.parse(sourceContent);
        } catch (AssertionError | RuntimeException ex) {
            String detail = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            log.warn("JavaParser failed for {}: {}", filePath, detail);
            return parseErrorModel(filePath, detail);
        }

        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            String detail = result.getProblems().isEmpty() ? "unknown parse error"
                    : result.getProblems().get(0).getMessage();
            return parseErrorModel(filePath, detail);
        }

        CompilationUnit cu = result.getResult().get();
        // getPrimaryType() requires a file-backed parse to match on filename;
        // when parsing from a raw string it returns empty, so fall back to the
        // first top-level class declared in the compilation unit.
        Optional<ClassOrInterfaceDeclaration> primaryClass = cu.getPrimaryType()
                .filter(t -> t instanceof ClassOrInterfaceDeclaration)
                .map(t -> (ClassOrInterfaceDeclaration) t);
        if (primaryClass.isEmpty()) {
            primaryClass = cu.getTypes().stream()
                    .filter(t -> t instanceof ClassOrInterfaceDeclaration)
                    .map(t -> (ClassOrInterfaceDeclaration) t)
                    .findFirst();
        }

        // Enum detection — handled separately from class/interface
        if (primaryClass.isEmpty()) {
            Optional<EnumDeclaration> enumDecl = cu.getTypes().stream()
                    .filter(t -> t instanceof EnumDeclaration)
                    .map(t -> (EnumDeclaration) t)
                    .findFirst();
            if (enumDecl.isPresent()) {
                EnumDeclaration en = enumDecl.get();
                String enumFqn = cu.getPackageDeclaration()
                        .map(p -> p.getNameAsString() + "." + en.getNameAsString())
                        .orElse(en.getNameAsString());
                List<String> enumAnnotations = en.getAnnotations().stream()
                        .map(AnnotationExpr::getNameAsString).toList();
                List<String> enumValues = en.getEntries().stream()
                        .map(e -> e.getNameAsString()).toList();
                return ParsedModel.of(
                        filePath, enumFqn, enumAnnotations, List.of(), List.of(),
                        List.of(), List.of(), false, null,
                        extractJavadoc(en), List.of(), enumValues
                );
            }
            // Not a class, interface, or enum
            return ParsedModel.of(filePath, null, List.of(), List.of(), List.of(),
                    List.of(), List.of(), false, null,
                    null, List.of(), List.of());
        }

        ClassOrInterfaceDeclaration cls = primaryClass.get();
        String classFqn = cu.getPackageDeclaration()
                .map(p -> p.getNameAsString() + "." + cls.getNameAsString())
                .orElse(cls.getNameAsString());

        List<String> annotations = cls.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .toList();

        List<String> constructorParams = cls.getConstructors().stream()
                .findFirst()
                .map(c -> c.getParameters().stream()
                        .map(p -> p.getType().asString())
                        .toList())
                .orElse(List.of());

        List<ParsedModel.FieldInjectionDef> fieldInjectionDefs =
                MethodCallGraphExtractor.extractFieldInjections(cls);
        Set<String> depTypes = new LinkedHashSet<>(constructorParams);
        fieldInjectionDefs.forEach(f -> depTypes.add(f.declaredType()));
        List<String> fieldInjections = List.copyOf(depTypes);

        List<ParsedModel.EndpointDef> endpoints = extractEndpoints(cls);
        List<ParsedModel.OutboundCallDef> outboundCalls = extractOutboundCalls(cls);

        // FeignClient interfaces declare outbound calls via their own mapping annotations.
        // Reuse extractEndpoints() — the annotation structure is identical to controllers.
        if (annotations.contains("FeignClient")) {
            List<ParsedModel.OutboundCallDef> feignCalls = endpoints.stream()
                    .map(ep -> new ParsedModel.OutboundCallDef(
                            "FeignClient", ep.httpMethod(), ep.path(),
                            classFqn + "#" + ep.methodName()
                    ))
                    .toList();
            List<ParsedModel.OutboundCallDef> merged = new ArrayList<>(outboundCalls);
            merged.addAll(feignCalls);
            outboundCalls = merged;
        }

        String classJavadoc = extractJavadoc(cls);
        List<ParsedModel.MethodDef> publicMethods = extractPublicMethods(cls);

        ImportIndex imports = ImportIndex.build(sourceContent);
        var typeCtx = new TypeFqnResolver.CompilationContext(null, null, classFqn);
        MethodCallGraphExtractor.FieldTypeResolver typeResolver =
                typeName -> typeFqnResolver != null
                        ? typeFqnResolver.resolve(typeName, imports, typeCtx).fqn()
                        : typeName;

        List<ParsedModel.MethodCallDef> methodCalls =
                MethodCallGraphExtractor.extract(cls, classFqn, fieldInjectionDefs, typeResolver);

        FactoryRoutingExtractor.BeanNameResolver beanResolver = new FactoryRoutingExtractor.BeanNameResolver() {
            @Override
            public String resolveBeanName(String beanName) {
                return null;
            }

            @Override
            public String resolveType(String typeName) {
                return typeResolver.resolve(typeName);
            }
        };
        List<ParsedModel.FactoryRoutingDef> factoryRouting =
                FactoryRoutingExtractor.extract(cls, classFqn, fieldInjectionDefs, beanResolver);
        String componentBeanName = FactoryRoutingExtractor.extractComponentBeanName(cls);

        List<String> implementedInterfaces = cls.getImplementedTypes().stream()
                .map(t -> typeResolver.resolve(t.getNameAsString()))
                .filter(fqn -> fqn != null && !fqn.isBlank())
                .distinct()
                .toList();

        return new ParsedModel(
                filePath, classFqn, annotations, constructorParams,
                fieldInjections, endpoints, outboundCalls, false, null,
                classJavadoc, publicMethods, List.of(),
                fieldInjectionDefs, methodCalls, factoryRouting, componentBeanName,
                implementedInterfaces
        );
    }

    private static boolean isGetterOrSetter(MethodDeclaration method) {
        String name = method.getNameAsString();
        return (name.startsWith("get") || name.startsWith("set") || name.startsWith("is"))
                && method.getParameters().size() <= 1;
    }

    private static String extractJavadoc(
            com.github.javaparser.ast.nodeTypes.NodeWithJavadoc<?> node) {
        return node.getJavadocComment()
                .map(jd -> jd.parse().getDescription().toText().trim())
                .filter(s -> !s.isBlank())
                .orElse(null);
    }

    private static List<ParsedModel.MethodDef> extractPublicMethods(
            ClassOrInterfaceDeclaration cls) {
        return cls.getMethods().stream()
                .filter(MethodDeclaration::isPublic)
                .filter(m -> !isGetterOrSetter(m))
                .map(m -> new ParsedModel.MethodDef(
                        m.getNameAsString(),
                        extractJavadoc(m),
                        m.getType().asString(),
                        m.getParameters().stream()
                                .map(p -> p.getType().asString())
                                .toList(),
                        m.getThrownExceptions().stream()
                                .map(e -> e.asString())
                                .toList()
                ))
                .toList();
    }

    private List<ParsedModel.EndpointDef> extractEndpoints(ClassOrInterfaceDeclaration cls) {
        List<ParsedModel.EndpointDef> result = new ArrayList<>();
        String classMapping = cls.getAnnotations().stream()
                .filter(a -> a.getNameAsString().equals("RequestMapping"))
                .map(a -> annotationValue(a, ""))
                .findFirst().orElse("");

        for (MethodDeclaration method : cls.getMethods()) {
            for (AnnotationExpr ann : method.getAnnotations()) {
                List<String> httpMethods = httpMethodsForMappingAnnotation(ann);
                if (httpMethods.isEmpty()) {
                    continue;
                }
                String methodPath = annotationValue(ann, "");
                String requestParams = extractMappingParams(ann);
                for (String httpMethod : httpMethods) {
                    result.add(new ParsedModel.EndpointDef(
                            httpMethod, classMapping + methodPath, method.getNameAsString(), requestParams));
                }
            }
        }
        return result;
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

    /**
     * Reads {@code method = RequestMethod.POST} (or array / HttpMethod variants).
     * When {@code method} is omitted, Spring matches all verbs — emit all supported methods.
     */
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

    private List<ParsedModel.OutboundCallDef> extractOutboundCalls(
            ClassOrInterfaceDeclaration cls) {
        List<ParsedModel.OutboundCallDef> result = new ArrayList<>();

        if (extendsRestService(cls)) {
            String configPrefix = configurationPropertiesPrefix(cls);
            boolean hasOutboundCall = cls.getMethods().stream()
                    .flatMap(m -> m.findAll(MethodCallExpr.class).stream())
                    .anyMatch(c -> "callWithRetry".equals(c.getNameAsString()));
            if (hasOutboundCall || configPrefix != null) {
                result.add(new ParsedModel.OutboundCallDef(
                        "RestService",
                        "POST",
                        configPrefix,
                        cls.getNameAsString()
                ));
            }
        }

        // Method-body traversal: RestClient / WebClient
        // Pattern: <scope>.get().uri("path"), <scope>.post().uri("path"), etc.
        cls.getMethods().forEach(method -> {
            method.findAll(MethodCallExpr.class).forEach(call -> {

                // RestClient / WebClient: look for .uri(...) chained on .get()/.post()/etc.
                if ("uri".equals(call.getNameAsString())
                        && call.getScope().isPresent()
                        && !call.getArguments().isEmpty()) {
                    Expression scope = call.getScope().get();
                    if (scope instanceof MethodCallExpr verbCall) {
                        String httpMethod = HTTP_VERBS.get(verbCall.getNameAsString());
                        if (httpMethod != null) {
                            String rawArg = call.getArgument(0).toString().replace("\"", "");
                            result.add(new ParsedModel.OutboundCallDef(
                                    "RestClient", httpMethod, rawArg,
                                    cls.getNameAsString() + "#" + method.getNameAsString()
                            ));
                        }
                    }
                }

                // RestTemplate: getForEntity("/path", ...), postForEntity("/path", ...), etc.
                String tmVerb = TEMPLATE_METHODS.get(call.getNameAsString());
                if (tmVerb != null && !call.getArguments().isEmpty()) {
                    String rawArg = call.getArgument(0).toString().replace("\"", "");
                    result.add(new ParsedModel.OutboundCallDef(
                            "RestTemplate", tmVerb, rawArg,
                            cls.getNameAsString() + "#" + method.getNameAsString()
                    ));
                }

                // RestTemplate/WebClient: exchange(uri, HttpMethod.POST, ...)
                if ("exchange".equals(call.getNameAsString()) && call.getArguments().size() >= 2) {
                    String uriArg = call.getArgument(0).toString().replace("\"", "");
                    String httpMethod = extractHttpMethodFromExpr(call.getArgument(1).toString());
                    String clientType = call.getScope()
                            .map(s -> s.toString().contains("WebClient") ? "WebClient" : "RestTemplate")
                            .orElse("RestTemplate");
                    result.add(new ParsedModel.OutboundCallDef(
                            clientType,
                            httpMethod,
                            uriArg.startsWith("\"") ? uriArg : null,
                            cls.getNameAsString() + "#" + method.getNameAsString()
                    ));
                }

                if ("createWorkbenchSubmission".equals(call.getNameAsString())) {
                    String clientType = call.getScope().map(Expression::toString).orElse("WorkbenchSubmissionRestClient");
                    result.add(new ParsedModel.OutboundCallDef(
                            clientType,
                            "POST",
                            "/workbench/submission",
                            cls.getNameAsString() + "#" + method.getNameAsString()
                    ));
                }
            });
        });

        // Field-level fallback: always emit presence signal for each HTTP client field.
        // Captures cases where the URI is dynamic (variable, not a string literal).
        cls.getFields().forEach(field -> {
            String type = field.getElementType().asString();
            if (CLIENTS.stream().anyMatch(type::contains)) {
                result.add(new ParsedModel.OutboundCallDef(
                        type, null, null, cls.getNameAsString()
                ));
            }
        });

        return result;
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

    private static String annotationValue(AnnotationExpr ann, String defaultValue) {
        if (ann.isSingleMemberAnnotationExpr()) {
            return ann.asSingleMemberAnnotationExpr().getMemberValue().toString()
                    .replace("\"", "");
        }
        if (ann.isNormalAnnotationExpr()) {
            return ann.asNormalAnnotationExpr().getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("value")
                              || p.getNameAsString().equals("path"))
                    .map(p -> p.getValue().toString().replace("\"", ""))
                    .findFirst().orElse(defaultValue);
        }
        return defaultValue;
    }

    private static String extractHttpMethodFromExpr(String expr) {
        if (expr == null) return null;
        if (expr.contains("HttpMethod.POST") || expr.contains(".POST")) return "POST";
        if (expr.contains("HttpMethod.PUT") || expr.contains(".PUT")) return "PUT";
        if (expr.contains("HttpMethod.GET") || expr.contains(".GET")) return "GET";
        if (expr.contains("HttpMethod.DELETE") || expr.contains(".DELETE")) return "DELETE";
        if (expr.contains("HttpMethod.PATCH") || expr.contains(".PATCH")) return "PATCH";
        return null;
    }

    private static boolean extendsRestService(ClassOrInterfaceDeclaration cls) {
        return cls.getExtendedTypes().stream()
                .anyMatch(t -> t.getNameAsString().equals("RestService")
                        || t.toString().contains("RestService"));
    }

    private static String configurationPropertiesPrefix(ClassOrInterfaceDeclaration cls) {
        return cls.getAnnotations().stream()
                .filter(a -> a.getNameAsString().equals("ConfigurationProperties"))
                .map(a -> annotationValue(a, null))
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse(null);
    }

    private static ParsedModel parseErrorModel(String filePath, String detail) {
        return ParsedModel.of(
                filePath, null, List.of(), List.of(), List.of(),
                List.of(), List.of(), true, detail,
                null, List.of(), List.of()
        );
    }
}
