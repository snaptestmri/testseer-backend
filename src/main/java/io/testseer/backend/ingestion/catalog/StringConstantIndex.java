package io.testseer.backend.ingestion.catalog;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Index of {@code static final String} field initializers for compile-time path resolution (TRG-18-R).
 */
public final class StringConstantIndex {

    private final Map<String, String> fieldFqnToLiteral;

    private StringConstantIndex(Map<String, String> fieldFqnToLiteral) {
        this.fieldFqnToLiteral = Map.copyOf(fieldFqnToLiteral);
    }

    public static StringConstantIndex empty() {
        return new StringConstantIndex(Map.of());
    }

    public static StringConstantIndex build(Map<String, String> pathToSource) {
        if (pathToSource == null || pathToSource.isEmpty()) {
            return empty();
        }
        Map<String, String> literals = new HashMap<>();
        JavaParser parser = new JavaParser();
        for (String source : pathToSource.values()) {
            if (source == null || source.isBlank()) {
                continue;
            }
            ParseResult<CompilationUnit> result = parser.parse(source);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                continue;
            }
            CompilationUnit cu = result.getResult().get();
            String pkg = cu.getPackageDeclaration()
                    .map(p -> p.getNameAsString())
                    .orElse("");
            cu.findAll(FieldDeclaration.class).forEach(field -> {
                if (!field.isStatic()) {
                    return;
                }
                if (!isStringType(field)) {
                    return;
                }
                Optional<ClassOrInterfaceDeclaration> owner = field.findAncestor(ClassOrInterfaceDeclaration.class);
                if (owner.isEmpty()) {
                    return;
                }
                String classFqn = pkg.isBlank()
                        ? owner.get().getNameAsString()
                        : pkg + "." + owner.get().getNameAsString();
                for (VariableDeclarator var : field.getVariables()) {
                    var init = var.getInitializer();
                    if (init.isEmpty() || !(init.get() instanceof StringLiteralExpr lit)) {
                        continue;
                    }
                    literals.put(fieldFqn(classFqn, var.getNameAsString()), lit.getValue());
                }
            });
        }
        return new StringConstantIndex(literals);
    }

    public Optional<String> resolveField(String fieldFqn) {
        if (fieldFqn == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(fieldFqnToLiteral.get(fieldFqn));
    }

    public Iterable<String> fieldFqns() {
        return fieldFqnToLiteral.keySet();
    }

    public static String fieldFqn(String classFqn, String fieldName) {
        return classFqn + "#" + fieldName;
    }

    private static boolean isStringType(FieldDeclaration field) {
        String type = field.getElementType().asString();
        return "String".equals(type) || type.endsWith(".String");
    }
}
