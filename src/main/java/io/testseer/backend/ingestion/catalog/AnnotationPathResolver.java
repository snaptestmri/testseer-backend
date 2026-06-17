package io.testseer.backend.ingestion.catalog;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.Optional;

/**
 * Resolves {@code @*Mapping} path expressions to compile-time string literals when possible.
 */
public final class AnnotationPathResolver {

    public enum ResolutionKind {
        LITERAL,
        FIELD,
        UNRESOLVED
    }

    public record ResolvedPath(String path, String fieldFqn, ResolutionKind kind) {}

    private AnnotationPathResolver() {}

    public static ResolvedPath resolve(
            Expression expr,
            StringConstantIndex constants,
            StaticImportIndex staticImports,
            ImportIndex typeImports) {

        if (expr == null) {
            return unresolved("");
        }
        if (expr instanceof StringLiteralExpr lit) {
            return new ResolvedPath(lit.getValue(), null, ResolutionKind.LITERAL);
        }
        if (expr instanceof NameExpr nameExpr) {
            return resolveSimpleName(nameExpr.getNameAsString(), constants, staticImports);
        }
        if (expr instanceof FieldAccessExpr fieldAccess) {
            return resolveFieldAccess(fieldAccess, constants, typeImports);
        }
        return unresolved(expr.toString().replace("\"", ""));
    }

    private static ResolvedPath resolveSimpleName(
            String simpleName,
            StringConstantIndex constants,
            StaticImportIndex staticImports) {

        String staticFieldFqn = staticImports.resolveStaticFieldFqn(simpleName);
        if (staticFieldFqn != null) {
            return fromFieldFqn(staticFieldFqn, constants);
        }
        for (String typeFqn : staticImports.wildcardStaticTypes()) {
            ResolvedPath resolved = fromFieldFqn(StringConstantIndex.fieldFqn(typeFqn, simpleName), constants);
            if (resolved.kind() != ResolutionKind.UNRESOLVED) {
                return resolved;
            }
        }
        return unresolved(simpleName);
    }

    private static ResolvedPath resolveFieldAccess(
            FieldAccessExpr fieldAccess,
            StringConstantIndex constants,
            ImportIndex typeImports) {

        String fieldName = fieldAccess.getNameAsString();
        Expression scope = fieldAccess.getScope();
        if (scope instanceof NameExpr typeName) {
            String typeFqn = typeImports != null
                    ? typeImports.resolve(typeName.getNameAsString())
                    : typeName.getNameAsString();
            if (typeFqn != null) {
                return fromFieldFqn(StringConstantIndex.fieldFqn(typeFqn, fieldName), constants);
            }
        }
        return unresolved(fieldAccess.toString().replace("\"", ""));
    }

    private static ResolvedPath fromFieldFqn(String fieldFqn, StringConstantIndex constants) {
        Optional<String> literal = constants.resolveField(fieldFqn);
        if (literal.isPresent()) {
            return new ResolvedPath(literal.get(), fieldFqn, ResolutionKind.FIELD);
        }
        return unresolved(fieldFqn);
    }

    private static ResolvedPath unresolved(String raw) {
        return new ResolvedPath(raw, null, ResolutionKind.UNRESOLVED);
    }
}
