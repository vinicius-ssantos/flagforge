CREATE TABLE flagforge.environment_approval_policies (
    organization_id UUID NOT NULL,
    project_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    approval_required BOOLEAN NOT NULL DEFAULT FALSE,
    prevent_self_approval BOOLEAN NOT NULL DEFAULT TRUE,
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (organization_id, project_id, environment_id),
    CONSTRAINT environment_approval_policies_environment_fk
        FOREIGN KEY (organization_id, project_id, environment_id)
        REFERENCES flagforge.environments (organization_id, project_id, id),
    CONSTRAINT environment_approval_policies_actor_not_blank
        CHECK (btrim(updated_by) <> '')
);

CREATE TABLE flagforge.change_requests (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    project_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    state VARCHAR(20) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(2000),
    requester_id VARCHAR(128) NOT NULL,
    reviewer_id VARCHAR(128),
    decision_note VARCHAR(2000),
    expected_publication_version BIGINT NOT NULL,
    candidate_revision_number BIGINT NOT NULL,
    candidate_schema_version INTEGER NOT NULL,
    candidate_algorithm_version VARCHAR(64) NOT NULL,
    candidate_checksum CHAR(64) NOT NULL,
    candidate_payload BYTEA NOT NULL,
    candidate_payload_size INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    submitted_at TIMESTAMPTZ,
    decided_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    published_revision_id UUID,
    published_revision_number BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT change_requests_environment_fk
        FOREIGN KEY (organization_id, project_id, environment_id)
        REFERENCES flagforge.environments (organization_id, project_id, id),
    CONSTRAINT change_requests_state_supported
        CHECK (state IN ('DRAFT', 'IN_REVIEW', 'APPROVED', 'PUBLISHED', 'REJECTED')),
    CONSTRAINT change_requests_title_not_blank
        CHECK (btrim(title) <> ''),
    CONSTRAINT change_requests_requester_not_blank
        CHECK (btrim(requester_id) <> ''),
    CONSTRAINT change_requests_reviewer_not_blank
        CHECK (reviewer_id IS NULL OR btrim(reviewer_id) <> ''),
    CONSTRAINT change_requests_expected_version_non_negative
        CHECK (expected_publication_version >= 0),
    CONSTRAINT change_requests_candidate_revision_positive
        CHECK (candidate_revision_number > 0),
    CONSTRAINT change_requests_payload_consistent
        CHECK (candidate_payload_size = octet_length(candidate_payload)
            AND candidate_payload_size > 0),
    CONSTRAINT change_requests_checksum_format
        CHECK (candidate_checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT change_requests_state_timestamps_consistent
        CHECK (
            (state = 'DRAFT' AND submitted_at IS NULL AND decided_at IS NULL AND published_at IS NULL)
            OR (state = 'IN_REVIEW' AND submitted_at IS NOT NULL AND decided_at IS NULL AND published_at IS NULL)
            OR (state = 'APPROVED' AND submitted_at IS NOT NULL AND decided_at IS NOT NULL AND reviewer_id IS NOT NULL AND published_at IS NULL)
            OR (state = 'REJECTED' AND submitted_at IS NOT NULL AND decided_at IS NOT NULL AND reviewer_id IS NOT NULL AND published_at IS NULL)
            OR (state = 'PUBLISHED' AND submitted_at IS NOT NULL AND decided_at IS NOT NULL AND reviewer_id IS NOT NULL
                AND published_at IS NOT NULL AND published_revision_id IS NOT NULL AND published_revision_number IS NOT NULL)
        )
);

CREATE UNIQUE INDEX change_requests_one_active_per_environment_idx
    ON flagforge.change_requests (organization_id, project_id, environment_id)
    WHERE state IN ('DRAFT', 'IN_REVIEW', 'APPROVED');

CREATE INDEX change_requests_environment_history_idx
    ON flagforge.change_requests (
        organization_id,
        project_id,
        environment_id,
        created_at DESC,
        id DESC
    );

ALTER TABLE flagforge.audit_events
    DROP CONSTRAINT audit_events_resource_type_supported;

ALTER TABLE flagforge.audit_events
    ADD CONSTRAINT audit_events_resource_type_supported
        CHECK (resource_type IN (
            'FEATURE_FLAG',
            'SDK_CREDENTIAL',
            'CONFIGURATION_REVISION',
            'CHANGE_REQUEST',
            'ENVIRONMENT'
        ));

ALTER TABLE flagforge.audit_events
    DROP CONSTRAINT audit_events_action_supported;

ALTER TABLE flagforge.audit_events
    ADD CONSTRAINT audit_events_action_supported
        CHECK (action IN (
            'FEATURE_FLAG_CREATED',
            'FEATURE_FLAG_ARCHIVED',
            'SDK_CREDENTIAL_CREATED',
            'SDK_CREDENTIAL_ROTATED',
            'SDK_CREDENTIAL_REVOKED',
            'CONFIGURATION_PUBLISHED',
            'CONFIGURATION_ROLLED_BACK',
            'CHANGE_REQUEST_CREATED',
            'CHANGE_REQUEST_SUBMITTED',
            'CHANGE_REQUEST_APPROVED',
            'CHANGE_REQUEST_REJECTED',
            'CHANGE_REQUEST_PUBLISHED',
            'ENVIRONMENT_APPROVAL_POLICY_CHANGED'
        ));

COMMENT ON TABLE flagforge.environment_approval_policies IS
    'Environment-level publication governance. Missing rows mean direct publication is allowed.';

COMMENT ON TABLE flagforge.change_requests IS
    'Exact immutable publication candidates and their append-only governance lifecycle.';
