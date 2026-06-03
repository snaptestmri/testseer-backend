package io.testseer.backend.ingestion;

import java.util.List;
import java.util.Map;

public record ParsedModel(
        String filePath,
        String classFqn,
        List<String> annotations,
        List<String> constructorParamTypes,
        List<String> fieldInjectionTypes,
        List<EndpointDef> endpoints,
        List<OutboundCallDef> outboundCalls,
        boolean parseError,
        String parseErrorDetail
) {
    public record EndpointDef(String httpMethod, String path, String methodName) {}
    public record OutboundCallDef(String clientType, String httpMethod, String path, String sourceMethod) {}
}
