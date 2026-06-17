package io.testseer.backend.config.workspace;

public class DuplicateServiceModuleException extends RuntimeException {

    public DuplicateServiceModuleException(String orgId, String moduleId) {
        super("Service module already exists: orgId=" + orgId + ", moduleId=" + moduleId);
    }
}
