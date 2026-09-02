package io.github.viniciusssantos.flagforge.platform;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * A self-contained token issuer for local development only.
 *
 * <p>Every bean here is confined to the {@code dev} profile, so a production runtime cannot mint
 * its own operator tokens even by accident. The signing key is an RSA pair generated in memory at
 * startup: nothing is read from configuration, nothing is written to disk, and tokens do not
 * survive a restart, so there is no development secret that can leak or be reused elsewhere.
 */
@Configuration(proxyBeanMethods = false)
@Profile(DevelopmentTokenConfiguration.DEVELOPMENT_PROFILE)
class DevelopmentTokenConfiguration {

    static final String DEVELOPMENT_PROFILE = "dev";
    static final String DEVELOPMENT_PATH_PATTERN = "/api/v1/dev/**";

    private static final int KEY_SIZE = 2048;

    @Bean
    DevelopmentSigningKey developmentSigningKey() {
        KeyPairGenerator generator;
        try {
            generator = KeyPairGenerator.getInstance("RSA");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("RSA key generation is unavailable", exception);
        }
        generator.initialize(KEY_SIZE);
        KeyPair keyPair = generator.generateKeyPair();
        return new DevelopmentSigningKey(
                (RSAPublicKey) keyPair.getPublic(),
                (RSAPrivateKey) keyPair.getPrivate());
    }

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder developmentJwtDecoder(DevelopmentSigningKey signingKey) {
        return NimbusJwtDecoder.withPublicKey(signingKey.publicKey()).build();
    }

    @Bean
    JwtEncoder developmentJwtEncoder(DevelopmentSigningKey signingKey) {
        RSAKey rsaKey = new RSAKey.Builder(signingKey.publicKey())
                .privateKey(signingKey.privateKey())
                .keyID(UUID.randomUUID().toString())
                .build();
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(rsaKey));
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain developmentTokenFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(DEVELOPMENT_PATH_PATTERN)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .headers(Customizer.withDefaults());
        return http.build();
    }

    record DevelopmentSigningKey(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
    }
}
