package io.testseer.backend.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String error,
        String message,
        String hint,
        String requestId,
        List<String> errors
) {
    public static ApiError of(ApiErrorCode code, String message) {
        return of(code, message, null);
    }

    public static ApiError of(ApiErrorCode code, String message, String hint) {
        return new ApiError(code.name(), message, hint, RequestIdHolder.current(), null);
    }

    public static ApiError validation(List<String> fieldErrors) {
        return new ApiError(
                ApiErrorCode.VALIDATION_ERROR.name(),
                "Request validation failed",
                null,
                RequestIdHolder.current(),
                fieldErrors);
    }

    public static ApiError internal() {
        return of(ApiErrorCode.INTERNAL_ERROR, "An unexpected error occurred");
    }
}
