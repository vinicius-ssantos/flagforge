package io.github.viniciusssantos.flagforge;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the development token issuer cannot exist outside the {@code dev} profile.
 *
 * <p>The issuer signs a token for any actor asked for, so a runtime that accidentally kept it
 * would let anyone become any operator. This test is the guard on that.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DevelopmentTokenIsolationTests extends PostgreSqlIntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void doesNotRegisterTheDevelopmentTokenIssuerByDefault() {
        assertThat(applicationContext.getBeanNamesForType(JwtEncoder.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(Object.class).keySet())
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("developmenttoken"));
    }

    @Test
    void leavesTheControlPlaneClosedWhenNoIssuerIsConfigured() throws Exception {
        assertThat(applicationContext.getBeanNamesForType(JwtDecoder.class)).isEmpty();

        mockMvc.perform(post("/api/v1/dev/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorId\":\"anyone\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"acme\",\"displayName\":\"Acme\"}"))
                .andExpect(status().isUnauthorized());
    }
}
