package io.github.viniciusssantos.flagforge.credentials;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService.CredentialMetadata;
import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService.IssuedCredential;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class SdkCredentialController {

    private final SdkCredentialService sdkCredentialService;

    SdkCredentialController(SdkCredentialService sdkCredentialService) {
        this.sdkCredentialService = sdkCredentialService;
    }

    /**
     * Issues a credential and returns its plaintext for the only time.
     *
     * <p>The secret is stored as a hash, so a caller that loses this response must rotate rather
     * than read the value back. Listing deliberately exposes metadata only.
     */
    @PostMapping("/environments/{environmentId}/sdk-credentials")
    ResponseEntity<IssuedCredentialResponse> create(
            @PathVariable UUID environmentId,
            @RequestBody CreateCredentialRequest request) {
        String name = request == null ? null : request.name();
        IssuedCredential issued = sdkCredentialService.create(environmentId, name);
        return ResponseEntity.status(HttpStatus.CREATED).body(IssuedCredentialResponse.of(issued));
    }

    @GetMapping("/environments/{environmentId}/sdk-credentials")
    List<CredentialResponse> list(@PathVariable UUID environmentId) {
        return sdkCredentialService.list(environmentId).stream()
                .map(CredentialResponse::of)
                .toList();
    }

    @PostMapping("/sdk-credentials/{credentialId}/rotation")
    IssuedCredentialResponse rotate(@PathVariable UUID credentialId) {
        return IssuedCredentialResponse.of(sdkCredentialService.rotate(credentialId));
    }

    @PostMapping("/sdk-credentials/{credentialId}/revocation")
    CredentialResponse revoke(@PathVariable UUID credentialId) {
        return CredentialResponse.of(sdkCredentialService.revoke(credentialId));
    }

    record CreateCredentialRequest(String name) {
    }

    record CredentialResponse(
            UUID id,
            UUID environmentId,
            String name,
            String keyPrefix,
            String scope,
            String status,
            UUID rotatedFromId,
            Instant createdAt,
            Instant revokedAt) {

        static CredentialResponse of(CredentialMetadata metadata) {
            return new CredentialResponse(
                    metadata.id(),
                    metadata.environmentId(),
                    metadata.name(),
                    metadata.keyPrefix(),
                    metadata.scope().name(),
                    metadata.status().name(),
                    metadata.rotatedFromId(),
                    metadata.createdAt(),
                    metadata.revokedAt());
        }
    }

    record IssuedCredentialResponse(CredentialResponse credential, String plaintext) {

        static IssuedCredentialResponse of(IssuedCredential issued) {
            return new IssuedCredentialResponse(
                    CredentialResponse.of(issued.metadata()),
                    issued.plaintext());
        }
    }
}
