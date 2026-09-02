package io.github.viniciusssantos.flagforge.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;

/**
 * Validates operator tokens against an external OIDC issuer.
 *
 * <p>The decoder exists only when an issuer is configured. Without it no human authentication is
 * possible and the control plane stays closed, which is the same posture the application had
 * before human authentication existed — enabling operator access is an explicit deployment
 * decision, never a default.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "flagforge.security.oidc", name = "issuer-uri")
class OidcResourceServerConfiguration {

    @Bean
    JwtDecoder jwtDecoder(@Value("${flagforge.security.oidc.issuer-uri}") String issuerUri) {
        return JwtDecoders.fromIssuerLocation(issuerUri);
    }
}
