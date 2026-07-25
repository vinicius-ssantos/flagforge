package io.github.viniciusssantos.flagforge.tenancy;

public enum ControlPlanePermission {
    ORGANIZATION_READ,
    ORGANIZATION_MANAGE,
    MEMBERSHIP_READ,
    MEMBERSHIP_MANAGE,
    PROJECT_READ,
    PROJECT_WRITE,
    ENVIRONMENT_READ,
    ENVIRONMENT_WRITE,
    SDK_CREDENTIAL_READ,
    SDK_CREDENTIAL_MANAGE
}
