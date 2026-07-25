CREATE TABLE flagforge.organizations (
    id UUID PRIMARY KEY,
    slug VARCHAR(63) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT organizations_slug_format
        CHECK (slug ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    CONSTRAINT organizations_display_name_not_blank
        CHECK (btrim(display_name) <> ''),
    CONSTRAINT organizations_slug_unique UNIQUE (slug)
);

CREATE TABLE flagforge.memberships (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT memberships_organization_fk
        FOREIGN KEY (organization_id)
        REFERENCES flagforge.organizations (id)
        ON DELETE CASCADE,
    CONSTRAINT memberships_actor_id_not_blank
        CHECK (btrim(actor_id) <> ''),
    CONSTRAINT memberships_status_supported
        CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT memberships_actor_per_organization_unique
        UNIQUE (organization_id, actor_id)
);

CREATE TABLE flagforge.projects (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    project_key VARCHAR(63) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT projects_organization_fk
        FOREIGN KEY (organization_id)
        REFERENCES flagforge.organizations (id)
        ON DELETE CASCADE,
    CONSTRAINT projects_key_format
        CHECK (project_key ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    CONSTRAINT projects_display_name_not_blank
        CHECK (btrim(display_name) <> ''),
    CONSTRAINT projects_key_per_organization_unique
        UNIQUE (organization_id, project_key),
    CONSTRAINT projects_organization_identity_unique
        UNIQUE (organization_id, id)
);

CREATE TABLE flagforge.environments (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    project_id UUID NOT NULL,
    environment_key VARCHAR(63) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT environments_organization_fk
        FOREIGN KEY (organization_id)
        REFERENCES flagforge.organizations (id)
        ON DELETE CASCADE,
    CONSTRAINT environments_project_tenant_fk
        FOREIGN KEY (organization_id, project_id)
        REFERENCES flagforge.projects (organization_id, id)
        ON DELETE CASCADE,
    CONSTRAINT environments_key_format
        CHECK (environment_key ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    CONSTRAINT environments_display_name_not_blank
        CHECK (btrim(display_name) <> ''),
    CONSTRAINT environments_key_per_project_unique
        UNIQUE (organization_id, project_id, environment_key),
    CONSTRAINT environments_organization_identity_unique
        UNIQUE (organization_id, id)
);

CREATE INDEX memberships_actor_lookup_idx
    ON flagforge.memberships (actor_id, organization_id);

CREATE INDEX projects_organization_lookup_idx
    ON flagforge.projects (organization_id, id);

CREATE INDEX environments_project_lookup_idx
    ON flagforge.environments (organization_id, project_id, id);

COMMENT ON TABLE flagforge.organizations IS
    'Top-level tenant boundaries for all FlagForge-owned resources.';

COMMENT ON TABLE flagforge.memberships IS
    'Organization-scoped actor membership without RBAC policy, which is delivered separately.';

COMMENT ON TABLE flagforge.projects IS
    'Organization-owned software product or bounded application contexts.';

COMMENT ON TABLE flagforge.environments IS
    'Project configuration spaces with an enforced organization boundary.';
