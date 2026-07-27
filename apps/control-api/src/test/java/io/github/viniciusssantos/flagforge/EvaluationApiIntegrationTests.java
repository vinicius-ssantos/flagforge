package io.github.viniciusssantos.flagforge;

import java.util.List;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService;
import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService.IssuedCredential;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.BooleanVariant;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.CreateFlagCommand;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.LifecycleType;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.ValueType;
import io.github.viniciusssantos.flagforge.publishing.PublicationService;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class EvaluationApiIntegrationTests extends PostgreSqlIntegrationTestSupport {

    private static final String REQUEST = """
            {
              "type": "BOOLEAN",
              "defaultValue": false,
              "targetingKey": "private-subject-123",
              "attributes": {
                "country": {
                  "type": "STRING",
                  "value": "BR"
                }
              }
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantHierarchyService tenantHierarchyService;

    @Autowired
    private FeatureFlagService featureFlagService;

    @Autowired
    private SdkCredentialService sdkCredentialService;

    @Autowired
    private PublicationService publicationService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sdkKeyEvaluatesOnlyTheProjectOwnedByItsEnvironment() throws Exception {
        TenantFixture disabledTenant = createTenant(
                "evaluation-disabled",
                "actor-disabled",
                false);
        TenantFixture enabledTenant = createTenant(
                "evaluation-enabled",
                "actor-enabled",
                true);
        SecurityContextHolder.clearContext();

        mockMvc.perform(post("/api/v1/evaluate/checkout-v2")
                        .header(
                                "Authorization",
                                "Bearer " + disabledTenant.credential().plaintext())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.flagKey").value("checkout-v2"))
                .andExpect(jsonPath("$.valueType").value("BOOLEAN"))
                .andExpect(jsonPath("$.value").value(false))
                .andExpect(jsonPath("$.variant").value("disabled"))
                .andExpect(jsonPath("$.reason").value("DEFAULT"))
                .andExpect(jsonPath("$.configurationVersion").isNotEmpty())
                .andExpect(jsonPath("$.error.code").value("NONE"))
                .andExpect(header().string("Cache-Control", "no-store"));

        mockMvc.perform(post("/api/v1/evaluate/checkout-v2")
                        .header(
                                "Authorization",
                                "Bearer " + enabledTenant.credential().plaintext())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(true))
                .andExpect(jsonPath("$.variant").value("enabled"))
                .andExpect(jsonPath("$.reason").value("DEFAULT"))
                .andExpect(jsonPath("$.error.code").value("NONE"));
    }

    @Test
    void authenticationAndFallbackContractsRemainNonEnumerable() throws Exception {
        TenantFixture tenant = createTenant(
                "evaluation-auth",
                "actor-auth",
                false);
        SecurityContextHolder.clearContext();

        mockMvc.perform(post("/api/v1/evaluate/checkout-v2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(
                        "Authentication is required to access this resource."));

        mockMvc.perform(post("/api/v1/evaluate/checkout-v2")
                        .header("Authorization", "Bearer ff_sdk_invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(
                        "Authentication is required to access this resource."));

        mockMvc.perform(post("/api/v1/evaluate/unknown-flag")
                        .header(
                                "Authorization",
                                "Bearer " + tenant.credential().plaintext())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(false))
                .andExpect(jsonPath("$.variant").doesNotExist())
                .andExpect(jsonPath("$.reason").value("ERROR"))
                .andExpect(jsonPath("$.error.code").value("FLAG_NOT_FOUND"));
    }

    @Test
    void malformedTypedFallbackReturnsStableProblemDetails() throws Exception {
        TenantFixture tenant = createTenant(
                "evaluation-invalid",
                "actor-invalid",
                false);
        SecurityContextHolder.clearContext();
        String invalidRequest = """
                {
                  "type": "BOOLEAN",
                  "defaultValue": "false",
                  "targetingKey": "private-subject-123",
                  "attributes": {}
                }
                """;

        mockMvc.perform(post("/api/v1/evaluate/checkout-v2")
                        .header(
                                "Authorization",
                                "Bearer " + tenant.credential().plaintext())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidRequest))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(
                        "urn:flagforge:problem:invalid-evaluation-request"))
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    private TenantFixture createTenant(
            String prefix,
            String actorId,
            boolean defaultValue) {
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
                defaultValue ? "enabled" : "disabled",
                List.of(
                        new BooleanVariant("disabled", false),
                        new BooleanVariant("enabled", true))));
        publicationService.publish(environment.id(), 0);
        IssuedCredential credential = sdkCredentialService.create(
                environment.id(),
                "evaluation-client");
        return new TenantFixture(credential);
    }

    private static void authenticate(UUID organizationId, String actorId) {
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        new TenantPrincipal(organizationId, actorId),
                        null,
                        List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private record TenantFixture(IssuedCredential credential) {
    }
}
