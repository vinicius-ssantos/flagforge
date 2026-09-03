package io.github.viniciusssantos.flagforge.credentials;

import java.net.URI;

import io.github.viniciusssantos.flagforge.tenancy.TenantAccessException;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = SdkCredentialController.class)
final class SdkCredentialExceptionHandler {

    private static final String CORRELATION_ATTRIBUTE = "flagforge.correlation-id";

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

    /**
     * Rotating or revoking a credential that is no longer active is a conflict, not a bad request.
     *
     * <p>The message is deliberately generic: it must not reveal a credential's state to a caller
     * that guessed its identifier, and the tenant check has already run before this point.
     */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ProblemDetail> handleInactiveCredential(
            IllegalStateException exception,
            HttpServletRequest request) {
        return respond(
                HttpStatus.CONFLICT,
                "Credential state conflict",
                "urn:flagforge:problem:credential-state-conflict",
                "The credential is not in a state that allows this operation",
                "CREDENTIAL_STATE_CONFLICT",
                request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleConcurrentModification(
            OptimisticLockingFailureException exception,
            HttpServletRequest request) {
        return respond(
                HttpStatus.CONFLICT,
                "Credential state conflict",
                "urn:flagforge:problem:credential-state-conflict",
                "The credential changed concurrently; reload and retry",
                "CONCURRENT_MODIFICATION",
                request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadableRequest(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        return respond(
                HttpStatus.BAD_REQUEST,
                "Invalid credential request",
                "urn:flagforge:problem:invalid-credential-request",
                "Request body is malformed or incomplete",
                "INVALID_CREDENTIAL_REQUEST",
                request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleInvalidArgument(
            IllegalArgumentException exception,
            HttpServletRequest request) {
        return respond(
                HttpStatus.BAD_REQUEST,
                "Invalid credential request",
                "urn:flagforge:problem:invalid-credential-request",
                exception.getMessage(),
                "INVALID_CREDENTIAL_REQUEST",
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
