package io.github.viniciusssantos.flagforge.tenancy;

public interface TenantIdentityProvider {

    TenantIdentity current();
}
