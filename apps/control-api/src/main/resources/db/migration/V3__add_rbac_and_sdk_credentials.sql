ALTER TABLE flagforge.memberships
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'VIEWER';

ALTER TABLE flagforge.memberships
    ADD CONSTRAINT memberships_role_supported
        CHECK (role IN ('OWNER', 'ADMIN', 'DEVELOPER', 'VIEWER'));

ALTER TABLE flagforge.memberships
    ALTER COLUMN role DROP DEFAULT;

CREATE TABLE flagforge.sdk_credentials (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    key_id CHAR(24) NOT NULL,
    credential_name VARCHAR(120) NOT NULL,
    secret_hash CHAR(64) NOT NULL,
    scope VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    rotated_from_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    version BIGINT NOT NULL,
    CONSTRAINT sdk_credentials_environment_tenant_fk
        FOREIGN KEY (organization_id, environment_id)
        REFERENCES flagforge.environments (organization_id, id)
        ON DELETE CASCADE,
    CONSTRAINT sdk_credentials_rotated_from_fk
        FOREIGN KEY (rotated_from_id)
        REFERENCES flagforge.sdk_credentials (id)
        ON DELETE SET NULL,
    CONSTRAINT sdk_credentials_key_id_unique UNIQUE (key_id),
    CONSTRAINT sdk_credentials_name_not_blank
        CHECK (btrim(credential_name) <> ''),
    CONSTRAINT sdk_credentials_hash_format
        CHECK (secret_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT sdk_credentials_scope_supported
        CHECK (scope = 'EVALUATE'),
    CONSTRAINT sdk_credentials_status_supported
        CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT sdk_credentials_revocation_consistent
        CHECK (
            (status = 'ACTIVE' AND revoked_at IS NULL)
            OR (status = 'REVOKED' AND revoked_at IS NOT NULL)
        )
);

CREATE INDEX sdk_credentials_environment_list_idx
    ON flagforge.sdk_credentials (
        organization_id,
        environment_id,
        created_at DESC
    );

CREATE INDEX sdk_credentials_active_lookup_idx
    ON flagforge.sdk_credentials (key_id)
    WHERE status = 'ACTIVE';

COMMENT ON COLUMN flagforge.sdk_credentials.secret_hash IS
    'SHA-256 hash of a cryptographically random 256-bit secret; plaintext is never persisted.';

COMMENT ON TABLE flagforge.sdk_credentials IS
    'Environment-scoped, evaluation-only SDK credentials with rotation and revocation metadata.';
