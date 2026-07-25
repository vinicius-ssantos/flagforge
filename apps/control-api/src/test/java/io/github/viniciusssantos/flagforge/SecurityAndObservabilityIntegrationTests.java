package io.github.viniciusssantos.flagforge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityAndObservabilityIntegrationTests extends PostgreSqlIntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesMinimalHealthAndReadinessEndpoints() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/livez"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/readyz"))
                .andExpect(status().isOk());
    }

    @Test
    void deniesApplicationRoutesWithStableProblemDetails() throws Exception {
        mockMvc.perform(get("/api/organizations/acme/projects/internal"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:flagforge:problem:authentication-required"))
                .andExpect(jsonPath("$.title").value("Authentication required"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("Authentication is required to access this resource."))
                .andExpect(jsonPath("$.instance").value("/api/organizations/acme/projects/internal"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().string(
                        "X-Correlation-ID",
                        matchesPattern("[A-Za-z0-9._:-]{1,64}")));
    }

    @Test
    void unauthenticatedRequestsCannotEnumerateTenantResources() throws Exception {
        MvcResult existingStylePath = mockMvc.perform(get("/api/organizations/acme/projects/known"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        MvcResult missingStylePath = mockMvc.perform(get("/api/organizations/acme/projects/missing"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(existingStylePath.getResponse().getContentAsString())
                .doesNotContain("known", "acme")
                .contains("Authentication is required to access this resource.");
        assertThat(missingStylePath.getResponse().getContentAsString())
                .doesNotContain("missing", "acme")
                .contains("Authentication is required to access this resource.");
    }

    @Test
    void preservesOnlySafeCorrelationIdentifiers() throws Exception {
        mockMvc.perform(get("/actuator/health").header("X-Correlation-ID", "release-check.42"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-ID", "release-check.42"));

        mockMvc.perform(get("/actuator/health").header("X-Correlation-ID", "contains spaces"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "X-Correlation-ID",
                        matchesPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")));
    }

    @Test
    void addsDefensiveSecurityHeaders() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string(
                        "Content-Security-Policy",
                        "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Permissions-Policy", "camera=(), microphone=(), geolocation=()"));
    }
}
