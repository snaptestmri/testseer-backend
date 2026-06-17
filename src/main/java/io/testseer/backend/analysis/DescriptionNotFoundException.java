package io.testseer.backend.analysis;

public class DescriptionNotFoundException extends RuntimeException {

    public DescriptionNotFoundException(String serviceId) {
        super("No description generated yet for service '" + serviceId + "'. POST to generate.");
    }
}
