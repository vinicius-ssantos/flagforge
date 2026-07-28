package io.github.viniciusssantos.flagforge;

import java.util.List;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.flags.FeatureFlagService;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.BooleanVariant;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.CreateFlagCommand;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.LifecycleType;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.ValueType;
import io.github.viniciusssantos.flagforge.publishing.PublicationService;
import io.github.viniciusssantos.flagforge.publishing.PublicationService.PublishedRevision;
import io.github.viniciusssantos.flagforge.tenancy.Environment;
import io.github.viniciusssantos.flagforge.tenancy.Organization;
import io.github.viniciusssantos.flagforge.tenancy.Project;
import io.github.viniciusssantos.flagforge.tenancy.TenantHierarchyService;
import io.github.viniciusssantos.flagforge.tenancy.TenantPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuditRevisionApiIntegrationTests extends PostgreSqlIntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantHierarchyService tenantHierarchyService;

    @Autowired
    private FeatureFlagService featureFlagService;

    @Autowired
    private PublicationService publicationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void exposesHistoryDiffRollbackAuditAndVersionConflict() throws Exception {
        TenantFixture fixture = createFixture("audit-revision-api", "actor-owner");
        PublishedRevision first = publicationService.publish(
                fixture.environment().id(),
                0);
        jdbcTemplate.update(
                "update flagforge.feature_flags set default_variant_key = 'enabled' "
                        + "where project_id = ? and flag_key = 'checkout-v2'",
                fixture.project().id());
        PublishedRevision second = publicationService.publish(
                fixture.environment().id(),
                first.publicationVersion());
        Authentication principalAuthentication = tenantAuthentication(fixture);
        String environmentBase = "/api/v1/environments/" + fixture.environment().id();

        mockMvc.perform(get(environmentBase + "/revisions")
                        .with(authentication(principalAuthentication)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].revisionNumber").value(2))
                .andExpect(jsonPath("$[0].revisionKind").value("PUBLISH"))
                .andExpect(jsonPath("$[0].current").value(true))
                .andExpect(jsonPath("$[1].revisionNumber").value(1))
                .andExpect(jsonPath("$[1].current").value(false));

        mockMvc.perform(get(environmentBase + "/revisions/diff")
                        .with(authentication(principalAuthentication))
                        .param("fromRevision", "1")
                        .param("toRevision", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromRevision").value(1))
                .andExpect(jsonPath("$.toRevision").value(2))
                .andExpect(jsonPath("$.differences.length()").value(1))
                .andExpect(jsonPath("$.differences[0].path").value(
                        "flags.checkout-v2.defaultVariant"))
                .andExpect(jsonPath("$.differences[0].type").value("CHANGED"))
                .andExpect(jsonPath("$.differences[0].beforeValue").value(
                        "disabled"))
                .andExpect(jsonPath("$.differences[0].afterValue").value(
                        "enabled"));

        mockMvc.perform(post(environmentBase + "/rollback")
                        .with(authentication(principalAuthentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceRevisionNumber\":1,\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionNumber").value(3))
                .andExpect(jsonPath("$.publicationVersion").value(3))
                .andExpect(jsonPath("$.revisionKind").value("ROLLBACK"))
                .andExpect(jsonPath("$.sourceRevisionId").value(
                        first.revisionId().toString()))
                .andExpect(jsonPath("$.sourceRevisionNumber").value(1));

        mockMvc.perform(get(environmentBase + "/audit")
                        .with(authentication(principalAuthentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value(
                        "CONFIGURATION_ROLLED_BACK"))
                .andExpect(jsonPath("$[0].revisionNumber").value(3))
                .andExpect(jsonPath("$[0].correlationId").isNotEmpty());

        mockMvc.perform(post(environmentBase + "/rollback")
                        .with(authentication(principalAuthentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceRevisionNumber\":1,\"expectedVersion\":2}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.expectedVersion").value(2))
                .andExpect(jsonPath("$.currentVersion").value(3))
                .andExpect(jsonPath("$.currentRevisionNumber").value(3));

        mockMvc.perform(get(environmentBase + "/audit"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(environmentBase + "/revisions"))
                .andExpect(status().isUnauthorized());

        org.assertj.core.api.Assertions.assertThat(second.revisionNumber())
                .isEqualTo(2);
    }

    private TenantFixture createFixture(String prefix, String actorId) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        Organization organization = tenantHierarchyService.registerOrganization(
                slug,
                prefix + " organization",
                actorId);
        authenticate(organization.id(), actorId);
        Project project = tenantHierarchyService.createProject(
                prefix + "-project",
                prefix + " project");
        Environment environment = tenantHierarchyService.createEnvironment(
                project.id(),
                "production",
                "Production");
        featureFlagService.create(new CreateFlagCommand(
                project.id(),
                "checkout-v2",
                "Checkout V2",
                null,
                actorId,
                ValueType.BOOLEAN,
                LifecycleType.OPERATIONAL,
                null,
                "disabled",
                List.of(
                        new BooleanVariant("disabled", false),
                        new BooleanVariant("enabled", true))));
        return new TenantFixture(organization, project, environment, actorId);
    }

    private static Authentication tenantAuthentication(TenantFixture fixture) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new TenantPrincipal(
                        fixture.organization().id(),
                        fixture.actorId()),
                null,
                List.of());
    }

    private static void authenticate(UUID organizationId, String actorId) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new TenantPrincipal(organizationId, actorId),
                        null,
                        List.of()));
    }

    private record TenantFixture(
            Organization organization,
            Project project,
            Environment environment,
            String actorId) {
    }
}
