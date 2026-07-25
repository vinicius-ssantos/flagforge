package io.github.viniciusssantos.flagforge.tenancy;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("environments")
public record Environment(
        @Id UUID id,
        @Column("organization_id") UUID organizationId,
        @Column("project_id") UUID projectId,
        @Column("environment_key") String key,
        @Column("display_name") String displayName,
        @Column("created_at") Instant createdAt,
        @Column("updated_at") Instant updatedAt,
        @Version Long version) {

    public Environment {
        id = TenantValidation.requireId(id, "id");
        organizationId = TenantValidation.requireId(organizationId, "organizationId");
        projectId = TenantValidation.requireId(projectId, "projectId");
        key = TenantValidation.requireKey(key, "key");
        displayName = TenantValidation.requireName(displayName, "displayName");
        createdAt = TenantValidation.requireTimestamp(createdAt, "createdAt");
        updatedAt = TenantValidation.requireTimestamp(updatedAt, "updatedAt");
        version = TenantValidation.requireVersion(version);
    }

    public static Environment create(
            UUID organizationId,
            UUID projectId,
            String key,
            String displayName,
            Instant now) {
        return new Environment(
                UUID.randomUUID(),
                organizationId,
                projectId,
                key,
                displayName,
                now,
                now,
                null);
    }

    public Environment rename(String newDisplayName, Instant now) {
        return new Environment(
                id,
                organizationId,
                projectId,
                key,
                newDisplayName,
                createdAt,
                now,
                version);
    }
}
