package io.github.viniciusssantos.flagforge;

import java.util.UUID;

import io.github.viniciusssantos.flagforge.tenancy.Environment;
import io.github.viniciusssantos.flagforge.tenancy.Organization;
import io.github.viniciusssantos.flagforge.tenancy.Project;
import io.github.viniciusssantos.flagforge.tenancy.TenantAccessException;
import io.github.viniciusssantos.flagforge.tenancy.TenantAuditContext;
import io.github.viniciusssantos.flagforge.tenancy.TenantHierarchyService;
import io.github.viniciusssantos.flagforge.tenancy.TenantPrincipal;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest
class TenantHierarchyIntegrationTests extends PostgreSqlIntegrationTestSupport {

    @Autowired
    private TenantHierarchyService tenantHierarchyService;

    @AfterEach
    void clearRequestContext() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void organizationCannotReadOrMutateAnotherOrganizationsResources() {
        Organization organizationA = registerOrganization("alpha", "actor-alpha");
        Organization organizationB = registerOrganization("bravo", "actor-bravo");

        authenticate(organizationA.id(), "actor-alpha");
        Project projectA = tenantHierarchyService.createProject("checkout", "Alpha Checkout");
        Environment environmentA = tenantHierarchyService.createEnvironment(
                projectA.id(),
                "qa-blue",
                "QA Blue");

        authenticate(organizationB.id(), "actor-bravo");
        Project projectB = tenantHierarchyService.createProject("checkout", "Bravo Checkout");
        Environment environmentB = tenantHierarchyService.createEnvironment(
                projectB.id(),
                "qa-blue",
                "QA Blue");

        authenticate(organizationA.id(), "actor-alpha");
        assertGenericNotFound(() -> tenantHierarchyService.findProject(projectB.id()));
        assertGenericNotFound(() -> tenantHierarchyService.findProject(UUID.randomUUID()));
        assertGenericNotFound(() -> tenantHierarchyService.findEnvironment(environmentB.id()));
        assertGenericNotFound(() -> tenantHierarchyService.renameProject(
                projectB.id(),
                "Compromised"));
        assertGenericNotFound(() -> tenantHierarchyService.createEnvironment(
                projectB.id(),
                "production",
                "Production"));

        assertThat(tenantHierarchyService.findProject(projectA.id())).isEqualTo(projectA);
        assertThat(tenantHierarchyService.findEnvironment(environmentA.id())).isEqualTo(environmentA);

        authenticate(organizationB.id(), "actor-bravo");
        assertThat(tenantHierarchyService.findProject(projectB.id()).displayName())
                .isEqualTo("Bravo Checkout");
    }

    @Test
    void activeMembershipIsRequiredForTheTenantClaimedByThePrincipal() {
        Organization organization = registerOrganization("membership", "actor-owner");

        authenticate(organization.id(), "actor-outsider");
        TenantAccessException failure = catchThrowableOfType(
                tenantHierarchyService::currentOrganization,
                TenantAccessException.class);

        assertThat(failure.reason())
                .isEqualTo(TenantAccessException.Reason.AUTHENTICATION_REQUIRED);
        assertThat(failure).hasMessage("Authenticated tenant context is required");

        authenticate(organization.id(), "actor-owner");
        tenantHierarchyService.addMembership("actor-member");

        authenticate(organization.id(), "actor-member");
        assertThat(tenantHierarchyService.currentOrganization()).isEqualTo(organization);
    }

    @Test
    void auditContextContainsOnlyOrganizationActorAndCorrelationIdentifiers() {
        Organization organization = registerOrganization("audit", "actor-auditor");
        authenticate(organization.id(), "actor-auditor");
        MDC.put("correlationId", "tenant-test.42");

        TenantAuditContext auditContext = tenantHierarchyService.currentAuditContext();

        assertThat(auditContext.organizationId()).isEqualTo(organization.id());
        assertThat(auditContext.actorId()).isEqualTo("actor-auditor");
        assertThat(auditContext.correlationId()).isEqualTo("tenant-test.42");
    }

    private Organization registerOrganization(String prefix, String actorId) {
        String uniqueSlug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return tenantHierarchyService.registerOrganization(
                uniqueSlug,
                prefix + " organization",
                actorId);
    }

    private static void authenticate(UUID organizationId, String actorId) {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(
                new TenantPrincipal(organizationId, actorId),
                null);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static void assertGenericNotFound(ThrowingCallable operation) {
        TenantAccessException failure = catchThrowableOfType(
                operation,
                TenantAccessException.class);
        assertThat(failure.reason())
                .isEqualTo(TenantAccessException.Reason.RESOURCE_NOT_FOUND);
        assertThat(failure).hasMessage("Resource not found");
    }
}
