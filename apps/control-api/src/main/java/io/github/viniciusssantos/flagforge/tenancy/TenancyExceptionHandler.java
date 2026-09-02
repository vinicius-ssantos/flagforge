package io.github.viniciusssantos.flagforge.tenancy;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
        OrganizationController.class,
        ProjectController.class,
        EnvironmentController.class
})
final class TenancyExceptionHandler {

    private static final String CORRELATION_ATTRIBUTE = "flagforge.correlation-id";

    /**
     * Maps tenant access failures without revealing which resources exist.
     *
     * <p>A resource owned by another organization produces the same 404 as one that does not exist,
     * so a caller cannot enumerate tenants by comparing responses.
     */
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
                    exception.reason().name(),
                    request);
            case ACCESS_DENIED -> respond(
                    HttpStatus.FORBIDDEN,
                    "Access denied",
                    "urn:flagforge:problem:access-denied",
                    "The authenticated principal is not allowed to access this resource.",
                    exception.reason().name(),
                    request);
            case RESOURCE_NOT_FOUND -> respond(
                    HttpStatus.NOT_FOUND,
                    "Resource not found",
                    "urn:flagforge:problem:resource-not-found",
                    "Resource not found",
                    exception.reason().name(),
                    request);
        };
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleInvalidArgument(
            IllegalArgumentException exception,
            HttpServletRequest request) {
        return respond(
                HttpStatus.BAD_REQUEST,
                "Invalid tenant request",
                "urn:flagforge:problem:invalid-tenant-request",
                exception.getMessage(),
                "INVALID_TENANT_REQUEST",
                request);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ProblemDetail> handleConflict(
            IllegalStateException exception,
            HttpServletRequest request) {
        return respond(
                HttpStatus.CONFLICT,
                "Tenant state conflict",
                "urn:flagforge:problem:tenant-state-conflict",
                exception.getMessage(),
                "TENANT_STATE_CONFLICT",
                request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadableRequest(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        return respond(
                HttpStatus.BAD_REQUEST,
                "Invalid tenant request",
                "urn:flagforge:problem:invalid-tenant-request",
                "Request body is malformed or incomplete",
                "INVALID_TENANT_REQUEST",
                request);
    }

    private static ResponseEntity<ProblemDetail> respond(
            HttpStatus status,
            String title,
            String type,
            String detail,
            String errorCode,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(type));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("correlationId", request.getAttribute(CORRELATION_ATTRIBUTE));
        problem.setProperty("errorCode", errorCode);
        return ResponseEntity.status(status).body(problem);
    }
}
