package io.github.viniciusssantos.flagforge.tenancy;

import java.util.UUID;

public record TenantAuditContext(
        UUID organizationId,
        String actorId,
        String correlationId) {

    public TenantAuditContext {
        organizationId = TenantValidation.requireId(organizationId, "organizationId");
        actorId = TenantValidation.requireActorId(actorId);
        if (correlationId != null && correlationId.isBlank()) {
            correlationId = null;
        }
    }
}
