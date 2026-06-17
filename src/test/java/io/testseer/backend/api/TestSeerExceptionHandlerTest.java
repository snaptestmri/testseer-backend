package io.testseer.backend.api;

import io.testseer.backend.admin.JobAlreadyInFlightException;
import io.testseer.backend.analysis.DescriptionNotFoundException;
import io.testseer.backend.registry.DuplicateServiceException;
import io.testseer.backend.registry.ServiceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import static org.assertj.core.api.Assertions.assertThat;

class TestSeerExceptionHandlerTest {

    private final TestSeerExceptionHandler handler = new TestSeerExceptionHandler();

    @Test
    void notFound_returnsApiErrorJson() {
        var response = handler.handleNotFound(new ServiceNotFoundException("svc-x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().message()).contains("svc-x");
        assertThat(response.getBody().hint()).contains("/registry/services");
        assertThat(response.getBody().requestId()).isNotBlank();
    }

    @Test
    void conflict_returnsApiErrorJson() {
        var response = handler.handleDuplicate(
                new DuplicateServiceException("acme", "repo", "svc"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().error()).isEqualTo("CONFLICT");
    }

    @Test
    void jobConflict_returnsApiErrorJson() {
        var response = handler.handleConflict(new JobAlreadyInFlightException("svc-x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().error()).isEqualTo("CONFLICT");
    }

    @Test
    void badRequest_returnsValidationError() {
        var response = handler.handleIllegalArgument(new IllegalArgumentException("bad input"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void descriptionNotFound_returnsApiErrorJson() {
        var response = handler.handleDescriptionNotFound(new DescriptionNotFoundException("svc-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error()).isEqualTo("NOT_FOUND");
    }

    @Test
    void unexpected_returnsInternalErrorWithoutLeak() {
        var response = handler.handleUnexpected(new RuntimeException("secret stack detail"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().error()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
    }

    @Test
    void clientDisconnect_isNotLoggedAsInternalError() {
        var response = handler.handleUnexpected(new AsyncRequestNotUsableException(
                "ServletOutputStream failed to write: java.io.IOException: Broken pipe",
                new org.apache.catalina.connector.ClientAbortException(new java.io.IOException("Broken pipe"))));

        assertThat(response).isNull();
        assertThat(TestSeerExceptionHandler.isClientDisconnect(
                new java.io.IOException("Broken pipe"))).isTrue();
    }
}
