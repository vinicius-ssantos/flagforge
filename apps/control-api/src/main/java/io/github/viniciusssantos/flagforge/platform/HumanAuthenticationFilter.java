package io.github.viniciusssantos.flagforge.platform;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import io.github.viniciusssantos.flagforge.tenancy.ActorPrincipal;
import io.github.viniciusssantos.flagforge.tenancy.TenantPrincipalResolver;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates human operators from an OIDC-issued JWT.
 *
 * <p>The token establishes <em>who</em> is calling through its subject. The organization header
 * establishes <em>where</em> they are acting, and is trusted only after an ACTIVE membership is
 * found for that pair, so the tenant boundary still derives from authenticated context rather than
 * from a request field alone.
 *
 * <p>A request that authenticates but cannot resolve a tenant receives an {@link ActorPrincipal},
 * which reaches only organization registration. Every other operation resolves its tenant through
 * {@code TenantAuthorizationService} and therefore fails as if unauthenticated. A missing token, a
 * malformed token, an unknown organization, and a missing membership are all left unauthenticated
 * for the security chain to reject with one generic 401, so none of them can be told apart.
 */
@Component
public final class HumanAuthenticationFilter extends OncePerRequestFilter {

    public static final String ORGANIZATION_HEADER = "X-FlagForge-Organization";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CONTROL_PLANE_PREFIX = "/api/v1/";
    private static final String EVALUATION_PATH_PREFIX = "/api/v1/evaluate/";
    private static final String DEVELOPMENT_PATH_PREFIX = "/api/v1/dev/";

    private final ObjectProvider<JwtDecoder> jwtDecoderProvider;
    private final TenantPrincipalResolver tenantPrincipalResolver;

    public HumanAuthenticationFilter(
            ObjectProvider<JwtDecoder> jwtDecoderProvider,
            TenantPrincipalResolver tenantPrincipalResolver) {
        this.jwtDecoderProvider = jwtDecoderProvider;
        this.tenantPrincipalResolver = tenantPrincipalResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith(CONTROL_PLANE_PREFIX)
                || uri.startsWith(EVALUATION_PATH_PREFIX)
                || uri.startsWith(DEVELOPMENT_PATH_PREFIX);
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

        authenticate(request).ifPresent(authentication ->
                SecurityContextHolder.getContext().setAuthentication(authentication));
        filterChain.doFilter(request, response);
    }

    private Optional<UsernamePasswordAuthenticationToken> authenticate(HttpServletRequest request) {
        JwtDecoder jwtDecoder = jwtDecoderProvider.getIfAvailable();
        String token = bearerToken(request);
        if (jwtDecoder == null || token == null) {
            return Optional.empty();
        }

        String actorId;
        try {
            Jwt jwt = jwtDecoder.decode(token);
            actorId = jwt.getSubject();
        } catch (JwtException exception) {
            SecurityContextHolder.clearContext();
            return Optional.empty();
        }
        if (actorId == null || actorId.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(authenticated(principal(request, actorId)));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return Optional.empty();
        }
    }

    private Object principal(HttpServletRequest request, String actorId) {
        String organizationSlug = request.getHeader(ORGANIZATION_HEADER);
        if (organizationSlug == null || organizationSlug.isBlank()) {
            return new ActorPrincipal(actorId);
        }
        return tenantPrincipalResolver.resolve(organizationSlug, actorId)
                .map(Object.class::cast)
                .orElseGet(() -> new ActorPrincipal(actorId));
    }

    private static UsernamePasswordAuthenticationToken authenticated(Object principal) {
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
    }

    private static String bearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length());
        return token.isBlank() ? null : token;
    }
}
