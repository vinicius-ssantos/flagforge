package io.github.viniciusssantos.flagforge.tenancy;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("projects")
public record Project(
        @Id UUID id,
        @Column("organization_id") UUID organizationId,
        @Column("project_key") String key,
        @Column("display_name") String displayName,
        @Column("created_at") Instant createdAt,
        @Column("updated_at") Instant updatedAt,
        @Version Long version) {

    public Project {
        id = TenantValidation.requireId(id, "id");
        organizationId = TenantValidation.requireId(organizationId, "organizationId");
        key = TenantValidation.requireKey(key, "key");
        displayName = TenantValidation.requireName(displayName, "displayName");
        createdAt = TenantValidation.requireTimestamp(createdAt, "createdAt");
        updatedAt = TenantValidation.requireTimestamp(updatedAt, "updatedAt");
        version = TenantValidation.requireVersion(version);
    }

    public static Project create(
            UUID organizationId,
            String key,
            String displayName,
            Instant now) {
        return new Project(
                UUID.randomUUID(),
                organizationId,
                key,
                displayName,
                now,
                now,
                null);
    }

    public Project rename(String newDisplayName, Instant now) {
        return new Project(
                id,
                organizationId,
                key,
                newDisplayName,
                createdAt,
                now,
                version);
    }
}
