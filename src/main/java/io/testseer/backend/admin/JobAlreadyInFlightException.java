package io.testseer.backend.admin;

public class JobAlreadyInFlightException extends RuntimeException {
    public JobAlreadyInFlightException(String serviceId) {
        super("A QUEUED or RUNNING job already exists for service " + serviceId);
    }
}
