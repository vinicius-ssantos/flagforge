package io.github.viniciusssantos.flagforge.publishing;

import java.net.URI;

import io.github.viniciusssantos.flagforge.publishing.ChangeRequestService.ChangeRequestError;
import io.github.viniciusssantos.flagforge.publishing.ChangeRequestService.ChangeRequestException;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
        ChangeRequestController.class,
        PublicationController.class
})
final class ChangeRequestExceptionHandler {

    @ExceptionHandler(ChangeRequestException.class)
    ProblemDetail handle(ChangeRequestException exception) {
        HttpStatus status = status(exception.code());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status,
                exception.getMessage());
        problem.setType(URI.create("urn:flagforge:problem:change-request"));
        problem.setTitle(title(exception.code()));
        problem.setProperty("errorCode", exception.code().name());
        problem.setProperty("correlationId", MDC.get("correlationId"));
        return problem;
    }

    private static HttpStatus status(ChangeRequestError code) {
        return switch (code) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case SELF_APPROVAL_FORBIDDEN -> HttpStatus.FORBIDDEN;
            case INVALID_TRANSITION,
                    ACTIVE_REQUEST_EXISTS,
                    CANDIDATE_CHANGED,
                    APPROVAL_REQUIRED,
                    VERSION_CONFLICT -> HttpStatus.CONFLICT;
        };
    }

    private static String title(ChangeRequestError code) {
        return switch (code) {
            case APPROVAL_REQUIRED -> "Approval required";
            case SELF_APPROVAL_FORBIDDEN -> "Self approval forbidden";
            case CANDIDATE_CHANGED -> "Change request candidate changed";
            case VERSION_CONFLICT -> "Change request version conflict";
            case ACTIVE_REQUEST_EXISTS -> "Active change request exists";
            case INVALID_TRANSITION -> "Invalid change request transition";
            case INVALID_REQUEST -> "Invalid change request";
        };
    }
}
