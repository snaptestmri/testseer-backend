package io.testseer.backend.ingestion.external;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.KotlinSourceLightParser;
import io.testseer.backend.ingestion.messaging.MessagingFactOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ExternalCallSiteExtractor {

    private static final Logger log = LoggerFactory.getLogger(ExternalCallSiteExtractor.class);

    private static final Pattern CONFIG_GETTER =
            Pattern.compile("^(\\w+)\\.get([A-Z]\\w*)\\(\\)$");
    private static final Pattern HTTP_METHOD_ENUM =
            Pattern.compile("HttpMethod\\.(GET|POST|PUT|DELETE|PATCH)");

    private final JavaParser parser = new JavaParser();

    public record ConfigPropertiesBinding(String classFqn, String prefix) {}

    public record CallSiteCandidate(
            String sourceSymbol,
            String configAccessor,
            String configPrefix,
            String configProperty,
            String httpClientType,
            String httpClientMethod,
            String httpMethod
    ) {}

    public List<ConfigPropertiesBinding> extractConfigBindings(
            List<MessagingFactOrchestrator.SourceFile> javaFiles) {
        List<ConfigPropertiesBinding> bindings = new ArrayList<>();
        for (MessagingFactOrchestrator.SourceFile file : javaFiles) {
            if (skipFile(file)) continue;
            parseClass(file.path(), file.content()).ifPresent(cls -> {
                String fqn = classFqn(cls);
                cls.getAnnotations().stream()
                        .filter(a -> a.getNameAsString().equals("ConfigurationProperties"))
                        .map(this::configurationPrefix)
                        .filter(p -> p != null && !p.isBlank())
                        .forEach(prefix -> bindings.add(new ConfigPropertiesBinding(fqn, prefix)));
            });
        }
        return bindings;
    }

    public List<CallSiteCandidate> extractCallSites(List<MessagingFactOrchestrator.SourceFile> javaFiles) {
        List<CallSiteCandidate> sites = new ArrayList<>();
        for (MessagingFactOrchestrator.SourceFile file : javaFiles) {
            if (skipFile(file)) continue;
            parseClass(file.path(), file.content()).ifPresent(cls -> {
                String classFqn = classFqn(cls);
                for (MethodDeclaration method : cls.getMethods()) {
                    String methodSymbol = classFqn + "#" + method.getNameAsString();
                    method.findAll(MethodCallExpr.class).forEach(call -> {
                        if ("callWithRetry".equals(call.getNameAsString())) {
                            sites.add(buildRestServiceSite(methodSymbol, classFqn, cls));
                        }
                        if ("exchange".equals(call.getNameAsString()) && call.getArguments().size() >= 2) {
                            sites.add(buildExchangeSite(methodSymbol, call));
                        }
                        if (call.getNameAsString().startsWith("get")
                                && call.getNameAsString().endsWith("Endpoint")
                                && call.getScope().isPresent()) {
                            sites.add(buildConfigGetterSite(methodSymbol, call));
                        }
                    });
                }
            });
        }
        return sites;
    }

    public List<FactBatch.ExternalCallSiteFact> toFacts(List<CallSiteCandidate> sites, String endpointId) {
        return sites.stream()
                .map(s -> new FactBatch.ExternalCallSiteFact(
                        s.sourceSymbol(),
                        s.configAccessor(),
                        s.configPrefix(),
                        s.configProperty(),
                        s.httpClientType(),
                        s.httpClientMethod(),
                        s.httpMethod(),
                        endpointId,
                        "javaparser",
                        s.configProperty() != null ? 0.90 : 0.70
                ))
                .toList();
    }

    private CallSiteCandidate buildRestServiceSite(
            String methodSymbol, String classFqn, ClassOrInterfaceDeclaration cls) {
        String prefix = cls.getAnnotations().stream()
                .filter(a -> a.getNameAsString().equals("ConfigurationProperties"))
                .map(this::configurationPrefix)
                .filter(p -> p != null && !p.isBlank())
                .findFirst()
                .orElse(null);
        return new CallSiteCandidate(
                methodSymbol,
                null,
                prefix,
                "uri",
                "RestService",
                "callWithRetry",
                "POST"
        );
    }

    private CallSiteCandidate buildExchangeSite(String methodSymbol, MethodCallExpr call) {
        String uriArg = call.getArgument(0).toString();
        String httpMethod = extractHttpMethod(call.getArgument(1).toString());
        String clientType = call.getScope()
                .map(Expression::toString)
                .orElse("RestTemplate");
        ConfigRef configRef = resolveConfigRef(uriArg, call);
        return new CallSiteCandidate(
                methodSymbol,
                configRef.accessor(),
                configRef.prefix(),
                configRef.property(),
                clientType.contains("RestTemplate") ? "RestTemplate" : clientType,
                "exchange",
                httpMethod
        );
    }

    private CallSiteCandidate buildConfigGetterSite(String methodSymbol, MethodCallExpr call) {
        String accessor = call.toString();
        Matcher m = CONFIG_GETTER.matcher(accessor);
        String property = m.matches() ? decapitalize(m.group(2)) : null;
        return new CallSiteCandidate(
                methodSymbol,
                accessor,
                null,
                property,
                null,
                "config-getter",
                null
        );
    }

    private ConfigRef resolveConfigRef(String uriArg, MethodCallExpr exchangeCall) {
        if (uriArg.contains(".get") && uriArg.endsWith("()")) {
            Matcher m = CONFIG_GETTER.matcher(uriArg.trim());
            if (m.matches()) {
                return new ConfigRef(uriArg.trim(), null, decapitalize(m.group(2)));
            }
        }
        if ("requestURI".equals(uriArg.trim()) || "requestUri".equals(uriArg.trim())) {
            return findConfigGetterInEnclosingMethod(exchangeCall);
        }
        return new ConfigRef(null, null, null);
    }

    private ConfigRef findConfigGetterInEnclosingMethod(MethodCallExpr exchangeCall) {
        return exchangeCall.findAncestor(MethodDeclaration.class)
                .flatMap(method -> method.findAll(MethodCallExpr.class).stream()
                        .filter(c -> c.getNameAsString().startsWith("get")
                                && c.getNameAsString().endsWith("Endpoint"))
                        .findFirst()
                        .map(c -> {
                            String accessor = c.toString();
                            Matcher m = CONFIG_GETTER.matcher(accessor);
                            String property = m.matches() ? decapitalize(m.group(2)) : null;
                            return new ConfigRef(accessor, null, property);
                        }))
                .orElse(new ConfigRef(null, null, null));
    }

    private String extractHttpMethod(String arg) {
        Matcher m = HTTP_METHOD_ENUM.matcher(arg);
        if (m.find()) return m.group(1);
        return null;
    }

    private String configurationPrefix(AnnotationExpr ann) {
        if (ann.isNormalAnnotationExpr()) {
            return ann.asNormalAnnotationExpr().getPairs().stream()
                    .filter(p -> "prefix".equals(p.getNameAsString()) || "value".equals(p.getNameAsString()))
                    .map(p -> p.getValue().toString().replace("\"", ""))
                    .findFirst()
                    .orElse(null);
        }
        if (ann.isSingleMemberAnnotationExpr()) {
            return ann.asSingleMemberAnnotationExpr().getMemberValue().toString().replace("\"", "");
        }
        return null;
    }

    private static boolean skipFile(MessagingFactOrchestrator.SourceFile file) {
        return file == null
                || KotlinSourceLightParser.isKotlinPath(file.path())
                || file.content() == null
                || file.content().isBlank();
    }

    private Optional<ClassOrInterfaceDeclaration> parseClass(String path, String source) {
        try {
            ParseResult<CompilationUnit> result = parser.parse(source);
            if (!result.isSuccessful() || result.getResult().isEmpty()) return Optional.empty();
            CompilationUnit cu = result.getResult().get();
            return cu.getPrimaryType()
                    .filter(t -> t instanceof ClassOrInterfaceDeclaration)
                    .map(t -> (ClassOrInterfaceDeclaration) t)
                    .or(() -> cu.getTypes().stream()
                            .filter(t -> t instanceof ClassOrInterfaceDeclaration)
                            .map(t -> (ClassOrInterfaceDeclaration) t)
                            .findFirst());
        } catch (AssertionError | RuntimeException ex) {
            log.warn("Skipping external call-site parse for {}: {}", path, ex.getMessage());
            return Optional.empty();
        }
    }

    private String classFqn(ClassOrInterfaceDeclaration cls) {
        return cls.findCompilationUnit()
                .flatMap(cu -> cu.getPackageDeclaration()
                        .map(p -> p.getNameAsString() + "." + cls.getNameAsString()))
                .orElse(cls.getNameAsString());
    }

    private static String decapitalize(String name) {
        if (name == null || name.isEmpty()) return name;
        return name.substring(0, 1).toLowerCase(Locale.ROOT) + name.substring(1);
    }

    private record ConfigRef(String accessor, String prefix, String property) {}
}
