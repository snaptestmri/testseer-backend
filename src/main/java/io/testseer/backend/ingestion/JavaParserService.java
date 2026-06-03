package io.testseer.backend.ingestion;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class JavaParserService {

    private final JavaParser parser;

    public JavaParserService() {
        this.parser = new JavaParser();
    }

    public ParsedModel parse(String filePath, String sourceContent) {
        ParseResult<CompilationUnit> result = parser.parse(sourceContent);

        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            String detail = result.getProblems().isEmpty() ? "unknown parse error"
                    : result.getProblems().get(0).getMessage();
            return new ParsedModel(
                    filePath, null, List.of(), List.of(), List.of(),
                    List.of(), List.of(), true, detail
            );
        }

        CompilationUnit cu = result.getResult().get();
        Optional<ClassOrInterfaceDeclaration> primaryClass = cu.getPrimaryType()
                .filter(t -> t instanceof ClassOrInterfaceDeclaration)
                .map(t -> (ClassOrInterfaceDeclaration) t);

        if (primaryClass.isEmpty()) {
            return new ParsedModel(filePath, null, List.of(), List.of(), List.of(),
                    List.of(), List.of(), false, null);
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

        List<String> fieldInjections = cls.getFields().stream()
                .filter(f -> f.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("Autowired")))
                .flatMap(f -> f.getVariables().stream())
                .map(v -> v.getType().asString())
                .toList();

        List<ParsedModel.EndpointDef> endpoints = extractEndpoints(cls);
        List<ParsedModel.OutboundCallDef> outboundCalls = extractOutboundCalls(cls);

        return new ParsedModel(
                filePath, classFqn, annotations, constructorParams,
                fieldInjections, endpoints, outboundCalls, false, null
        );
    }

    private List<ParsedModel.EndpointDef> extractEndpoints(ClassOrInterfaceDeclaration cls) {
        List<ParsedModel.EndpointDef> result = new ArrayList<>();
        String classMapping = cls.getAnnotations().stream()
                .filter(a -> a.getNameAsString().equals("RequestMapping"))
                .map(a -> annotationValue(a, ""))
                .findFirst().orElse("");

        for (MethodDeclaration method : cls.getMethods()) {
            for (AnnotationExpr ann : method.getAnnotations()) {
                String httpMethod = switch (ann.getNameAsString()) {
                    case "GetMapping"     -> "GET";
                    case "PostMapping"    -> "POST";
                    case "PutMapping"     -> "PUT";
                    case "DeleteMapping"  -> "DELETE";
                    case "PatchMapping"   -> "PATCH";
                    case "RequestMapping" -> "GET";
                    default -> null;
                };
                if (httpMethod != null) {
                    String methodPath = annotationValue(ann, "");
                    result.add(new ParsedModel.EndpointDef(
                            httpMethod, classMapping + methodPath, method.getNameAsString()));
                }
            }
        }
        return result;
    }

    private List<ParsedModel.OutboundCallDef> extractOutboundCalls(
            ClassOrInterfaceDeclaration cls) {
        List<ParsedModel.OutboundCallDef> result = new ArrayList<>();
        var CLIENTS = List.of("RestClient", "WebClient", "RestTemplate", "FeignClient");

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
}
