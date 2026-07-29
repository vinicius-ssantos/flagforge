package io.github.viniciusssantos.flagforge.tenancy.internal;

import io.github.viniciusssantos.flagforge.tenancy.TenantAccessException;
import io.github.viniciusssantos.flagforge.tenancy.TenantIdentity;
import io.github.viniciusssantos.flagforge.tenancy.TenantIdentityProvider;
import io.github.viniciusssantos.flagforge.tenancy.TenantPrincipal;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
final class SpringSecurityTenantIdentityProvider implements TenantIdentityProvider {

    @Override
    public TenantIdentity current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof TenantPrincipal principal)) {
            throw TenantAccessException.authenticationRequired();
        }

        return new TenantIdentity(principal.organizationId(), principal.actorId());
    }
}
