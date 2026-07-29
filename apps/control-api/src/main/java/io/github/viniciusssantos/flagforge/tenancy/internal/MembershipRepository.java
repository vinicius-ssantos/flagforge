package io.github.viniciusssantos.flagforge.tenancy.internal;

import java.util.Optional;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.tenancy.Membership;
import io.github.viniciusssantos.flagforge.tenancy.MembershipRole;
import io.github.viniciusssantos.flagforge.tenancy.MembershipStatus;

import org.springframework.data.repository.CrudRepository;

public interface MembershipRepository extends CrudRepository<Membership, UUID> {

    boolean existsByOrganizationIdAndActorIdAndStatus(
            UUID organizationId,
            String actorId,
            MembershipStatus status);

    Optional<Membership> findByOrganizationIdAndActorId(
            UUID organizationId,
            String actorId);

    long countByOrganizationIdAndRoleAndStatus(
            UUID organizationId,
            MembershipRole role,
            MembershipStatus status);
}
