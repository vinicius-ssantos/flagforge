package io.github.viniciusssantos.flagforge.tenancy;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manages who may act inside the caller's organization.
 *
 * <p>The organization is never taken from the request: it comes from the authenticated principal,
 * so a caller can only add members to the organization it is already acting in. Granting OWNER
 * requires ORGANIZATION_MANAGE, which the service enforces.
 */
@RestController
@RequestMapping("/api/v1/memberships")
final class MembershipController {

    private final TenantHierarchyService tenantHierarchyService;

    MembershipController(TenantHierarchyService tenantHierarchyService) {
        this.tenantHierarchyService = tenantHierarchyService;
    }

    @PostMapping
    ResponseEntity<MembershipResponse> add(@RequestBody MembershipRequest request) {
        if (request == null || request.actorId() == null) {
            throw new IllegalArgumentException("actorId is required");
        }
        Membership membership = request.role() == null
                ? tenantHierarchyService.addMembership(request.actorId())
                : tenantHierarchyService.addMembership(request.actorId(), request.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(MembershipResponse.of(membership));
    }

    @PutMapping("/role")
    MembershipResponse changeRole(@RequestBody MembershipRequest request) {
        if (request == null || request.actorId() == null || request.role() == null) {
            throw new IllegalArgumentException("actorId and role are required");
        }
        return MembershipResponse.of(
                tenantHierarchyService.changeMembershipRole(request.actorId(), request.role()));
    }

    record MembershipRequest(String actorId, MembershipRole role) {
    }

    record MembershipResponse(
            UUID id,
            String actorId,
            MembershipRole role,
            MembershipStatus status,
            Instant createdAt) {

        static MembershipResponse of(Membership membership) {
            return new MembershipResponse(
                    membership.id(),
                    membership.actorId(),
                    membership.role(),
                    membership.status(),
                    membership.createdAt());
        }
    }
}
