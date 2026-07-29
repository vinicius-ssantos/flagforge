package io.github.viniciusssantos.flagforge.tenancy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MembershipRoleTests {

    @Test
    void ownerHasEveryControlPlanePermission() {
        assertThat(MembershipRole.OWNER.permissions())
                .containsExactlyInAnyOrder(ControlPlanePermission.values());
    }

    @Test
    void adminManagesMembershipsAndCredentialsButNotOrganizationOwnership() {
        assertThat(MembershipRole.ADMIN.allows(
                ControlPlanePermission.MEMBERSHIP_MANAGE)).isTrue();
        assertThat(MembershipRole.ADMIN.allows(
                ControlPlanePermission.SDK_CREDENTIAL_MANAGE)).isTrue();
        assertThat(MembershipRole.ADMIN.allows(
                ControlPlanePermission.ORGANIZATION_MANAGE)).isFalse();
    }

    @Test
    void developerWritesProjectsAndEnvironmentsWithoutManagingAccess() {
        assertThat(MembershipRole.DEVELOPER.allows(
                ControlPlanePermission.PROJECT_WRITE)).isTrue();
        assertThat(MembershipRole.DEVELOPER.allows(
                ControlPlanePermission.ENVIRONMENT_WRITE)).isTrue();
        assertThat(MembershipRole.DEVELOPER.allows(
                ControlPlanePermission.MEMBERSHIP_MANAGE)).isFalse();
        assertThat(MembershipRole.DEVELOPER.allows(
                ControlPlanePermission.SDK_CREDENTIAL_MANAGE)).isFalse();
    }

    @Test
    void viewerRemainsReadOnly() {
        assertThat(MembershipRole.VIEWER.permissions())
                .contains(
                        ControlPlanePermission.ORGANIZATION_READ,
                        ControlPlanePermission.MEMBERSHIP_READ,
                        ControlPlanePermission.PROJECT_READ,
                        ControlPlanePermission.ENVIRONMENT_READ,
                        ControlPlanePermission.SDK_CREDENTIAL_READ)
                .doesNotContain(
                        ControlPlanePermission.ORGANIZATION_MANAGE,
                        ControlPlanePermission.MEMBERSHIP_MANAGE,
                        ControlPlanePermission.PROJECT_WRITE,
                        ControlPlanePermission.ENVIRONMENT_WRITE,
                        ControlPlanePermission.SDK_CREDENTIAL_MANAGE);
    }
}
