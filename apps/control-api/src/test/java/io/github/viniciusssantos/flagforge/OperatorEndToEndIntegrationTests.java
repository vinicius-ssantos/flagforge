package io.github.viniciusssantos.flagforge;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the documented quick start end to end over HTTP only.
 *
 * <p>This is the proof that the platform is self-sufficient: nothing here reaches into a service
 * or seeds a row. If any step still required the service layer, this test could not be written,
 * which is exactly the gap issue #47 set out to close.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class OperatorEndToEndIntegrationTests extends PostgreSqlIntegrationTestSupport {

    private static final String ORGANIZATION_HEADER = "X-FlagForge-Organization";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createsPublishesAndEvaluatesAFlagWithoutTouchingTheServiceLayer() throws Exception {
        String slug = uniqueSlug();
        String token = issueToken("operator-" + UUID.randomUUID());

        postJson("/api/v1/organizations", token, null, """
                {"slug":"%s","displayName":"Acme"}""".formatted(slug))
                .andExpect(status().isCreated());

        String projectId = idOf(postJson("/api/v1/projects", token, slug, """
                {"key":"checkout","displayName":"Checkout"}""")
                .andExpect(status().isCreated()));

        String environmentId = idOf(postJson(
                "/api/v1/projects/" + projectId + "/environments", token, slug, """
                {"key":"production","displayName":"Production"}""")
                .andExpect(status().isCreated()));

        postJson("/api/v1/projects/" + projectId + "/flags", token, slug, """
                {"key":"checkout-v2","displayName":"Checkout v2","ownerId":"squad-checkout",
                 "valueType":"BOOLEAN","lifecycleType":"OPERATIONAL","defaultVariantKey":"enabled",
                 "variants":[{"key":"disabled","booleanValue":false},
                             {"key":"enabled","booleanValue":true}]}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("checkout-v2"))
                .andExpect(jsonPath("$.variants.length()").value(2));

        String credential = mockMvc.perform(post(
                        "/api/v1/environments/" + environmentId + "/sdk-credentials")
                        .header("Authorization", "Bearer " + token)
                        .header(ORGANIZATION_HEADER, slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"production-server\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plaintext").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String plaintext = objectMapper.readTree(credential).get("plaintext").asString();

        mockMvc.perform(post("/api/v1/environments/" + environmentId + "/publication")
                        .header("Authorization", "Bearer " + token)
                        .header(ORGANIZATION_HEADER, slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/evaluate/checkout-v2")
                        .header("Authorization", "Bearer " + plaintext)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"BOOLEAN\",\"defaultValue\":false,"
                                + "\"targetingKey\":\"customer-492\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(true))
                .andExpect(jsonPath("$.variant").value("enabled"))
                .andExpect(jsonPath("$.reason").value("DEFAULT"));
    }

    @Test
    void listedCredentialsNeverExposeTheSecret() throws Exception {
        String slug = uniqueSlug();
        String token = issueToken("operator-" + UUID.randomUUID());
        postJson("/api/v1/organizations", token, null, """
                {"slug":"%s","displayName":"Acme"}""".formatted(slug))
                .andExpect(status().isCreated());
        String projectId = idOf(postJson("/api/v1/projects", token, slug, """
                {"key":"checkout","displayName":"Checkout"}""").andExpect(status().isCreated()));
        String environmentId = idOf(postJson(
                "/api/v1/projects/" + projectId + "/environments", token, slug, """
                {"key":"production","displayName":"Production"}""").andExpect(status().isCreated()));

        String issued = postJson("/api/v1/environments/" + environmentId + "/sdk-credentials",
                token, slug, "{\"name\":\"server\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String plaintext = objectMapper.readTree(issued).get("plaintext").asString();

        String listed = mockMvc.perform(get(
                        "/api/v1/environments/" + environmentId + "/sdk-credentials")
                        .header("Authorization", "Bearer " + token)
                        .header(ORGANIZATION_HEADER, slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].keyPrefix").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(listed).doesNotContain(plaintext);
        assertThat(listed).doesNotContain("plaintext");
    }

    @Test
    void revokedCredentialsStopEvaluating() throws Exception {
        String slug = uniqueSlug();
        String token = issueToken("operator-" + UUID.randomUUID());
        postJson("/api/v1/organizations", token, null, """
                {"slug":"%s","displayName":"Acme"}""".formatted(slug))
                .andExpect(status().isCreated());
        String projectId = idOf(postJson("/api/v1/projects", token, slug, """
                {"key":"checkout","displayName":"Checkout"}""").andExpect(status().isCreated()));
        String environmentId = idOf(postJson(
                "/api/v1/projects/" + projectId + "/environments", token, slug, """
                {"key":"production","displayName":"Production"}""").andExpect(status().isCreated()));
        postJson("/api/v1/projects/" + projectId + "/flags", token, slug, """
                {"key":"checkout-v2","displayName":"Checkout v2","ownerId":"squad","valueType":"BOOLEAN",
                 "lifecycleType":"OPERATIONAL","defaultVariantKey":"enabled",
                 "variants":[{"key":"enabled","booleanValue":true}]}""")
                .andExpect(status().isCreated());

        String issued = postJson("/api/v1/environments/" + environmentId + "/sdk-credentials",
                token, slug, "{\"name\":\"server\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode issuedNode = objectMapper.readTree(issued);
        String plaintext = issuedNode.get("plaintext").asString();
        String credentialId = issuedNode.get("credential").get("id").asString();

        mockMvc.perform(post("/api/v1/environments/" + environmentId + "/publication")
                        .header("Authorization", "Bearer " + token)
                        .header(ORGANIZATION_HEADER, slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/evaluate/checkout-v2")
                        .header("Authorization", "Bearer " + plaintext)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"BOOLEAN\",\"defaultValue\":false,"
                                + "\"targetingKey\":\"customer-1\"}"))
                .andExpect(status().isOk());

        postJson("/api/v1/sdk-credentials/" + credentialId + "/revocation", token, slug, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        mockMvc.perform(post("/api/v1/evaluate/checkout-v2")
                        .header("Authorization", "Bearer " + plaintext)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"BOOLEAN\",\"defaultValue\":false,"
                                + "\"targetingKey\":\"customer-1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invitedDeveloperCanCreateFlagsAndViewerCannot() throws Exception {
        String slug = uniqueSlug();
        String ownerToken = issueToken("owner-" + UUID.randomUUID());
        postJson("/api/v1/organizations", ownerToken, null, """
                {"slug":"%s","displayName":"Acme"}""".formatted(slug))
                .andExpect(status().isCreated());
        String projectId = idOf(postJson("/api/v1/projects", ownerToken, slug, """
                {"key":"checkout","displayName":"Checkout"}""").andExpect(status().isCreated()));

        String developerId = "developer-" + UUID.randomUUID();
        String viewerId = "viewer-" + UUID.randomUUID();
        postJson("/api/v1/memberships", ownerToken, slug, """
                {"actorId":"%s","role":"DEVELOPER"}""".formatted(developerId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("DEVELOPER"));
        postJson("/api/v1/memberships", ownerToken, slug, """
                {"actorId":"%s","role":"VIEWER"}""".formatted(viewerId))
                .andExpect(status().isCreated());

        postJson("/api/v1/projects/" + projectId + "/flags", issueToken(developerId), slug, """
                {"key":"developer-flag","displayName":"Developer flag","ownerId":"squad","valueType":"BOOLEAN",
                 "lifecycleType":"OPERATIONAL","defaultVariantKey":"on",
                 "variants":[{"key":"on","booleanValue":true}]}""")
                .andExpect(status().isCreated());

        postJson("/api/v1/projects/" + projectId + "/flags", issueToken(viewerId), slug, """
                {"key":"viewer-flag","displayName":"Viewer flag","ownerId":"squad","valueType":"BOOLEAN",
                 "lifecycleType":"OPERATIONAL","defaultVariantKey":"on",
                 "variants":[{"key":"on","booleanValue":true}]}""")
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsAVariantWhoseValueContradictsTheDeclaredType() throws Exception {
        String slug = uniqueSlug();
        String token = issueToken("operator-" + UUID.randomUUID());
        postJson("/api/v1/organizations", token, null, """
                {"slug":"%s","displayName":"Acme"}""".formatted(slug))
                .andExpect(status().isCreated());
        String projectId = idOf(postJson("/api/v1/projects", token, slug, """
                {"key":"checkout","displayName":"Checkout"}""").andExpect(status().isCreated()));

        // A BOOLEAN flag carrying a string value must not be silently coerced.
        postJson("/api/v1/projects/" + projectId + "/flags", token, slug, """
                {"key":"coerced","displayName":"Coerced","ownerId":"squad","valueType":"BOOLEAN",
                 "lifecycleType":"OPERATIONAL","defaultVariantKey":"on",
                 "variants":[{"key":"on","stringValue":"true"}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("TYPE_MISMATCH"));

        // NUMBER remains reserved rather than half-implemented.
        postJson("/api/v1/projects/" + projectId + "/flags", token, slug, """
                {"key":"numeric","displayName":"Numeric","ownerId":"squad","valueType":"NUMBER",
                 "lifecycleType":"OPERATIONAL","defaultVariantKey":"one",
                 "variants":[{"key":"one","stringValue":"1"}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_VALUE_TYPE"));
    }

    @Test
    void reportsAMissingRequiredFieldInsteadOfFailingInternally() throws Exception {
        String slug = uniqueSlug();
        String token = issueToken("operator-" + UUID.randomUUID());
        postJson("/api/v1/organizations", token, null, """
                {"slug":"%s","displayName":"Acme"}""".formatted(slug))
                .andExpect(status().isCreated());
        String projectId = idOf(postJson("/api/v1/projects", token, slug, """
                {"key":"checkout","displayName":"Checkout"}""").andExpect(status().isCreated()));

        // The domain signals absent input with NullPointerException. Over HTTP that must not
        // become a 500 that leaks an internal failure.
        postJson("/api/v1/projects/" + projectId + "/flags", token, slug, """
                {"key":"no-owner","displayName":"No owner","valueType":"BOOLEAN",
                 "lifecycleType":"OPERATIONAL","defaultVariantKey":"on",
                 "variants":[{"key":"on","booleanValue":true}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_TEXT"))
                .andExpect(jsonPath("$.detail").value("ownerId is required"));
    }

    @Test
    void hidesFlagsOwnedByAnotherTenant() throws Exception {
        String slugA = uniqueSlug();
        String tokenA = issueToken("owner-a-" + UUID.randomUUID());
        postJson("/api/v1/organizations", tokenA, null, """
                {"slug":"%s","displayName":"A"}""".formatted(slugA)).andExpect(status().isCreated());
        String projectA = idOf(postJson("/api/v1/projects", tokenA, slugA, """
                {"key":"checkout","displayName":"Checkout"}""").andExpect(status().isCreated()));
        String flagId = idOf(postJson("/api/v1/projects/" + projectA + "/flags", tokenA, slugA, """
                {"key":"secret-flag","displayName":"Secret","ownerId":"squad","valueType":"BOOLEAN",
                 "lifecycleType":"OPERATIONAL","defaultVariantKey":"on",
                 "variants":[{"key":"on","booleanValue":true}]}""")
                .andExpect(status().isCreated()));

        String slugB = uniqueSlug();
        String tokenB = issueToken("owner-b-" + UUID.randomUUID());
        postJson("/api/v1/organizations", tokenB, null, """
                {"slug":"%s","displayName":"B"}""".formatted(slugB)).andExpect(status().isCreated());
        String projectB = idOf(postJson("/api/v1/projects", tokenB, slugB, """
                {"key":"checkout","displayName":"Checkout"}""").andExpect(status().isCreated()));

        // The same flag key exists in both tenants; neither can read the other's flag.
        mockMvc.perform(get("/api/v1/projects/" + projectB + "/flags/" + flagId)
                        .header("Authorization", "Bearer " + tokenB)
                        .header(ORGANIZATION_HEADER, slugB))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/projects/" + projectA + "/flags/" + flagId)
                        .header("Authorization", "Bearer " + tokenB)
                        .header(ORGANIZATION_HEADER, slugB))
                .andExpect(status().isNotFound());
    }

    private ResultActions postJson(
            String path,
            String token,
            String slug,
            String body) throws Exception {
        MockHttpServletRequestBuilder request = post(path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (slug != null) {
            request = request.header(ORGANIZATION_HEADER, slug);
        }
        return mockMvc.perform(request);
    }

    private String idOf(ResultActions actions) throws Exception {
        String body = actions.andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asString();
    }

    private String issueToken(String actorId) throws Exception {
        String body = mockMvc.perform(
                        post("/api/v1/dev/tokens")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"actorId\":\"" + actorId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("token").asString();
    }

    private static String uniqueSlug() {
        return "org-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
