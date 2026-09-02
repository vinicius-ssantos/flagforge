package io.github.viniciusssantos.flagforge.platform;

import java.time.Duration;
import java.time.Instant;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mints operator tokens for local development.
 *
 * <p>This exists so a developer can exercise the control plane from documented commands without
 * standing up an identity provider. It performs no identity verification whatsoever — it signs
 * whatever actor is asked for — which is exactly why it is confined to the {@code dev} profile and
 * absent from every other runtime.
 */
@RestController
@RequestMapping("/api/v1/dev/tokens")
@Profile(DevelopmentTokenConfiguration.DEVELOPMENT_PROFILE)
class DevelopmentTokenController {

    private static final String ISSUER = "flagforge-dev";
    private static final Duration LIFETIME = Duration.ofHours(1);

    private final JwtEncoder jwtEncoder;

    DevelopmentTokenController(JwtEncoder jwtEncoder) {
        this.jwtEncoder = jwtEncoder;
    }

    @PostMapping
    ResponseEntity<TokenResponse> issue(@RequestBody TokenRequest request) {
        if (request == null || request.actorId() == null || request.actorId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(request.actorId().strip())
                .issuedAt(now)
                .expiresAt(now.plus(LIFETIME))
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new TokenResponse(token, LIFETIME.toSeconds()));
    }

    record TokenRequest(String actorId) {
    }

    record TokenResponse(String token, long expiresInSeconds) {
    }
}
