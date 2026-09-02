package io.github.viniciusssantos.flagforge.tenancy;

import java.security.Principal;

/**
 * An authenticated human actor that has not selected an organization.
 *
 * <p>This principal authorizes only organization registration, the one operation that cannot
 * require a pre-existing tenant. Every other control-plane operation resolves its tenant through
 * {@link TenantPrincipal}, so a request holding this principal fails authorization exactly as an
 * unauthenticated request does.
 */
public record ActorPrincipal(String actorId) implements Principal {

    public ActorPrincipal {
        actorId = TenantValidation.requireActorId(actorId);
    }

    @Override
    public String getName() {
        return actorId;
    }
}
