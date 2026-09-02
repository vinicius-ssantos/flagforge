package io.github.viniciusssantos.flagforge.tenancy;

import java.util.Optional;

import io.github.viniciusssantos.flagforge.tenancy.internal.MembershipRepository;
import io.github.viniciusssantos.flagforge.tenancy.internal.OrganizationRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Establishes the tenant identity of an already-authenticated actor.
 *
 * <p>This deliberately does not go through {@link TenantAuthorizationService}: that service reads
 * the principal out of the security context, and this is what puts the principal there. The
 * organization arrives as an untrusted request value, so membership is what makes it trusted — a
 * caller can only act in an organization where it holds an ACTIVE membership.
 */
@Service
public class TenantPrincipalResolver {

    private final OrganizationRepository organizationRepository;
    private final MembershipRepository membershipRepository;

    public TenantPrincipalResolver(
            OrganizationRepository organizationRepository,
            MembershipRepository membershipRepository) {
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
    }

    /**
     * Resolves the principal for an actor acting in one organization.
     *
     * <p>An unknown organization, a malformed slug, a missing membership, and a SUSPENDED
     * membership are indistinguishable to the caller: all return an empty result, so a failed
     * lookup cannot be used to probe which organizations exist.
     */
    @Transactional(readOnly = true)
    public Optional<TenantPrincipal> resolve(String organizationSlug, String actorId) {
        String normalizedSlug;
        String normalizedActorId;
        try {
            normalizedSlug = TenantValidation.requireKey(organizationSlug, "organizationSlug");
            normalizedActorId = TenantValidation.requireActorId(actorId);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return Optional.empty();
        }

        return organizationRepository.findBySlug(normalizedSlug)
                .filter(organization -> membershipRepository
                        .existsByOrganizationIdAndActorIdAndStatus(
                                organization.id(),
                                normalizedActorId,
                                MembershipStatus.ACTIVE))
                .map(organization -> new TenantPrincipal(organization.id(), normalizedActorId));
    }
}
