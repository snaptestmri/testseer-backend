package io.testseer.backend.registry;

public class DuplicateServiceException extends RuntimeException {
    public DuplicateServiceException(String orgId, String repo, String serviceName) {
        super("Service already registered: %s/%s/%s".formatted(orgId, repo, serviceName));
    }
}
