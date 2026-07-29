package io.github.viniciusssantos.flagforge.tenancy;

import java.security.Principal;
import java.util.UUID;

public record TenantPrincipal(UUID organizationId, String actorId) implements Principal {

    public TenantPrincipal {
        organizationId = TenantValidation.requireId(organizationId, "organizationId");
        actorId = TenantValidation.requireActorId(actorId);
    }

    @Override
    public String getName() {
        return actorId;
    }
}
