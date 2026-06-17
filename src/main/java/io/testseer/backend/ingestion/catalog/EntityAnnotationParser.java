package io.testseer.backend.ingestion.catalog;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** JavaParser-based entity annotation extraction (replaces regex for @Table / @Document). */
@Component
public class EntityAnnotationParser {

    private final JavaParser parser = new JavaParser();

    public record JpaTableInfo(String name, String catalog) {}

    public record DocumentInfo(String collection) {}

    public record CassandraTableInfo(String value) {}

    public Optional<JpaTableInfo> jpaTable(String content) {
        return primaryType(content)
                .flatMap(type -> type.getAnnotationByName("Table")
                        .map(this::parseJpaTable));
    }

    public Optional<DocumentInfo> mongoDocument(String content) {
        return primaryType(content)
                .flatMap(type -> type.getAnnotationByName("Document")
                        .map(this::parseDocument));
    }

    public Optional<CassandraTableInfo> cassandraTable(String content) {
        return primaryType(content)
                .flatMap(type -> type.getAnnotations().stream()
                        .filter(a -> a.getNameAsString().equals("Table")
                                && type.getFullyQualifiedName().orElse("").contains(".nosql."))
                        .findFirst()
                        .map(this::parseCassandraTable));
    }

    private Optional<TypeDeclaration<?>> primaryType(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        try {
            ParseResult<CompilationUnit> result = parser.parse(content);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                return Optional.empty();
            }
            return result.getResult().get().getTypes().stream().findFirst();
        } catch (AssertionError | RuntimeException ex) {
            return Optional.empty();
        }
    }

    private JpaTableInfo parseJpaTable(AnnotationExpr ann) {
        String name = stringMember(ann, "name");
        if (name == null) name = stringMember(ann, "value");
        String catalog = stringMember(ann, "catalog");
        return new JpaTableInfo(name, catalog);
    }

    private DocumentInfo parseDocument(AnnotationExpr ann) {
        String collection = stringMember(ann, "collection");
        if (collection == null) collection = stringMember(ann, "value");
        return new DocumentInfo(collection);
    }

    private CassandraTableInfo parseCassandraTable(AnnotationExpr ann) {
        String value = stringMember(ann, "value");
        if (value == null) value = stringMember(ann, "name");
        return new CassandraTableInfo(value);
    }

    private static String stringMember(AnnotationExpr ann, String memberName) {
        if (ann.isSingleMemberAnnotationExpr() && "value".equals(memberName)) {
            return literal(ann.asSingleMemberAnnotationExpr().getMemberValue());
        }
        if (ann.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : ann.asNormalAnnotationExpr().getPairs()) {
                if (pair.getNameAsString().equals(memberName)) {
                    return literal(pair.getValue());
                }
            }
        }
        return null;
    }

    private static String literal(Expression expr) {
        if (expr == null) return null;
        String raw = expr.toString().trim();
        if ((raw.startsWith("\"") && raw.endsWith("\""))
                || (raw.startsWith("'") && raw.endsWith("'"))) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw.isEmpty() ? null : raw;
    }
}
