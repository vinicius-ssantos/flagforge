package io.github.viniciusssantos.flagforge.tenancy;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations")
final class OrganizationController {

    private final TenantHierarchyService tenantHierarchyService;

    OrganizationController(TenantHierarchyService tenantHierarchyService) {
        this.tenantHierarchyService = tenantHierarchyService;
    }

    /**
     * Registers an organization and makes the calling actor its founding OWNER.
     *
     * <p>The founding actor is taken from the authenticated principal and never from the request
     * body, so a caller cannot found an organization owned by somebody else.
     */
    @PostMapping
    ResponseEntity<OrganizationResponse> register(
            @RequestBody RegisterRequest request,
            Authentication authentication) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        Organization organization = tenantHierarchyService.registerOrganization(
                request.slug(),
                request.displayName(),
                actorId(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(OrganizationResponse.of(organization));
    }

    @GetMapping("/current")
    OrganizationResponse current() {
        return OrganizationResponse.of(tenantHierarchyService.currentOrganization());
    }

    private static String actorId(Authentication authentication) {
        if (authentication == null) {
            throw TenantAccessException.authenticationRequired();
        }
        return switch (authentication.getPrincipal()) {
            case ActorPrincipal actor -> actor.actorId();
            case TenantPrincipal tenant -> tenant.actorId();
            default -> throw TenantAccessException.authenticationRequired();
        };
    }

    record RegisterRequest(String slug, String displayName) {
    }

    record OrganizationResponse(
            UUID id,
            String slug,
            String displayName,
            Instant createdAt) {

        static OrganizationResponse of(Organization organization) {
            return new OrganizationResponse(
                    organization.id(),
                    organization.slug(),
                    organization.displayName(),
                    organization.createdAt());
        }
    }
}
