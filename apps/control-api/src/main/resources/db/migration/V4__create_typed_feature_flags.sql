CREATE TABLE flagforge.feature_flags (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    project_id UUID NOT NULL,
    flag_key VARCHAR(63) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    owner_id VARCHAR(128) NOT NULL,
    value_type VARCHAR(20) NOT NULL,
    lifecycle_type VARCHAR(20) NOT NULL,
    expected_removal_date DATE,
    default_variant_key VARCHAR(63) NOT NULL,
    state VARCHAR(20) NOT NULL,
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT feature_flags_project_tenant_fk
        FOREIGN KEY (organization_id, project_id)
        REFERENCES flagforge.projects (organization_id, id)
        ON DELETE CASCADE,
    CONSTRAINT feature_flags_key_format
        CHECK (flag_key ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT feature_flags_display_name_not_blank
        CHECK (btrim(display_name) <> ''),
    CONSTRAINT feature_flags_owner_not_blank
        CHECK (btrim(owner_id) <> ''),
    CONSTRAINT feature_flags_value_type_supported
        CHECK (value_type IN ('BOOLEAN', 'STRING', 'NUMBER', 'JSON')),
    CONSTRAINT feature_flags_lifecycle_supported
        CHECK (lifecycle_type IN ('RELEASE', 'EXPERIMENT', 'OPERATIONAL', 'KILL_SWITCH')),
    CONSTRAINT feature_flags_default_variant_key_format
        CHECK (default_variant_key ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT feature_flags_state_supported
        CHECK (state IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT feature_flags_archive_consistent
        CHECK (
            (state = 'ACTIVE' AND archived_at IS NULL)
            OR (state = 'ARCHIVED' AND archived_at IS NOT NULL)
        ),
    CONSTRAINT feature_flags_removal_date_policy
        CHECK (
            lifecycle_type NOT IN ('RELEASE', 'EXPERIMENT')
            OR expected_removal_date IS NOT NULL
        ),
    CONSTRAINT feature_flags_key_per_project_unique
        UNIQUE (organization_id, project_id, flag_key),
    CONSTRAINT feature_flags_tenant_identity_unique
        UNIQUE (organization_id, project_id, id)
);

CREATE TABLE flagforge.feature_flag_variants (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    project_id UUID NOT NULL,
    flag_id UUID NOT NULL,
    variant_key VARCHAR(63) NOT NULL,
    value_type VARCHAR(20) NOT NULL,
    boolean_value BOOLEAN,
    string_value VARCHAR(2048),
    number_value NUMERIC(38, 12),
    json_value JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT feature_flag_variants_flag_tenant_fk
        FOREIGN KEY (organization_id, project_id, flag_id)
        REFERENCES flagforge.feature_flags (organization_id, project_id, id)
        ON DELETE CASCADE,
    CONSTRAINT feature_flag_variants_key_format
        CHECK (variant_key ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT feature_flag_variants_value_type_supported
        CHECK (value_type IN ('BOOLEAN', 'STRING', 'NUMBER', 'JSON')),
    CONSTRAINT feature_flag_variants_value_consistent
        CHECK (
            (value_type = 'BOOLEAN'
                AND boolean_value IS NOT NULL
                AND string_value IS NULL
                AND number_value IS NULL
                AND json_value IS NULL)
            OR (value_type = 'STRING'
                AND boolean_value IS NULL
                AND string_value IS NOT NULL
                AND number_value IS NULL
                AND json_value IS NULL)
            OR (value_type = 'NUMBER'
                AND boolean_value IS NULL
                AND string_value IS NULL
                AND number_value IS NOT NULL
                AND json_value IS NULL)
            OR (value_type = 'JSON'
                AND boolean_value IS NULL
                AND string_value IS NULL
                AND number_value IS NULL
                AND json_value IS NOT NULL
                AND jsonb_typeof(json_value) = 'object'
                AND octet_length(json_value::text) <= 16384)
        ),
    CONSTRAINT feature_flag_variants_key_per_flag_unique
        UNIQUE (organization_id, project_id, flag_id, variant_key)
);

CREATE INDEX feature_flags_project_lookup_idx
    ON flagforge.feature_flags (organization_id, project_id, id);

CREATE INDEX feature_flags_active_project_idx
    ON flagforge.feature_flags (organization_id, project_id, flag_key)
    WHERE state = 'ACTIVE';

CREATE INDEX feature_flag_variants_flag_lookup_idx
    ON flagforge.feature_flag_variants (
        organization_id,
        project_id,
        flag_id,
        variant_key
    );

COMMENT ON TABLE flagforge.feature_flags IS
    'Stable project-owned feature flag identities and lifecycle metadata.';

COMMENT ON TABLE flagforge.feature_flag_variants IS
    'Named typed values owned by one stable feature flag identity.';

COMMENT ON COLUMN flagforge.feature_flags.value_type IS
    'Immutable for a stable flag key; incompatible type reuse requires a new key.';

COMMENT ON COLUMN flagforge.feature_flag_variants.json_value IS
    'Reserved bounded JSON object representation with a 16 KiB database limit.';
