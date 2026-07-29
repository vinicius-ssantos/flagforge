package io.github.viniciusssantos.flagforge.evaluation;

import java.net.URI;

import io.github.viniciusssantos.flagforge.evaluation.EvaluationApi.ErrorCode;
import io.github.viniciusssantos.flagforge.evaluation.EvaluationApi.EvaluationRequestException;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = EvaluationController.class)
final class EvaluationExceptionHandler {

    private static final String CORRELATION_ATTRIBUTE =
            "flagforge.correlation-id";

    @ExceptionHandler(EvaluationRequestException.class)
    ResponseEntity<ProblemDetail> handleEvaluationRequest(
            EvaluationRequestException exception,
            HttpServletRequest request) {
        return problem(
                request,
                exception.errorCode(),
                exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadableRequest(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        return problem(
                request,
                ErrorCode.INVALID_REQUEST,
                "Evaluation request body is malformed or incomplete");
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpServletRequest request,
            ErrorCode errorCode,
            String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                detail);
        problemDetail.setTitle("Invalid evaluation request");
        problemDetail.setType(URI.create(
                "urn:flagforge:problem:invalid-evaluation-request"));
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("errorCode", errorCode.name());
        problemDetail.setProperty(
                "correlationId",
                request.getAttribute(CORRELATION_ATTRIBUTE));
        return ResponseEntity.badRequest().body(problemDetail);
    }
}
