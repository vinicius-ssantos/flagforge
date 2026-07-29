package io.github.viniciusssantos.flagforge.tenancy;

import java.util.UUID;

public record TenantIdentity(UUID organizationId, String actorId) {

    public TenantIdentity {
        organizationId = TenantValidation.requireId(organizationId, "organizationId");
        actorId = TenantValidation.requireActorId(actorId);
    }
}
