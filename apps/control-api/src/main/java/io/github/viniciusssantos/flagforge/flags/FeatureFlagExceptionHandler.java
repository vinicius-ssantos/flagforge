package io.github.viniciusssantos.flagforge.flags;

import java.net.URI;

import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.FlagValidationException;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.ValidationCode;
import io.github.viniciusssantos.flagforge.tenancy.TenantAccessException;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = FeatureFlagController.class)
final class FeatureFlagExceptionHandler {

    private static final String CORRELATION_ATTRIBUTE = "flagforge.correlation-id";

    @ExceptionHandler(FlagValidationException.class)
    ResponseEntity<ProblemDetail> handleValidation(
            FlagValidationException exception,
            HttpServletRequest request) {
        HttpStatus status = statusFor(exception.code());
        ProblemDetail problem = problem(
                status,
                "Invalid feature flag request",
                "urn:flagforge:problem:invalid-flag-request",
                exception.getMessage(),
                request);
        problem.setProperty("errorCode", exception.code().name());
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(TenantAccessException.class)
    ResponseEntity<ProblemDetail> handleTenantAccess(
            TenantAccessException exception,
            HttpServletRequest request) {
        return switch (exception.reason()) {
            case AUTHENTICATION_REQUIRED -> respond(
                    HttpStatus.UNAUTHORIZED,
                    "Authentication required",
                    "urn:flagforge:problem:authentication-required",
                    "Authentication is required to access this resource.",
                    exception,
                    request);
            case ACCESS_DENIED -> respond(
                    HttpStatus.FORBIDDEN,
                    "Access denied",
                    "urn:flagforge:problem:access-denied",
                    "The authenticated principal is not allowed to access this resource.",
                    exception,
                    request);
            case RESOURCE_NOT_FOUND -> respond(
                    HttpStatus.NOT_FOUND,
                    "Resource not found",
                    "urn:flagforge:problem:resource-not-found",
                    "Resource not found",
                    exception,
                    request);
        };
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadableRequest(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "Invalid feature flag request",
                "urn:flagforge:problem:invalid-flag-request",
                "Request body is malformed or incomplete",
                request);
        problem.setProperty("errorCode", ValidationCode.INVALID_TEXT.name());
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * A duplicate key is a conflict; everything else the domain rejects is a bad request.
     *
     * <p>Concurrent modification is also a conflict, so a caller can distinguish "your input is
     * wrong" from "retry with fresh state".
     */
    private static HttpStatus statusFor(ValidationCode code) {
        return switch (code) {
            case FLAG_KEY_ALREADY_EXISTS, CONCURRENT_MODIFICATION -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    private static ResponseEntity<ProblemDetail> respond(
            HttpStatus status,
            String title,
            String type,
            String detail,
            TenantAccessException exception,
            HttpServletRequest request) {
        ProblemDetail problem = problem(status, title, type, detail, request);
        problem.setProperty("errorCode", exception.reason().name());
        return ResponseEntity.status(status).body(problem);
    }

    private static ProblemDetail problem(
            HttpStatus status,
            String title,
            String type,
            String detail,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(type));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("correlationId", request.getAttribute(CORRELATION_ATTRIBUTE));
        return problem;
    }
}
