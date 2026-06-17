package io.testseer.backend.config.workspace;

public class ServiceModuleNotFoundException extends RuntimeException {

    public ServiceModuleNotFoundException(String orgId, String moduleId) {
        super("Service module not found: orgId=" + orgId + ", moduleId=" + moduleId);
    }
}
