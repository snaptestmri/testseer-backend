package io.testseer.backend.api;

import io.testseer.backend.admin.JobAlreadyInFlightException;
import io.testseer.backend.admin.JobNotFoundException;
import io.testseer.backend.admin.JobNotReplayableException;
import io.testseer.backend.analysis.DescriptionNotFoundException;
import io.testseer.backend.config.workspace.CatalogLibraryNotFoundException;
import io.testseer.backend.config.workspace.DuplicateCatalogLibraryException;
import io.testseer.backend.config.workspace.DuplicateServiceModuleException;
import io.testseer.backend.config.workspace.ServiceModuleNotFoundException;
import io.testseer.backend.registry.DuplicateServiceException;
import io.testseer.backend.registry.ServiceNotFoundException;
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.List;

@RestControllerAdvice
public class TestSeerExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(TestSeerExceptionHandler.class);

    @ExceptionHandler(DuplicateServiceException.class)
    public ResponseEntity<ApiError> handleDuplicate(DuplicateServiceException ex) {
        log.warn("Duplicate service registration: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(ApiErrorCode.CONFLICT, ex.getMessage()));
    }

    @ExceptionHandler({DuplicateCatalogLibraryException.class, DuplicateServiceModuleException.class})
    public ResponseEntity<ApiError> handleDuplicateWorkspaceConfig(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(ApiErrorCode.CONFLICT, ex.getMessage()));
    }

    @ExceptionHandler({CatalogLibraryNotFoundException.class, ServiceModuleNotFoundException.class})
    public ResponseEntity<ApiError> handleWorkspaceConfigNotFound(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(ApiErrorCode.NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(ServiceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ServiceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(
                        ApiErrorCode.NOT_FOUND,
                        ex.getMessage(),
                        "Register via POST /registry/services"));
    }

    @ExceptionHandler(DescriptionNotFoundException.class)
    public ResponseEntity<ApiError> handleDescriptionNotFound(DescriptionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(
                        ApiErrorCode.NOT_FOUND,
                        ex.getMessage(),
                        "POST /v1/services/{serviceId}/description to generate"));
    }

    @ExceptionHandler(JobAlreadyInFlightException.class)
    public ResponseEntity<ApiError> handleConflict(JobAlreadyInFlightException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        ApiErrorCode.CONFLICT,
                        ex.getMessage(),
                        "Poll GET /v1/status/{serviceId} or GET /v1/jobs/{jobId}"));
    }

    @ExceptionHandler(JobNotFoundException.class)
    public ResponseEntity<ApiError> handleJobNotFound(JobNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(ApiErrorCode.NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(JobNotReplayableException.class)
    public ResponseEntity<ApiError> handleJobNotReplayable(JobNotReplayableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(ApiErrorCode.CONFLICT, ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ApiErrorCode.VALIDATION_ERROR, ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .toList();
        return ResponseEntity.badRequest().body(ApiError.validation(errors));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(
                        ApiErrorCode.VALIDATION_ERROR,
                        "Required parameter '" + ex.getParameterName() + "' is missing"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleMissingStaticResource(NoResourceFoundException ex) {
        // Browsers request /favicon.ico automatically; not an application error.
        log.debug("Static resource not found: {}", ex.getResourcePath());
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        if (isClientDisconnect(ex)) {
            log.debug("Client disconnected before response completed requestId={}: {}",
                    RequestIdHolder.current(), ex.getMessage());
            return null;
        }
        log.error("Unhandled API error requestId={}: {}", RequestIdHolder.current(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiError.internal());
    }

    static boolean isClientDisconnect(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ClientAbortException || t instanceof AsyncRequestNotUsableException) {
                return true;
            }
            if (t instanceof IOException io && isDisconnectMessage(io.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDisconnectMessage(String message) {
        if (message == null) {
            return false;
        }
        return message.contains("Broken pipe") || message.contains("Connection reset");
    }
}
