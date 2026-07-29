package io.github.viniciusssantos.flagforge.platform;

import java.io.IOException;
import java.util.List;

import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService;
import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService.SdkAuthenticationException;
import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService.SdkPrincipal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class SdkCredentialAuthenticationFilter
        extends OncePerRequestFilter {

    public static final String EVALUATE_AUTHORITY = "SDK_EVALUATE";

    private static final String EVALUATION_PATH_PREFIX =
            "/api/v1/evaluate/";
    private static final String BEARER_PREFIX = "Bearer ";

    private final SdkCredentialService credentialService;
    private final ProblemDetailsResponseWriter problemDetailsResponseWriter;

    public SdkCredentialAuthenticationFilter(
            SdkCredentialService credentialService,
            ProblemDetailsResponseWriter problemDetailsResponseWriter) {
        this.credentialService = credentialService;
        this.problemDetailsResponseWriter = problemDetailsResponseWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(EVALUATION_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String plaintext = bearerToken(request);
        if (plaintext == null) {
            writeUnauthorized(request, response);
            return;
        }

        try {
            SdkPrincipal principal = credentialService.authenticate(plaintext);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            List.of(new SimpleGrantedAuthority(
                                    EVALUATE_AUTHORITY)));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (SdkAuthenticationException exception) {
            SecurityContextHolder.clearContext();
            writeUnauthorized(request, response);
        }
    }

    private static String bearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null
                || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String plaintext = authorization.substring(BEARER_PREFIX.length());
        return plaintext.isBlank() ? null : plaintext;
    }

    private void writeUnauthorized(
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        problemDetailsResponseWriter.writeUnauthorized(
                request,
                response,
                new BadCredentialsException("Invalid SDK credential"));
    }
}
