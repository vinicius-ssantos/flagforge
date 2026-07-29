package io.github.viniciusssantos.flagforge.tenancy;

import java.util.EnumSet;
import java.util.Set;

public enum MembershipRole {
    OWNER(EnumSet.allOf(ControlPlanePermission.class)),
    ADMIN(EnumSet.of(
            ControlPlanePermission.ORGANIZATION_READ,
            ControlPlanePermission.MEMBERSHIP_READ,
            ControlPlanePermission.MEMBERSHIP_MANAGE,
            ControlPlanePermission.PROJECT_READ,
            ControlPlanePermission.PROJECT_WRITE,
            ControlPlanePermission.ENVIRONMENT_READ,
            ControlPlanePermission.ENVIRONMENT_WRITE,
            ControlPlanePermission.FLAG_READ,
            ControlPlanePermission.FLAG_WRITE,
            ControlPlanePermission.SDK_CREDENTIAL_READ,
            ControlPlanePermission.SDK_CREDENTIAL_MANAGE,
            ControlPlanePermission.AUDIT_READ,
            ControlPlanePermission.CHANGE_REQUEST_REVIEW)),
    DEVELOPER(EnumSet.of(
            ControlPlanePermission.ORGANIZATION_READ,
            ControlPlanePermission.MEMBERSHIP_READ,
            ControlPlanePermission.PROJECT_READ,
            ControlPlanePermission.PROJECT_WRITE,
            ControlPlanePermission.ENVIRONMENT_READ,
            ControlPlanePermission.ENVIRONMENT_WRITE,
            ControlPlanePermission.FLAG_READ,
            ControlPlanePermission.FLAG_WRITE)), 
    VIEWER(EnumSet.of(
            ControlPlanePermission.ORGANIZATION_READ,
            ControlPlanePermission.MEMBERSHIP_READ,
            ControlPlanePermission.PROJECT_READ,
            ControlPlanePermission.ENVIRONMENT_READ,
            ControlPlanePermission.FLAG_READ,
            ControlPlanePermission.SDK_CREDENTIAL_READ,
            ControlPlanePermission.AUDIT_READ));

    private final Set<ControlPlanePermission> permissions;

    MembershipRole(Set<ControlPlanePermission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public boolean allows(ControlPlanePermission permission) {
        return permissions.contains(permission);
    }

    public Set<ControlPlanePermission> permissions() {
        return permissions;
    }
}
