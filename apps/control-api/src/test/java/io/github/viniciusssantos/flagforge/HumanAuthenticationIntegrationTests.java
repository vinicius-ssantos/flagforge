package io.github.viniciusssantos.flagforge;

import java.util.List;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.tenancy.MembershipRole;
import io.github.viniciusssantos.flagforge.tenancy.Organization;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the control plane over HTTP with real operator tokens.
 *
 * <p>Unlike the existing tenant tests, which place a {@code TenantPrincipal} directly into the
 * security context, every request here crosses the authentication filter. That is the point: the
 * filter and the organization-selection rule are the subject under test, so bypassing them would
 * prove nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class HumanAuthenticationIntegrationTests extends PostgreSqlIntegrationTestSupport {

    private static final String ORGANIZATION_HEADER = "X-FlagForge-Organization";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TenantHierarchyService tenantHierarchyService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createsTheWholeTenantHierarchyOverHttp() throws Exception {
        String actorId = "founder-" + UUID.randomUUID();
        String token = issueToken(actorId);
        String slug = uniqueSlug();

        String organizationBody = mockMvc.perform(post("/api/v1/organizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("slug", slug, "displayName", "Acme")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value(slug))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(read(organizationBody, "id")).isNotBlank();

        String projectBody = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + token)
                        .header(ORGANIZATION_HEADER, slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("key", "checkout", "displayName", "Checkout")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("checkout"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String projectId = read(projectBody, "id");

        String environmentBody = mockMvc.perform(post("/api/v1/projects/" + projectId + "/environments")
                        .header("Authorization", "Bearer " + token)
                        .header(ORGANIZATION_HEADER, slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("key", "production", "displayName", "Production")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("production"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String environmentId = read(environmentBody, "id");

        mockMvc.perform(get("/api/v1/environments/" + environmentId)
                        .header("Authorization", "Bearer " + token)
                        .header(ORGANIZATION_HEADER, slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId));

        mockMvc.perform(get("/api/v1/organizations/current")
                        .header("Authorization", "Bearer " + token)
                        .header(ORGANIZATION_HEADER, slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(slug));
    }

    @Test
    void registrationTakesTheFounderFromTheTokenAndNotTheRequestBody() throws Exception {
        String actorId = "real-founder-" + UUID.randomUUID();
        String slug = uniqueSlug();

        mockMvc.perform(post("/api/v1/organizations")
                        .header("Authorization", "Bearer " + issueToken(actorId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"" + slug + "\",\"displayName\":\"Acme\","
                                + "\"foundingActorId\":\"impersonated-victim\"}"))
                .andExpect(status().isCreated());

        // The impersonated actor has no membership, so it cannot act in the organization at all.
        mockMvc.perform(get("/api/v1/organizations/current")
                        .header("Authorization", "Bearer " + issueToken("impersonated-victim"))
                        .header(ORGANIZATION_HEADER, slug))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/organizations/current")
                        .header("Authorization", "Bearer " + issueToken(actorId))
                        .header(ORGANIZATION_HEADER, slug))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsEveryUnusableTokenWithOneGenericFailure() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/current"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:flagforge:problem:authentication-required"));

        mockMvc.perform(get("/api/v1/organizations/current")
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:flagforge:problem:authentication-required"));

        mockMvc.perform(get("/api/v1/organizations/current")
                        .header("Authorization", "Bearer " + forgedToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:flagforge:problem:authentication-required"));
    }

    @Test
    void cannotActInAnOrganizationWithoutAnActiveMembership() throws Exception {
        String slug = uniqueSlug();
        registerOrganization(slug, "owner-" + UUID.randomUUID());
        String outsiderToken = issueToken("outsider-" + UUID.randomUUID());

        // An unknown organization and one the caller does not belong to are indistinguishable.
        mockMvc.perform(get("/api/v1/organizations/current")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .header(ORGANIZATION_HEADER, slug))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/organizations/current")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .header(ORGANIZATION_HEADER, uniqueSlug()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void hidesProjectsOwnedByAnotherTenant() throws Exception {
        String slugA = uniqueSlug();
        String actorA = "owner-a-" + UUID.randomUUID();
        registerOrganization(slugA, actorA);
        String tokenA = issueToken(actorA);

        String projectBody = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + tokenA)
                        .header(ORGANIZATION_HEADER, slugA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("key", "private-a", "displayName", "Private A")))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String projectId = read(projectBody, "id");

        String slugB = uniqueSlug();
        String actorB = "owner-b-" + UUID.randomUUID();
        registerOrganization(slugB, actorB);

        // Tenant B guessing tenant A's project id must not learn that it exists.
        mockMvc.perform(get("/api/v1/projects/" + projectId)
                        .header("Authorization", "Bearer " + issueToken(actorB))
                        .header(ORGANIZATION_HEADER, slugB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:flagforge:problem:resource-not-found"));

        mockMvc.perform(get("/api/v1/projects/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + issueToken(actorB))
                        .header(ORGANIZATION_HEADER, slugB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:flagforge:problem:resource-not-found"));
    }

    @Test
    void enforcesRoleBoundariesOnProjectCreation() throws Exception {
        String slug = uniqueSlug();
        String ownerId = "owner-" + UUID.randomUUID();
        Organization organization = registerOrganization(slug, ownerId);

        String viewerId = "viewer-" + UUID.randomUUID();
        String developerId = "developer-" + UUID.randomUUID();
        asOwner(organization.id(), ownerId, () -> {
            tenantHierarchyService.addMembership(viewerId, MembershipRole.VIEWER);
            tenantHierarchyService.addMembership(developerId, MembershipRole.DEVELOPER);
        });

        mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + issueToken(viewerId))
                        .header(ORGANIZATION_HEADER, slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("key", "viewer-attempt", "displayName", "Denied")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:flagforge:problem:access-denied"));

        mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + issueToken(developerId))
                        .header(ORGANIZATION_HEADER, slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("key", "developer-allowed", "displayName", "Allowed")))
                .andExpect(status().isCreated());
    }

    @Test
    void rejectsDuplicateSlugWithoutLeakingAnotherTenant() throws Exception {
        String slug = uniqueSlug();
        registerOrganization(slug, "first-" + UUID.randomUUID());

        mockMvc.perform(post("/api/v1/organizations")
                        .header("Authorization", "Bearer " + issueToken("second-" + UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("slug", slug, "displayName", "Impostor")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:flagforge:problem:invalid-tenant-request"));
    }

    private Organization registerOrganization(String slug, String actorId) {
        return tenantHierarchyService.registerOrganization(slug, "Org " + slug, actorId);
    }

    private void asOwner(UUID organizationId, String actorId, Runnable work) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new TenantPrincipal(organizationId, actorId),
                        null,
                        List.of()));
        try {
            work.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String issueToken(String actorId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/dev/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("actorId", actorId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return read(body, "token");
    }

    private static String forgedToken() {
        return "eyJhbGciOiJub25lIn0."
                + "eyJzdWIiOiJmb3JnZWQtYWN0b3IifQ."
                + "c2lnbmF0dXJl";
    }

    private static String uniqueSlug() {
        return "org-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String json(String... keyValuePairs) {
        StringBuilder builder = new StringBuilder("{");
        for (int index = 0; index < keyValuePairs.length; index += 2) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append('"').append(keyValuePairs[index]).append("\":\"")
                    .append(keyValuePairs[index + 1]).append('"');
        }
        return builder.append('}').toString();
    }

    private String read(String body, String field) {
        JsonNode node = objectMapper.readTree(body);
        return node.get(field).asString();
    }
}
