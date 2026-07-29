ALTER TABLE flagforge.configuration_revisions
    ADD COLUMN revision_kind VARCHAR(20) NOT NULL DEFAULT 'PUBLISH',
    ADD COLUMN source_revision_id UUID,
    ADD COLUMN source_revision_number BIGINT;

ALTER TABLE flagforge.configuration_revisions
    ADD CONSTRAINT configuration_revisions_kind_supported
        CHECK (revision_kind IN ('PUBLISH', 'ROLLBACK')),
    ADD CONSTRAINT configuration_revisions_source_consistent
        CHECK (
            (revision_kind = 'PUBLISH'
                AND source_revision_id IS NULL
                AND source_revision_number IS NULL)
            OR
            (revision_kind = 'ROLLBACK'
                AND source_revision_id IS NOT NULL
                AND source_revision_number IS NOT NULL
                AND source_revision_number > 0
                AND source_revision_number < revision_number)
        ),
    ADD CONSTRAINT configuration_revisions_source_fk
        FOREIGN KEY (
            organization_id,
            project_id,
            environment_id,
            source_revision_id,
            source_revision_number
        )
        REFERENCES flagforge.configuration_revisions (
            organization_id,
            project_id,
            environment_id,
            id,
            revision_number
        );

ALTER TABLE flagforge.publication_audit_events
    DROP CONSTRAINT publication_audit_events_action_supported;

ALTER TABLE flagforge.publication_audit_events
    ADD CONSTRAINT publication_audit_events_action_supported
        CHECK (action IN (
            'CONFIGURATION_PUBLISHED',
            'CONFIGURATION_ROLLED_BACK'
        ));

CREATE TABLE flagforge.audit_events (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    project_id UUID,
    environment_id UUID,
    actor_id VARCHAR(128) NOT NULL,
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id UUID NOT NULL,
    revision_id UUID,
    revision_number BIGINT,
    correlation_id VARCHAR(64),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT audit_events_organization_fk
        FOREIGN KEY (organization_id)
        REFERENCES flagforge.organizations (id),
    CONSTRAINT audit_events_project_fk
        FOREIGN KEY (organization_id, project_id)
        REFERENCES flagforge.projects (organization_id, id),
    CONSTRAINT audit_events_environment_fk
        FOREIGN KEY (organization_id, project_id, environment_id)
        REFERENCES flagforge.environments (organization_id, project_id, id),
    CONSTRAINT audit_events_revision_fk
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
    CONSTRAINT audit_events_actor_not_blank
        CHECK (btrim(actor_id) <> ''),
    CONSTRAINT audit_events_action_supported
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
            'CHANGE_REQUEST_REJECTED'
        )),
    CONSTRAINT audit_events_resource_type_supported
        CHECK (resource_type IN (
            'FEATURE_FLAG',
            'SDK_CREDENTIAL',
            'CONFIGURATION_REVISION',
            'CHANGE_REQUEST'
        )),
    CONSTRAINT audit_events_scope_consistent
        CHECK (
            (project_id IS NULL AND environment_id IS NULL)
            OR
            (project_id IS NOT NULL AND environment_id IS NULL)
            OR
            (project_id IS NOT NULL AND environment_id IS NOT NULL)
        ),
    CONSTRAINT audit_events_revision_consistent
        CHECK (
            (revision_id IS NULL AND revision_number IS NULL)
            OR
            (revision_id IS NOT NULL
                AND revision_number IS NOT NULL
                AND project_id IS NOT NULL
                AND environment_id IS NOT NULL
                AND revision_number > 0)
        ),
    CONSTRAINT audit_events_correlation_not_blank
        CHECK (correlation_id IS NULL OR btrim(correlation_id) <> ''),
    CONSTRAINT audit_events_details_object
        CHECK (jsonb_typeof(details) = 'object'),
    CONSTRAINT audit_events_details_bounded
        CHECK (octet_length(details::text) <= 16384)
);

INSERT INTO flagforge.audit_events (
    id,
    organization_id,
    project_id,
    environment_id,
    actor_id,
    action,
    resource_type,
    resource_id,
    revision_id,
    revision_number,
    correlation_id,
    details,
    occurred_at
)
SELECT
    event.id,
    event.organization_id,
    event.project_id,
    event.environment_id,
    event.actor_id,
    event.action,
    'CONFIGURATION_REVISION',
    event.revision_id,
    event.revision_id,
    event.revision_number,
    event.correlation_id,
    '{}'::jsonb,
    event.occurred_at
FROM flagforge.publication_audit_events event;

CREATE INDEX audit_events_organization_history_idx
    ON flagforge.audit_events (
        organization_id,
        occurred_at DESC,
        id DESC
    );

CREATE INDEX audit_events_environment_history_idx
    ON flagforge.audit_events (
        organization_id,
        project_id,
        environment_id,
        occurred_at DESC,
        id DESC
    )
    WHERE environment_id IS NOT NULL;

CREATE INDEX audit_events_resource_history_idx
    ON flagforge.audit_events (
        organization_id,
        resource_type,
        resource_id,
        occurred_at DESC
    );

CREATE TRIGGER audit_events_immutable
    BEFORE UPDATE OR DELETE ON flagforge.audit_events
    FOR EACH ROW
    EXECUTE FUNCTION flagforge.reject_immutable_configuration_mutation();

COMMENT ON COLUMN flagforge.configuration_revisions.revision_kind IS
    'PUBLISH for a draft publication or ROLLBACK for a new revision copied from immutable history.';

COMMENT ON COLUMN flagforge.configuration_revisions.source_revision_id IS
    'Immutable source revision used by a rollback; null for normal publication.';

COMMENT ON TABLE flagforge.audit_events IS
    'Append-only tenant audit trail containing bounded non-secret action metadata.';
