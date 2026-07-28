package io.github.viniciusssantos.flagforge.credentials;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.viniciusssantos.flagforge.audit.AuditTrailService;
import io.github.viniciusssantos.flagforge.audit.AuditTrailService.AuditAction;
import io.github.viniciusssantos.flagforge.audit.AuditTrailService.AuditCommand;
import io.github.viniciusssantos.flagforge.audit.AuditTrailService.AuditResourceType;
import io.github.viniciusssantos.flagforge.tenancy.ControlPlanePermission;
import io.github.viniciusssantos.flagforge.tenancy.Environment;
import io.github.viniciusssantos.flagforge.tenancy.TenantAccessException;
import io.github.viniciusssantos.flagforge.tenancy.TenantAuthorizationService;
import io.github.viniciusssantos.flagforge.tenancy.TenantHierarchyService;
import io.github.viniciusssantos.flagforge.tenancy.TenantIdentity;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SdkCredentialService {

    private static final String KEY_PREFIX = "ff_sdk_";
    private static final Pattern PLAINTEXT_PATTERN = Pattern.compile(
            "^ff_sdk_([0-9a-f]{24})_([0-9a-f]{64})$");
    private static final int KEY_ID_BYTES = 12;
    private static final int SECRET_BYTES = 32;
    private static final String DUMMY_SECRET_HASH =
            "e3b0c44298fc1c149afbf4c8996fb924"
                    + "27ae41e4649b934ca495991b7852b855";

    private static final String CREDENTIAL_COLUMNS = """
            id,
            organization_id,
            environment_id,
            key_id,
            credential_name,
            secret_hash,
            scope,
            status,
            rotated_from_id,
            created_at,
            updated_at,
            revoked_at,
            version
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TenantAuthorizationService authorizationService;
    private final TenantHierarchyService tenantHierarchyService;
    private final AuditTrailService auditTrailService;
    private final SecureRandom secureRandom;

    public SdkCredentialService(
            NamedParameterJdbcTemplate jdbcTemplate,
            TenantAuthorizationService authorizationService,
            TenantHierarchyService tenantHierarchyService,
            AuditTrailService auditTrailService) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorizationService = authorizationService;
        this.tenantHierarchyService = tenantHierarchyService;
        this.auditTrailService = auditTrailService;
        this.secureRandom = new SecureRandom();
    }

    @Transactional
    public IssuedCredential create(UUID environmentId, String name) {
        TenantIdentity identity = authorizationService.require(
                ControlPlanePermission.SDK_CREDENTIAL_MANAGE);
        Environment environment = tenantHierarchyService.findEnvironment(environmentId);
        ensureSameOrganization(identity, environment.organizationId());
        return issue(identity.organizationId(), environment.id(), name, null);
    }

    @Transactional(readOnly = true)
    public List<CredentialMetadata> list(UUID environmentId) {
        TenantIdentity identity = authorizationService.require(
                ControlPlanePermission.SDK_CREDENTIAL_READ);
        Environment environment = tenantHierarchyService.findEnvironment(environmentId);
        ensureSameOrganization(identity, environment.organizationId());

        String sql = """
                SELECT %s
                FROM flagforge.sdk_credentials
                WHERE organization_id = :organizationId
                  AND environment_id = :environmentId
                ORDER BY created_at DESC, id DESC
                """.formatted(CREDENTIAL_COLUMNS);

        return jdbcTemplate.query(
                        sql,
                        Map.of(
                                "organizationId", identity.organizationId(),
                                "environmentId", environment.id()),
                        (resultSet, rowNumber) -> mapRow(resultSet))
                .stream()
                .map(SdkCredentialService::toMetadata)
                .toList();
    }

    @Transactional
    public IssuedCredential rotate(UUID credentialId) {
        TenantIdentity identity = authorizationService.require(
                ControlPlanePermission.SDK_CREDENTIAL_MANAGE);
        CredentialRow current = findScoped(identity.organizationId(), credentialId)
                .orElseThrow(TenantAccessException::resourceNotFound);
        if (current.status() != CredentialStatus.ACTIVE) {
            throw new IllegalStateException("SDK credential is not active");
        }

        IssuedCredential replacement = issue(
                current.organizationId(),
                current.environmentId(),
                current.name(),
                current.id());
        revokeRow(current);
        return replacement;
    }

    @Transactional
    public CredentialMetadata revoke(UUID credentialId) {
        TenantIdentity identity = authorizationService.require(
                ControlPlanePermission.SDK_CREDENTIAL_MANAGE);
        CredentialRow current = findScoped(identity.organizationId(), credentialId)
                .orElseThrow(TenantAccessException::resourceNotFound);
        if (current.status() == CredentialStatus.REVOKED) {
            return toMetadata(current);
        }
        return toMetadata(revokeRow(current));
    }

    @Transactional(readOnly = true)
    public SdkPrincipal authenticate(String plaintext) {
        ParsedCredential parsed = parsePlaintext(plaintext);
        Optional<CredentialRow> candidate = findByKeyId(parsed.keyId());
        String expectedHash = candidate
                .map(CredentialRow::secretHash)
                .orElse(DUMMY_SECRET_HASH);
        boolean secretValid = secretMatches(parsed.secret(), expectedHash);
        if (candidate.isEmpty()) {
            throw SdkAuthenticationException.invalidCredential();
        }

        CredentialRow credential = candidate.get();
        boolean valid = secretValid
                && credential.status() == CredentialStatus.ACTIVE
                && credential.scope() == CredentialScope.EVALUATE;
        if (!valid) {
            throw SdkAuthenticationException.invalidCredential();
        }

        return new SdkPrincipal(
                credential.id(),
                credential.organizationId(),
                credential.environmentId(),
                credential.scope());
    }

    @Transactional(readOnly = true)
    public SdkPrincipal authenticateForEnvironment(
            String plaintext,
            UUID expectedOrganizationId,
            UUID expectedEnvironmentId) {
        SdkPrincipal principal = authenticate(plaintext);
        boolean valid = principal.organizationId().equals(expectedOrganizationId)
                && principal.environmentId().equals(expectedEnvironmentId);
        if (!valid) {
            throw SdkAuthenticationException.invalidCredential();
        }
        return principal;
    }

    private IssuedCredential issue(
            UUID organizationId,
            UUID environmentId,
            String name,
            UUID rotatedFromId) {
        String normalizedName = requireName(name);
        String keyId = randomHex(KEY_ID_BYTES);
        String secret = randomHex(SECRET_BYTES);
        String plaintext = KEY_PREFIX + keyId + "_" + secret;
        String secretHash = hashSecret(secret);
        Instant now = now();
        UUID credentialId = UUID.randomUUID();

        String sql = """
                INSERT INTO flagforge.sdk_credentials (
                    id,
                    organization_id,
                    environment_id,
                    key_id,
                    credential_name,
                    secret_hash,
                    scope,
                    status,
                    rotated_from_id,
                    created_at,
                    updated_at,
                    revoked_at,
                    version
                ) VALUES (
                    :id,
                    :organizationId,
                    :environmentId,
                    :keyId,
                    :name,
                    :secretHash,
                    :scope,
                    :status,
                    :rotatedFromId,
                    :createdAt,
                    :updatedAt,
                    NULL,
                    0
                )
                """;

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("id", credentialId)
                .addValue("organizationId", organizationId)
                .addValue("environmentId", environmentId)
                .addValue("keyId", keyId)
                .addValue("name", normalizedName)
                .addValue("secretHash", secretHash)
                .addValue("scope", CredentialScope.EVALUATE.name())
                .addValue("status", CredentialStatus.ACTIVE.name())
                .addValue("rotatedFromId", rotatedFromId)
                .addValue("createdAt", Timestamp.from(now))
                .addValue("updatedAt", Timestamp.from(now));
        jdbcTemplate.update(sql, parameters);

        CredentialMetadata metadata = new CredentialMetadata(
                credentialId,
                organizationId,
                environmentId,
                normalizedName,
                displayPrefix(keyId),
                CredentialScope.EVALUATE,
                CredentialStatus.ACTIVE,
                rotatedFromId,
                now,
                null,
                0);
        return new IssuedCredential(metadata, plaintext);
    }

    private CredentialRow revokeRow(CredentialRow current) {
        Instant revokedAt = now();
        String sql = """
                UPDATE flagforge.sdk_credentials
                SET status = 'REVOKED',
                    revoked_at = :revokedAt,
                    updated_at = :updatedAt,
                    version = version + 1
                WHERE id = :id
                  AND organization_id = :organizationId
                  AND status = 'ACTIVE'
                  AND version = :version
                """;
        int updated = jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("revokedAt", Timestamp.from(revokedAt))
                        .addValue("updatedAt", Timestamp.from(revokedAt))
                        .addValue("id", current.id())
                        .addValue("organizationId", current.organizationId())
                        .addValue("version", current.version()));
        if (updated != 1) {
            throw new OptimisticLockingFailureException(
                    "SDK credential changed concurrently");
        }

        return new CredentialRow(
                current.id(),
                current.organizationId(),
                current.environmentId(),
                current.keyId(),
                current.name(),
                current.secretHash(),
                current.scope(),
                CredentialStatus.REVOKED,
                current.rotatedFromId(),
                current.createdAt(),
                revokedAt,
                revokedAt,
                current.version() + 1);
    }

    private Optional<CredentialRow> findScoped(
            UUID organizationId,
            UUID credentialId) {
        String sql = """
                SELECT %s
                FROM flagforge.sdk_credentials
                WHERE organization_id = :organizationId
                  AND id = :credentialId
                """.formatted(CREDENTIAL_COLUMNS);
        return jdbcTemplate.query(
                        sql,
                        Map.of(
                                "organizationId", organizationId,
                                "credentialId", credentialId),
                        (resultSet, rowNumber) -> mapRow(resultSet))
                .stream()
                .findFirst();
    }

    private Optional<CredentialRow> findByKeyId(String keyId) {
        String sql = """
                SELECT %s
                FROM flagforge.sdk_credentials
                WHERE key_id = :keyId
                """.formatted(CREDENTIAL_COLUMNS);
        return jdbcTemplate.query(
                        sql,
                        Map.of("keyId", keyId),
                        (resultSet, rowNumber) -> mapRow(resultSet))
                .stream()
                .findFirst();
    }

    private static CredentialRow mapRow(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        Timestamp revokedAt = resultSet.getTimestamp("revoked_at");
        return new CredentialRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("environment_id", UUID.class),
                resultSet.getString("key_id").strip(),
                resultSet.getString("credential_name"),
                resultSet.getString("secret_hash").strip(),
                CredentialScope.valueOf(resultSet.getString("scope")),
                CredentialStatus.valueOf(resultSet.getString("status")),
                resultSet.getObject("rotated_from_id", UUID.class),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant(),
                revokedAt == null ? null : revokedAt.toInstant(),
                resultSet.getLong("version"));
    }

    private static CredentialMetadata toMetadata(CredentialRow row) {
        return new CredentialMetadata(
                row.id(),
                row.organizationId(),
                row.environmentId(),
                row.name(),
                displayPrefix(row.keyId()),
                row.scope(),
                row.status(),
                row.rotatedFromId(),
                row.createdAt(),
                row.revokedAt(),
                row.version());
    }

    private ParsedCredential parsePlaintext(String plaintext) {
        if (plaintext == null) {
            throw SdkAuthenticationException.invalidCredential();
        }
        Matcher matcher = PLAINTEXT_PATTERN.matcher(plaintext);
        if (!matcher.matches()) {
            throw SdkAuthenticationException.invalidCredential();
        }
        return new ParsedCredential(matcher.group(1), matcher.group(2));
    }

    private static boolean secretMatches(String secret, String expectedHash) {
        byte[] actual = HexFormat.of().parseHex(hashSecret(secret));
        byte[] expected = HexFormat.of().parseHex(expectedHash);
        return MessageDigest.isEqual(actual, expected);
    }

    private static String hashSecret(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String randomHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String requireName(String name) {
        Objects.requireNonNull(name, "name is required");
        String normalized = name.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("name cannot be blank");
        }
        if (normalized.length() > 120) {
            throw new IllegalArgumentException("name cannot exceed 120 characters");
        }
        return normalized;
    }

    private static void ensureSameOrganization(
            TenantIdentity identity,
            UUID resourceOrganizationId) {
        if (!identity.organizationId().equals(resourceOrganizationId)) {
            throw TenantAccessException.resourceNotFound();
        }
    }

    private static String displayPrefix(String keyId) {
        return KEY_PREFIX + keyId.substring(0, 8);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public enum CredentialScope {
        EVALUATE
    }

    public enum CredentialStatus {
        ACTIVE,
        REVOKED
    }

    public record CredentialMetadata(
            UUID id,
            UUID organizationId,
            UUID environmentId,
            String name,
            String keyPrefix,
            CredentialScope scope,
            CredentialStatus status,
            UUID rotatedFromId,
            Instant createdAt,
            Instant revokedAt,
            long version) {
    }

    public record IssuedCredential(
            CredentialMetadata metadata,
            String plaintext) {

        public IssuedCredential {
            Objects.requireNonNull(metadata, "metadata is required");
            Objects.requireNonNull(plaintext, "plaintext is required");
        }
    }

    public record SdkPrincipal(
            UUID credentialId,
            UUID organizationId,
            UUID environmentId,
            CredentialScope scope) implements Principal {

        @Override
        public String getName() {
            return credentialId.toString();
        }
    }

    public static final class SdkAuthenticationException extends RuntimeException {

        private SdkAuthenticationException() {
            super("Invalid SDK credential");
        }

        static SdkAuthenticationException invalidCredential() {
            return new SdkAuthenticationException();
        }
    }

    private record ParsedCredential(String keyId, String secret) {
    }

    private record CredentialRow(
            UUID id,
            UUID organizationId,
            UUID environmentId,
            String keyId,
            String name,
            String secretHash,
            CredentialScope scope,
            CredentialStatus status,
            UUID rotatedFromId,
            Instant createdAt,
            Instant updatedAt,
            Instant revokedAt,
            long version) {
    }
}
