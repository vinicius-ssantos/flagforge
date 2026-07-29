package io.github.viniciusssantos.flagforge.tenancy;

import io.github.viniciusssantos.flagforge.tenancy.internal.MembershipRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantAuthorizationService {

    private final MembershipRepository membershipRepository;
    private final TenantIdentityProvider tenantIdentityProvider;

    public TenantAuthorizationService(
            MembershipRepository membershipRepository,
            TenantIdentityProvider tenantIdentityProvider) {
        this.membershipRepository = membershipRepository;
        this.tenantIdentityProvider = tenantIdentityProvider;
    }

    @Transactional(readOnly = true)
    public TenantIdentity require(ControlPlanePermission permission) {
        Membership membership = currentMembership();
        if (!membership.role().allows(permission)) {
            throw TenantAccessException.accessDenied();
        }
        return new TenantIdentity(membership.organizationId(), membership.actorId());
    }

    @Transactional(readOnly = true)
    public Membership currentMembership() {
        TenantIdentity identity = tenantIdentityProvider.current();
        return membershipRepository.findByOrganizationIdAndActorId(
                        identity.organizationId(),
                        identity.actorId())
                .filter(membership -> membership.status() == MembershipStatus.ACTIVE)
                .orElseThrow(TenantAccessException::authenticationRequired);
    }
}
