package io.github.viniciusssantos.flagforge.platform;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

@Component
final class ProblemDetailsResponseWriter {

    private final ObjectMapper objectMapper;

    ProblemDetailsResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void writeUnauthorized(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        write(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "Authentication required",
                "Authentication is required to access this resource.",
                "urn:flagforge:problem:authentication-required");
    }

    void writeForbidden(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception) throws IOException {
        write(
                request,
                response,
                HttpStatus.FORBIDDEN,
                "Access denied",
                "The authenticated principal is not allowed to access this resource.",
                "urn:flagforge:problem:access-denied");
    }

    private void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String title,
            String detail,
            String type) throws IOException {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(title);
        problemDetail.setType(URI.create(type));
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty(
                "correlationId",
                request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE));

        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problemDetail);
    }
}
