package io.testseer.backend.ingestion.graph;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import io.testseer.backend.ingestion.ParsedModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Detects Spring factory routing tables built in {@code @PostConstruct} methods.
 */
public final class FactoryRoutingExtractor {

    private FactoryRoutingExtractor() {}

    public static List<ParsedModel.FactoryRoutingDef> extract(
            ClassOrInterfaceDeclaration cls,
            String classFqn,
            List<ParsedModel.FieldInjectionDef> fieldInjections,
            BeanNameResolver beanResolver) {

        Map<String, ParsedModel.FieldInjectionDef> varToField = new HashMap<>();
        for (ParsedModel.FieldInjectionDef inj : fieldInjections) {
            varToField.put(inj.variableName(), inj);
        }

        String selectorMethod = findSelectorMethod(cls);
        String discriminatorType = inferDiscriminatorType(cls);

        List<ParsedModel.FactoryRoutingDef> routes = new ArrayList<>();
        for (MethodDeclaration method : cls.getMethods()) {
            boolean postConstruct = method.getAnnotations().stream()
                    .anyMatch(a -> a.getNameAsString().equals("PostConstruct"));
            if (!postConstruct) {
                continue;
            }
            for (MethodCallExpr putCall : method.findAll(MethodCallExpr.class)) {
                if (!"put".equals(putCall.getNameAsString()) || putCall.getArguments().size() < 2) {
                    continue;
                }
                String routingKey = extractRoutingKey(putCall.getArgument(0));
                String targetVar = putCall.getArgument(1).toString().trim();
                if (routingKey == null || targetVar.isBlank()) {
                    continue;
                }

                ParsedModel.FieldInjectionDef targetField = varToField.get(targetVar);
                String targetBean = targetField != null ? targetField.beanName() : null;
                String targetFqn = resolveTargetFqn(targetField, targetBean, beanResolver);
                if (targetFqn == null || targetFqn.isBlank()) {
                    continue;
                }

                routes.add(new ParsedModel.FactoryRoutingDef(
                        selectorMethod,
                        discriminatorType,
                        routingKey,
                        targetBean,
                        targetFqn,
                        false));
            }
        }

        appendFallbackRoute(cls, selectorMethod, discriminatorType, varToField, beanResolver, routes);
        return routes;
    }

    private static void appendFallbackRoute(
            ClassOrInterfaceDeclaration cls,
            String selectorMethod,
            String discriminatorType,
            Map<String, ParsedModel.FieldInjectionDef> varToField,
            BeanNameResolver beanResolver,
            List<ParsedModel.FactoryRoutingDef> routes) {

        for (MethodDeclaration method : cls.getMethods()) {
            if (!method.isPublic()) {
                continue;
            }
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                if (!"orElse".equals(call.getNameAsString()) || call.getArguments().isEmpty()) {
                    continue;
                }
                String fallbackVar = call.getArgument(0).toString().trim();
                ParsedModel.FieldInjectionDef targetField = varToField.get(fallbackVar);
                String targetBean = targetField != null ? targetField.beanName() : null;
                String targetFqn = resolveTargetFqn(targetField, targetBean, beanResolver);
                if (targetFqn == null || targetFqn.isBlank()) {
                    continue;
                }
                boolean exists = routes.stream().anyMatch(r -> r.fallback());
                if (!exists) {
                    routes.add(new ParsedModel.FactoryRoutingDef(
                            selectorMethod,
                            discriminatorType,
                            "*",
                            targetBean,
                            targetFqn,
                            true));
                }
            }
        }
    }

    private static String findSelectorMethod(ClassOrInterfaceDeclaration cls) {
        return cls.getMethods().stream()
                .filter(MethodDeclaration::isPublic)
                .filter(m -> m.findAll(MethodCallExpr.class).stream()
                        .anyMatch(c -> "get".equals(c.getNameAsString())
                                && c.getScope().isPresent()
                                && c.getScope().get().toString().contains("Map")))
                .map(MethodDeclaration::getNameAsString)
                .findFirst()
                .orElse(null);
    }

    private static String inferDiscriminatorType(ClassOrInterfaceDeclaration cls) {
        return cls.getFields().stream()
                .map(f -> f.getElementType().asString())
                .filter(t -> t.contains("Map<"))
                .map(FactoryRoutingExtractor::mapKeyType)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst()
                .orElse(null);
    }

    private static Optional<String> mapKeyType(String mapType) {
        int start = mapType.indexOf('<');
        int comma = mapType.indexOf(',', start);
        if (start < 0 || comma < 0) {
            return Optional.empty();
        }
        return Optional.of(mapType.substring(start + 1, comma).trim());
    }

    private static String extractRoutingKey(Expression expr) {
        if (expr instanceof FieldAccessExpr field) {
            return field.getNameAsString();
        }
        if (expr instanceof NameExpr name) {
            return name.getNameAsString();
        }
        return expr.toString().replace("\"", "").trim();
    }

    private static String resolveTargetFqn(
            ParsedModel.FieldInjectionDef targetField,
            String targetBean,
            BeanNameResolver beanResolver) {
        if (targetBean != null && !targetBean.isBlank()) {
            String fromBean = beanResolver.resolveBeanName(targetBean);
            if (fromBean != null && !fromBean.isBlank()) {
                return fromBean;
            }
        }
        if (targetField != null) {
            return beanResolver.resolveType(targetField.declaredType());
        }
        return null;
    }

    public static String extractComponentBeanName(ClassOrInterfaceDeclaration cls) {
        return cls.getAnnotations().stream()
                .filter(a -> a.getNameAsString().equals("Component")
                        || a.getNameAsString().equals("Service"))
                .map(MethodCallGraphExtractor::annotationMember)
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse(null);
    }

    public interface BeanNameResolver {
        String resolveBeanName(String beanName);

        String resolveType(String typeName);
    }
}
