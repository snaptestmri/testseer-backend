package io.testseer.backend.registry;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class RegistryExceptionHandler {

    @ExceptionHandler(DuplicateServiceException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DuplicateServiceException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "DUPLICATE_SERVICE", "message", ex.getMessage()));
    }

    @ExceptionHandler(ServiceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ServiceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of(
                        "error", "NOT_FOUND",
                        "message", ex.getMessage(),
                        "hint", "Register via POST /registry/services"
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .toList();
        return ResponseEntity.badRequest()
                .body(Map.of("error", "VALIDATION_ERROR", "errors", errors));
    }
}
