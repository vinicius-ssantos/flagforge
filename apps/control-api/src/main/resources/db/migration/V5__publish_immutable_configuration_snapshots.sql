ALTER TABLE flagforge.environments
    ADD CONSTRAINT environments_tenant_project_identity_unique
    UNIQUE (organization_id, project_id, id);

CREATE TABLE flagforge.configuration_revisions (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    project_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    revision_number BIGINT NOT NULL,
    snapshot_schema_version INTEGER NOT NULL,
    algorithm_version VARCHAR(64) NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    published_by VARCHAR(128) NOT NULL,
    correlation_id VARCHAR(64),
    published_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT configuration_revisions_environment_fk
        FOREIGN KEY (organization_id, project_id, environment_id)
        REFERENCES flagforge.environments (organization_id, project_id, id),
    CONSTRAINT configuration_revisions_number_positive
        CHECK (revision_number > 0),
    CONSTRAINT configuration_revisions_schema_positive
        CHECK (snapshot_schema_version > 0),
    CONSTRAINT configuration_revisions_algorithm_not_blank
        CHECK (btrim(algorithm_version) <> ''),
    CONSTRAINT configuration_revisions_checksum_format
        CHECK (checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT configuration_revisions_actor_not_blank
        CHECK (btrim(published_by) <> ''),
    CONSTRAINT configuration_revisions_correlation_not_blank
        CHECK (correlation_id IS NULL OR btrim(correlation_id) <> ''),
    CONSTRAINT configuration_revisions_environment_version_unique
        UNIQUE (organization_id, project_id, environment_id, revision_number),
    CONSTRAINT configuration_revisions_full_identity_unique
        UNIQUE (
            organization_id,
            project_id,
            environment_id,
            id,
            revision_number
        )
);

CREATE TABLE flagforge.configuration_snapshots (
    revision_id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    project_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    revision_number BIGINT NOT NULL,
    snapshot_schema_version INTEGER NOT NULL,
    algorithm_version VARCHAR(64) NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    payload BYTEA NOT NULL,
    payload_size INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT configuration_snapshots_revision_fk
        FOREIGN KEY (
            organization_id,
            project_id,
            environment_id,
            revision_id,
            revision_number
        )
        REFERENCES flagforge.configuration_revisions (
            organization_id,
            project_id,
            environment_id,
            id,
            revision_number
        ),
    CONSTRAINT configuration_snapshots_schema_positive
        CHECK (snapshot_schema_version > 0),
    CONSTRAINT configuration_snapshots_algorithm_not_blank
        CHECK (btrim(algorithm_version) <> ''),
    CONSTRAINT configuration_snapshots_checksum_format
        CHECK (checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT configuration_snapshots_payload_bounded
        CHECK (
            payload_size = octet_length(payload)
            AND payload_size > 0
            AND payload_size <= 1048576
        ),
    CONSTRAINT configuration_snapshots_identity_unique
        UNIQUE (
            organization_id,
            project_id,
            environment_id,
            revision_number,
            algorithm_version,
            checksum
        )
);

CREATE TABLE flagforge.environment_publication_state (
    organization_id UUID NOT NULL,
    project_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    current_revision_id UUID NOT NULL,
    current_revision_number BIGINT NOT NULL,
    pointer_version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (organization_id, project_id, environment_id),
    CONSTRAINT environment_publication_state_environment_fk
        FOREIGN KEY (organization_id, project_id, environment_id)
        REFERENCES flagforge.environments (organization_id, project_id, id),
    CONSTRAINT environment_publication_state_revision_fk
        FOREIGN KEY (
            organization_id,
            project_id,
            environment_id,
            current_revision_id,
            current_revision_number
        )
        REFERENCES flagforge.configuration_revisions (
            organization_id,
            project_id,
            environment_id,
            id,
            revision_number
        ),
    CONSTRAINT environment_publication_state_version_positive
        CHECK (current_revision_number > 0 AND pointer_version >= 0)
);

CREATE TABLE flagforge.publication_audit_events (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    project_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    revision_number BIGINT NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    action VARCHAR(64) NOT NULL,
    correlation_id VARCHAR(64),
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT publication_audit_events_revision_fk
        FOREIGN KEY (
            organization_id,
            project_id,
            environment_id,
            revision_id,
            revision_number
        )
        REFERENCES flagforge.configuration_revisions (
            organization_id,
            project_id,
            environment_id,
            id,
            revision_number
        ),
    CONSTRAINT publication_audit_events_actor_not_blank
        CHECK (btrim(actor_id) <> ''),
    CONSTRAINT publication_audit_events_action_supported
        CHECK (action = 'CONFIGURATION_PUBLISHED'),
    CONSTRAINT publication_audit_events_correlation_not_blank
        CHECK (correlation_id IS NULL OR btrim(correlation_id) <> '')
);

CREATE TABLE flagforge.configuration_outbox (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    project_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    revision_number BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL,
    delivery_attempts INTEGER NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT configuration_outbox_revision_fk
        FOREIGN KEY (
            organization_id,
            project_id,
            environment_id,
            revision_id,
            revision_number
        )
        REFERENCES flagforge.configuration_revisions (
            organization_id,
            project_id,
            environment_id,
            id,
            revision_number
        ),
    CONSTRAINT configuration_outbox_event_supported
        CHECK (event_type = 'CONFIGURATION_PUBLISHED'),
    CONSTRAINT configuration_outbox_checksum_format
        CHECK (checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT configuration_outbox_payload_bounded
        CHECK (octet_length(payload::text) <= 16384),
    CONSTRAINT configuration_outbox_status_supported
        CHECK (status IN ('PENDING', 'PROCESSING', 'DELIVERED', 'FAILED')),
    CONSTRAINT configuration_outbox_attempts_non_negative
        CHECK (delivery_attempts >= 0)
);

CREATE INDEX configuration_revisions_environment_lookup_idx
    ON flagforge.configuration_revisions (
        organization_id,
        project_id,
        environment_id,
        revision_number DESC
    );

CREATE INDEX configuration_outbox_pending_idx
    ON flagforge.configuration_outbox (available_at, occurred_at)
    WHERE status = 'PENDING';

CREATE FUNCTION flagforge.reject_immutable_configuration_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'published configuration records are immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER configuration_revisions_immutable
    BEFORE UPDATE OR DELETE ON flagforge.configuration_revisions
    FOR EACH ROW
    EXECUTE FUNCTION flagforge.reject_immutable_configuration_mutation();

CREATE TRIGGER configuration_snapshots_immutable
    BEFORE UPDATE OR DELETE ON flagforge.configuration_snapshots
    FOR EACH ROW
    EXECUTE FUNCTION flagforge.reject_immutable_configuration_mutation();

CREATE TRIGGER publication_audit_events_immutable
    BEFORE UPDATE OR DELETE ON flagforge.publication_audit_events
    FOR EACH ROW
    EXECUTE FUNCTION flagforge.reject_immutable_configuration_mutation();

COMMENT ON TABLE flagforge.configuration_revisions IS
    'Immutable publication metadata for one complete environment configuration revision.';

COMMENT ON TABLE flagforge.configuration_snapshots IS
    'Canonical bounded serialized snapshots; exactly one payload belongs to each revision.';

COMMENT ON TABLE flagforge.environment_publication_state IS
    'Single current revision pointer per project environment.';

COMMENT ON TABLE flagforge.publication_audit_events IS
    'Append-only evidence that a configuration publication occurred.';

COMMENT ON TABLE flagforge.configuration_outbox IS
    'Transactional publication events awaiting the delivery relay implemented separately.';
