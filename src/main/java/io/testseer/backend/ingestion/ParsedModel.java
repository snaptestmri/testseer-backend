package io.testseer.backend.ingestion;

import java.util.List;

public record ParsedModel(
        String filePath,
        String classFqn,
        List<String> annotations,
        List<String> constructorParamTypes,
        List<String> fieldInjectionTypes,
        List<EndpointDef> endpoints,
        List<OutboundCallDef> outboundCalls,
        boolean parseError,
        String parseErrorDetail,
        String classJavadoc,
        List<MethodDef> publicMethods,
        List<String> enumValues,
        List<FieldInjectionDef> fieldInjections,
        List<MethodCallDef> methodCalls,
        List<FactoryRoutingDef> factoryRouting,
        String componentBeanName
) {
    public ParsedModel {
        fieldInjections = fieldInjections == null ? List.of() : fieldInjections;
        methodCalls = methodCalls == null ? List.of() : methodCalls;
        factoryRouting = factoryRouting == null ? List.of() : factoryRouting;
    }

    /** Backward-compatible factory for tests and Kotlin light-parse stubs. */
    public static ParsedModel of(
            String filePath,
            String classFqn,
            List<String> annotations,
            List<String> constructorParamTypes,
            List<String> fieldInjectionTypes,
            List<EndpointDef> endpoints,
            List<OutboundCallDef> outboundCalls,
            boolean parseError,
            String parseErrorDetail,
            String classJavadoc,
            List<MethodDef> publicMethods,
            List<String> enumValues) {
        return new ParsedModel(
                filePath, classFqn, annotations, constructorParamTypes, fieldInjectionTypes,
                endpoints, outboundCalls, parseError, parseErrorDetail, classJavadoc,
                publicMethods, enumValues, List.of(), List.of(), List.of(), null);
    }

    public record EndpointDef(String httpMethod, String path, String methodName) {}

    public record OutboundCallDef(String clientType, String httpMethod, String path,
                                   String sourceMethod) {}

    public record MethodDef(
            String name,
            String javadoc,
            String returnType,
            List<String> parameterTypes,
            List<String> thrownExceptions
    ) {}

    public record FieldInjectionDef(
            String variableName,
            String declaredType,
            String beanName,
            String injectionAnnotation
    ) {}

    public record MethodCallDef(
            String callerMethod,
            String calleeClassFqn,
            String calleeMethod,
            String calleeVariable
    ) {}

    public record FactoryRoutingDef(
            String selectorMethod,
            String discriminatorType,
            String routingKey,
            String targetBean,
            String targetClassFqn,
            boolean fallback
    ) {}
}
