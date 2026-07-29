package io.github.viniciusssantos.flagforge.tenancy;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("organizations")
public record Organization(
        @Id UUID id,
        String slug,
        @Column("display_name") String displayName,
        @Column("created_at") Instant createdAt,
        @Column("updated_at") Instant updatedAt,
        @Version Long version) {

    public Organization {
        id = TenantValidation.requireId(id, "id");
        slug = TenantValidation.requireKey(slug, "slug");
        displayName = TenantValidation.requireName(displayName, "displayName");
        createdAt = TenantValidation.requireTimestamp(createdAt, "createdAt");
        updatedAt = TenantValidation.requireTimestamp(updatedAt, "updatedAt");
        version = TenantValidation.requireVersion(version);
    }

    public static Organization create(String slug, String displayName, Instant now) {
        return new Organization(UUID.randomUUID(), slug, displayName, now, now, null);
    }

    public Organization rename(String newDisplayName, Instant now) {
        return new Organization(id, slug, newDisplayName, createdAt, now, version);
    }
}
