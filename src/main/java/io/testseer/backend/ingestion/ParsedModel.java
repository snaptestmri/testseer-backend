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
        // Semantic enrichment — null/empty when not yet extracted
        String classJavadoc,
        List<MethodDef> publicMethods,
        List<String> enumValues
) {
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
}
