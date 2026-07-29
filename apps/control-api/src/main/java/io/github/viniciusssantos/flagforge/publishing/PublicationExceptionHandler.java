package io.github.viniciusssantos.flagforge.publishing;

import java.net.URI;

import io.github.viniciusssantos.flagforge.audit.AuditController;
import io.github.viniciusssantos.flagforge.publishing.PublicationService.PublicationError;
import io.github.viniciusssantos.flagforge.publishing.PublicationService.PublicationException;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
        PublicationController.class,
        RevisionHistoryController.class,
        AuditController.class
})
final class PublicationExceptionHandler {

    private static final String CORRELATION_ATTRIBUTE =
            "flagforge.correlation-id";

    @ExceptionHandler(PublicationException.class)
    ResponseEntity<ProblemDetail> handlePublication(
            PublicationException exception,
            HttpServletRequest request) {
        if (exception.code() == PublicationError.VERSION_CONFLICT) {
            return conflict(exception, request);
        }
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "Invalid publication request",
                "urn:flagforge:problem:invalid-publication",
                exception.getMessage(),
                request);
        problem.setProperty("errorCode", exception.code().name());
        if (exception.validationCode() != null) {
            problem.setProperty("validationCode", exception.validationCode());
        }
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleInvalidArgument(
            IllegalArgumentException exception,
            HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "Invalid history request",
                "urn:flagforge:problem:invalid-history-request",
                exception.getMessage(),
                request);
        problem.setProperty("errorCode", "INVALID_HISTORY_REQUEST");
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadableRequest(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "Invalid publication request",
                "urn:flagforge:problem:invalid-publication",
                "Publication request body is malformed or incomplete",
                request);
        problem.setProperty(
                "errorCode",
                PublicationError.INVALID_EXPECTED_VERSION.name());
        return ResponseEntity.badRequest().body(problem);
    }

    private static ResponseEntity<ProblemDetail> conflict(
            PublicationException exception,
            HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                "Publication version conflict",
                "urn:flagforge:problem:publication-version-conflict",
                exception.getMessage(),
                request);
        problem.setProperty("errorCode", exception.code().name());
        problem.setProperty("expectedVersion", exception.expectedVersion());
        problem.setProperty("currentVersion", exception.currentVersion());
        if (exception.currentRevisionId() != null) {
            problem.setProperty(
                    "currentRevisionId",
                    exception.currentRevisionId());
            problem.setProperty(
                    "currentRevisionNumber",
                    exception.currentRevisionNumber());
            problem.setProperty(
                    "currentChecksum",
                    exception.currentChecksum());
            problem.setProperty(
                    "currentUpdatedAt",
                    exception.currentUpdatedAt());
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
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
        problem.setProperty(
                "correlationId",
                request.getAttribute(CORRELATION_ATTRIBUTE));
        return problem;
    }
}
