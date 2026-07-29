package io.github.viniciusssantos.flagforge;

import java.util.List;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService;
import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService.CredentialMetadata;
import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService.CredentialScope;
import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService.CredentialStatus;
import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService.IssuedCredential;
import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService.SdkAuthenticationException;
import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService.SdkPrincipal;
import io.github.viniciusssantos.flagforge.tenancy.Environment;
import io.github.viniciusssantos.flagforge.tenancy.MembershipRole;
import io.github.viniciusssantos.flagforge.tenancy.Organization;
import io.github.viniciusssantos.flagforge.tenancy.Project;
import io.github.viniciusssantos.flagforge.tenancy.TenantAccessException;
import io.github.viniciusssantos.flagforge.tenancy.TenantHierarchyService;
import io.github.viniciusssantos.flagforge.tenancy.TenantPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class RbacAndSdkCredentialIntegrationTests extends PostgreSqlIntegrationTestSupport {

    @Autowired
    private TenantHierarchyService tenantHierarchyService;

    @Autowired
    private SdkCredentialService sdkCredentialService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void enforcesLeastPrivilegeAcrossControlPlaneRoles() {
        Organization organization = registerOrganization("rbac", "actor-owner");
        authenticate(organization.id(), "actor-owner");
        Project project = tenantHierarchyService.createProject("checkout", "Checkout");
        Environment environment = tenantHierarchyService.createEnvironment(
                project.id(),
                "production",
                "Production");
        tenantHierarchyService.addMembership("actor-admin", MembershipRole.ADMIN);
        tenantHierarchyService.addMembership("actor-developer", MembershipRole.DEVELOPER);
        tenantHierarchyService.addMembership("actor-viewer", MembershipRole.VIEWER);

        authenticate(organization.id(), "actor-viewer");
        assertThat(tenantHierarchyService.findProject(project.id())).isEqualTo(project);
        assertThat(tenantHierarchyService.findEnvironment(environment.id()))
                .isEqualTo(environment);
        assertAccessDenied(() -> tenantHierarchyService.createProject(
                "viewer-write",
                "Viewer Write"));
        assertAccessDenied(() -> sdkCredentialService.create(
                environment.id(),
                "viewer-key"));

        authenticate(organization.id(), "actor-developer");
        Project developerProject = tenantHierarchyService.createProject(
                "developer-write",
                "Developer Write");
        assertThat(developerProject.organizationId()).isEqualTo(organization.id());
        assertAccessDenied(() -> tenantHierarchyService.addMembership(
                "actor-forbidden",
                MembershipRole.VIEWER));
        assertAccessDenied(() -> sdkCredentialService.create(
                environment.id(),
                "developer-key"));

        authenticate(organization.id(), "actor-admin");
        tenantHierarchyService.addMembership("actor-managed", MembershipRole.VIEWER);
        IssuedCredential adminCredential = sdkCredentialService.create(
                environment.id(),
                "admin-key");
        assertThat(adminCredential.metadata().scope())
                .isEqualTo(CredentialScope.EVALUATE);
        assertAccessDenied(() -> tenantHierarchyService.addMembership(
                "actor-owner-two",
                MembershipRole.OWNER));

        authenticate(organization.id(), "actor-owner");
        IllegalStateException lastOwnerFailure = assertThrows(
                IllegalStateException.class,
                () -> tenantHierarchyService.changeMembershipRole(
                        "actor-owner",
                        MembershipRole.VIEWER));
        assertThat(lastOwnerFailure)
                .hasMessage("Organization must retain at least one active owner");

        tenantHierarchyService.addMembership(
                "actor-owner-two",
                MembershipRole.OWNER);
        assertThat(tenantHierarchyService.changeMembershipRole(
                        "actor-owner",
                        MembershipRole.ADMIN)
                .role())
                .isEqualTo(MembershipRole.ADMIN);
    }

    @Test
    void storesOnlyCredentialHashesAndEnforcesEnvironmentScopeAndRevocation() {
        TenantFixture tenantA = createTenantFixture("alpha", "actor-alpha");
        TenantFixture tenantB = createTenantFixture("bravo", "actor-bravo");

        authenticate(tenantA.organization().id(), tenantA.actorId());
        IssuedCredential issued = sdkCredentialService.create(
                tenantA.primaryEnvironment().id(),
                "evaluation-service");

        assertThat(issued.plaintext())
                .matches("ff_sdk_[0-9a-f]{24}_[0-9a-f]{64}");
        assertThat(issued.metadata().scope()).isEqualTo(CredentialScope.EVALUATE);
        assertThat(issued.metadata().status()).isEqualTo(CredentialStatus.ACTIVE);
        assertThat(issued.metadata().keyPrefix()).startsWith("ff_sdk_");

        String persistedHash = jdbcTemplate.queryForObject(
                "select secret_hash from flagforge.sdk_credentials where id = ?",
                String.class,
                issued.metadata().id());
        Integer plaintextOccurrences = jdbcTemplate.queryForObject(
                "select count(*) from flagforge.sdk_credentials "
                        + "where key_id = ? or credential_name = ? or secret_hash = ?",
                Integer.class,
                issued.plaintext(),
                issued.plaintext(),
                issued.plaintext());
        String secretPart = issued.plaintext().substring(
                issued.plaintext().lastIndexOf('_') + 1);

        assertThat(persistedHash)
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(secretPart)
                .isNotEqualTo(issued.plaintext());
        assertThat(plaintextOccurrences).isZero();

        List<CredentialMetadata> listed = sdkCredentialService.list(
                tenantA.primaryEnvironment().id());
        assertThat(listed)
                .extracting(CredentialMetadata::id)
                .containsExactly(issued.metadata().id());
        assertThat(listed.getFirst().keyPrefix())
                .isEqualTo(issued.metadata().keyPrefix());

        SdkPrincipal principal = sdkCredentialService.authenticateForEnvironment(
                issued.plaintext(),
                tenantA.organization().id(),
                tenantA.primaryEnvironment().id());
        assertThat(principal.environmentId())
                .isEqualTo(tenantA.primaryEnvironment().id());
        assertThat(principal.scope()).isEqualTo(CredentialScope.EVALUATE);

        assertInvalidCredential(() -> sdkCredentialService.authenticateForEnvironment(
                issued.plaintext(),
                tenantA.organization().id(),
                tenantA.secondaryEnvironment().id()));
        assertInvalidCredential(() -> sdkCredentialService.authenticateForEnvironment(
                issued.plaintext(),
                tenantB.organization().id(),
                tenantB.primaryEnvironment().id()));
        assertInvalidCredential(() -> sdkCredentialService.authenticateForEnvironment(
                "ff_sdk_invalid",
                tenantA.organization().id(),
                tenantA.primaryEnvironment().id()));

        IssuedCredential rotated = sdkCredentialService.rotate(issued.metadata().id());
        assertThat(rotated.plaintext()).isNotEqualTo(issued.plaintext());
        assertThat(rotated.metadata().rotatedFromId())
                .isEqualTo(issued.metadata().id());
        assertInvalidCredential(() -> sdkCredentialService.authenticateForEnvironment(
                issued.plaintext(),
                tenantA.organization().id(),
                tenantA.primaryEnvironment().id()));
        assertThat(sdkCredentialService.authenticateForEnvironment(
                        rotated.plaintext(),
                        tenantA.organization().id(),
                        tenantA.primaryEnvironment().id())
                .credentialId())
                .isEqualTo(rotated.metadata().id());

        CredentialMetadata revoked = sdkCredentialService.revoke(
                rotated.metadata().id());
        assertThat(revoked.status()).isEqualTo(CredentialStatus.REVOKED);
        assertThat(revoked.revokedAt()).isNotNull();
        assertInvalidCredential(() -> sdkCredentialService.authenticateForEnvironment(
                rotated.plaintext(),
                tenantA.organization().id(),
                tenantA.primaryEnvironment().id()));

        authenticate(tenantB.organization().id(), tenantB.actorId());
        assertResourceNotFound(() -> sdkCredentialService.revoke(
                issued.metadata().id()));
        assertResourceNotFound(() -> sdkCredentialService.revoke(UUID.randomUUID()));
    }

    private TenantFixture createTenantFixture(String prefix, String actorId) {
        Organization organization = registerOrganization(prefix, actorId);
        authenticate(organization.id(), actorId);
        Project project = tenantHierarchyService.createProject(
                prefix + "-project",
                prefix + " project");
        Environment primary = tenantHierarchyService.createEnvironment(
                project.id(),
                "primary",
                "Primary");
        Environment secondary = tenantHierarchyService.createEnvironment(
                project.id(),
                "secondary",
                "Secondary");
        return new TenantFixture(organization, primary, secondary, actorId);
    }

    private Organization registerOrganization(String prefix, String actorId) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return tenantHierarchyService.registerOrganization(
                slug,
                prefix + " organization",
                actorId);
    }

    private static void authenticate(UUID organizationId, String actorId) {
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        new TenantPrincipal(organizationId, actorId),
                        null,
                        List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static void assertAccessDenied(Executable operation) {
        TenantAccessException failure = assertThrows(
                TenantAccessException.class,
                operation);
        assertThat(failure.reason())
                .isEqualTo(TenantAccessException.Reason.ACCESS_DENIED);
        assertThat(failure).hasMessage("Access denied");
    }

    private static void assertResourceNotFound(Executable operation) {
        TenantAccessException failure = assertThrows(
                TenantAccessException.class,
                operation);
        assertThat(failure.reason())
                .isEqualTo(TenantAccessException.Reason.RESOURCE_NOT_FOUND);
        assertThat(failure).hasMessage("Resource not found");
    }

    private static void assertInvalidCredential(Executable operation) {
        SdkAuthenticationException failure = assertThrows(
                SdkAuthenticationException.class,
                operation);
        assertThat(failure).hasMessage("Invalid SDK credential");
    }

    private record TenantFixture(
            Organization organization,
            Environment primaryEnvironment,
            Environment secondaryEnvironment,
            String actorId) {
    }
}
