package io.testseer.backend.ingestion.graph;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import io.testseer.backend.ingestion.ParsedModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts bounded method-call edges from public handler methods.
 */
public final class MethodCallGraphExtractor {

    private static final int MAX_CALLS_PER_METHOD = 200;
    private static final Set<String> SKIP_SCOPES = Set.of(
            "log", "LOG", "logger", "LOGGER", "Optional", "String", "Objects",
            "CollectionUtils", "StringUtils", "ObjectUtils", "Collections", "Arrays");

    private MethodCallGraphExtractor() {}

    public static List<ParsedModel.MethodCallDef> extract(
            ClassOrInterfaceDeclaration cls,
            String classFqn,
            List<ParsedModel.FieldInjectionDef> fieldInjections,
            FieldTypeResolver typeResolver) {

        Map<String, String> varToType = new HashMap<>();
        for (ParsedModel.FieldInjectionDef inj : fieldInjections) {
            varToType.put(inj.variableName(), inj.declaredType());
        }

        List<ParsedModel.MethodCallDef> result = new ArrayList<>();
        for (MethodDeclaration method : cls.getMethods()) {
            if (!method.isPublic() || isGetterOrSetter(method)) {
                continue;
            }
            int count = 0;
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                if (count >= MAX_CALLS_PER_METHOD) {
                    break;
                }
                if (shouldSkip(call)) {
                    continue;
                }
                ParsedModel.MethodCallDef edge = resolveCall(
                        method.getNameAsString(), call, classFqn, varToType, typeResolver);
                if (edge != null) {
                    result.add(edge);
                    count++;
                }
            }
        }
        return result;
    }

    private static boolean shouldSkip(MethodCallExpr call) {
        if (call.getScope().isEmpty()) {
            return false;
        }
        String scope = call.getScope().get().toString();
        return SKIP_SCOPES.stream().anyMatch(scope::startsWith);
    }

    private static ParsedModel.MethodCallDef resolveCall(
            String callerMethod,
            MethodCallExpr call,
            String owningClassFqn,
            Map<String, String> varToType,
            FieldTypeResolver typeResolver) {

        String calleeMethod = call.getNameAsString();
        String calleeVariable = null;
        String calleeType = null;

        if (call.getScope().isPresent()) {
            Expression scope = call.getScope().get();
            if (scope instanceof NameExpr name) {
                calleeVariable = name.getNameAsString();
                calleeType = varToType.get(calleeVariable);
            } else if (scope instanceof FieldAccessExpr fieldAccess) {
                calleeVariable = fieldAccess.getNameAsString();
                if (fieldAccess.getScope().toString().equals("this")) {
                    calleeType = owningClassFqn;
                } else {
                    calleeType = varToType.get(fieldAccess.getScope().toString());
                }
            } else {
                // Chained call or complex expression — callee type is not reliably inferable.
                return null;
            }
        } else {
            calleeType = owningClassFqn;
        }

        if (calleeType == null || calleeType.isBlank()) {
            return null;
        }

        String calleeFqn = typeResolver.resolve(calleeType);
        if (calleeFqn.isBlank()) {
            return null;
        }

        return new ParsedModel.MethodCallDef(callerMethod, calleeFqn, calleeMethod, calleeVariable);
    }

    private static boolean isGetterOrSetter(MethodDeclaration method) {
        String name = method.getNameAsString();
        return (name.startsWith("get") || name.startsWith("set") || name.startsWith("is"))
                && method.getParameters().size() <= 1;
    }

    @FunctionalInterface
    public interface FieldTypeResolver {
        String resolve(String typeName);
    }

    /** Build field-variable → declared-type map for a class. */
    public static List<ParsedModel.FieldInjectionDef> extractFieldInjections(
            ClassOrInterfaceDeclaration cls) {
        List<ParsedModel.FieldInjectionDef> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (FieldDeclaration field : cls.getFields()) {
            String injectionAnn = field.getAnnotations().stream()
                    .map(AnnotationExpr::getNameAsString)
                    .filter(a -> Set.of("Autowired", "Resource", "Inject", "Qualifier").contains(a))
                    .findFirst()
                    .orElse(null);
            String declaredType = field.getElementType().asString();
            boolean producerField = isKafkaProducerType(declaredType);
            if (injectionAnn == null && !producerField) {
                continue;
            }
            String beanName = field.getAnnotations().stream()
                    .filter(a -> a.getNameAsString().equals("Resource"))
                    .findFirst()
                    .map(MethodCallGraphExtractor::annotationMember)
                    .orElse(null);
            if (beanName == null || beanName.isBlank()) {
                beanName = field.getAnnotations().stream()
                        .filter(a -> a.getNameAsString().equals("Qualifier"))
                        .findFirst()
                        .map(MethodCallGraphExtractor::annotationMember)
                        .orElse(null);
            }

            for (VariableDeclarator var : field.getVariables()) {
                String key = var.getNameAsString() + "|" + declaredType;
                if (seen.add(key)) {
                    result.add(new ParsedModel.FieldInjectionDef(
                            var.getNameAsString(),
                            declaredType,
                            beanName,
                            injectionAnn != null ? injectionAnn : "FIELD"));
                }
            }
        }

        for (ConstructorDeclaration ctor : cls.getConstructors()) {
            for (Parameter param : ctor.getParameters()) {
                String declaredType = param.getType().asString();
                String beanName = param.getAnnotations().stream()
                        .filter(a -> a.getNameAsString().equals("Qualifier"))
                        .findFirst()
                        .map(MethodCallGraphExtractor::annotationMember)
                        .orElse(null);
                String key = param.getNameAsString() + "|" + declaredType;
                if (seen.add(key)) {
                    result.add(new ParsedModel.FieldInjectionDef(
                            param.getNameAsString(), declaredType, beanName, "CONSTRUCTOR"));
                }
            }
        }

        boolean allArgsConstructor = hasClassAnnotation(cls, "AllArgsConstructor");
        boolean requiredArgsConstructor = hasClassAnnotation(cls, "RequiredArgsConstructor");
        if (allArgsConstructor || requiredArgsConstructor) {
            for (FieldDeclaration field : cls.getFields()) {
                if (field.isStatic()) {
                    continue;
                }
                String declaredType = field.getElementType().asString();
                for (VariableDeclarator var : field.getVariables()) {
                    if (requiredArgsConstructor && !allArgsConstructor && !field.isFinal()) {
                        continue;
                    }
                    String key = var.getNameAsString() + "|" + declaredType;
                    if (seen.add(key)) {
                        result.add(new ParsedModel.FieldInjectionDef(
                                var.getNameAsString(), declaredType, null, "LOMBOK_CONSTRUCTOR"));
                    }
                }
            }
        }
        return result;
    }

    private static boolean hasClassAnnotation(ClassOrInterfaceDeclaration cls, String simpleName) {
        return cls.getAnnotations().stream()
                .anyMatch(a -> simpleName.equals(a.getNameAsString()));
    }

    public static boolean isKafkaProducerType(String declaredType) {
        if (declaredType == null || declaredType.isBlank()) {
            return false;
        }
        return declaredType.contains("SyncProducer")
                || declaredType.contains("AsyncProducer")
                || declaredType.contains("KafkaTemplate");
    }

    static String annotationMember(AnnotationExpr ann) {
        if (ann.isSingleMemberAnnotationExpr()) {
            return ann.asSingleMemberAnnotationExpr().getMemberValue().toString().replace("\"", "");
        }
        if (ann.isNormalAnnotationExpr()) {
            return ann.asNormalAnnotationExpr().getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("name") || p.getNameAsString().equals("value"))
                    .map(p -> p.getValue().toString().replace("\"", ""))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }
}
