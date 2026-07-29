package io.github.viniciusssantos.flagforge;

import java.util.List;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.flags.FeatureFlagService;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.BooleanVariant;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.CreateFlagCommand;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.LifecycleType;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.ValueType;
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
class PublicationApiIntegrationTests extends PostgreSqlIntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantHierarchyService tenantHierarchyService;

    @Autowired
    private FeatureFlagService featureFlagService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void publishesReadsAndReturnsConflictMetadata() throws Exception {
        TenantFixture fixture = createFixture("publication-api", "actor-owner");
        String endpoint = "/api/v1/environments/"
                + fixture.environment().id()
                + "/publication";
        Authentication principalAuthentication = tenantAuthentication(fixture);

        mockMvc.perform(post(endpoint)
                        .with(authentication(principalAuthentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.environmentId").value(
                        fixture.environment().id().toString()))
                .andExpect(jsonPath("$.revisionNumber").value(1))
                .andExpect(jsonPath("$.publicationVersion").value(1))
                .andExpect(jsonPath("$.checksum").isNotEmpty());

        mockMvc.perform(get(endpoint)
                        .with(authentication(principalAuthentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionNumber").value(1))
                .andExpect(jsonPath("$.publicationVersion").value(1));

        mockMvc.perform(post(endpoint)
                        .with(authentication(principalAuthentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(
                        "urn:flagforge:problem:publication-version-conflict"))
                .andExpect(jsonPath("$.title").value(
                        "Publication version conflict"))
                .andExpect(jsonPath("$.errorCode").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.expectedVersion").value(0))
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.currentRevisionId").isNotEmpty())
                .andExpect(jsonPath("$.currentRevisionNumber").value(1))
                .andExpect(jsonPath("$.currentChecksum").isNotEmpty())
                .andExpect(jsonPath("$.currentUpdatedAt").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void rejectsMissingExpectedVersionWithStableProblemDetails()
            throws Exception {
        TenantFixture fixture = createFixture(
                "missing-publication-version",
                "actor-owner");
        String endpoint = "/api/v1/environments/"
                + fixture.environment().id()
                + "/publication";

        mockMvc.perform(post(endpoint)
                        .with(authentication(tenantAuthentication(fixture)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(
                        "urn:flagforge:problem:invalid-publication"))
                .andExpect(jsonPath("$.errorCode").value(
                        "INVALID_EXPECTED_VERSION"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void publicationEndpointRequiresAuthentication() throws Exception {
        TenantFixture fixture = createFixture(
                "unauthenticated-publication",
                "actor-owner");
        SecurityContextHolder.clearContext();
        String endpoint = "/api/v1/environments/"
                + fixture.environment().id()
                + "/publication";

        mockMvc.perform(post(endpoint)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isUnauthorized());
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
        return new TenantFixture(organization, environment, actorId);
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
            Environment environment,
            String actorId) {
    }
}
