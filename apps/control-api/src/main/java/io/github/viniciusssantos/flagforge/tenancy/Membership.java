package io.github.viniciusssantos.flagforge.tenancy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("memberships")
public record Membership(
        @Id UUID id,
        @Column("organization_id") UUID organizationId,
        @Column("actor_id") String actorId,
        MembershipRole role,
        MembershipStatus status,
        @Column("created_at") Instant createdAt,
        @Column("updated_at") Instant updatedAt,
        @Version Long version) {

    public Membership {
        id = TenantValidation.requireId(id, "id");
        organizationId = TenantValidation.requireId(organizationId, "organizationId");
        actorId = TenantValidation.requireActorId(actorId);
        role = Objects.requireNonNull(role, "role is required");
        status = Objects.requireNonNull(status, "status is required");
        createdAt = TenantValidation.requireTimestamp(createdAt, "createdAt");
        updatedAt = TenantValidation.requireTimestamp(updatedAt, "updatedAt");
        version = TenantValidation.requireVersion(version);
    }

    public static Membership active(
            UUID organizationId,
            String actorId,
            MembershipRole role,
            Instant now) {
        return new Membership(
                UUID.randomUUID(),
                organizationId,
                actorId,
                role,
                MembershipStatus.ACTIVE,
                now,
                now,
                null);
    }

    public Membership suspend(Instant now) {
        return new Membership(
                id,
                organizationId,
                actorId,
                role,
                MembershipStatus.SUSPENDED,
                createdAt,
                now,
                version);
    }

    public Membership changeRole(MembershipRole newRole, Instant now) {
        return new Membership(
                id,
                organizationId,
                actorId,
                newRole,
                status,
                createdAt,
                now,
                version);
    }
}
