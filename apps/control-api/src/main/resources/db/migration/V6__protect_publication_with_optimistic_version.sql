UPDATE flagforge.environment_publication_state
SET pointer_version = pointer_version + 1;

ALTER TABLE flagforge.environment_publication_state
    DROP CONSTRAINT environment_publication_state_version_positive;

ALTER TABLE flagforge.environment_publication_state
    ADD CONSTRAINT environment_publication_state_version_positive
    CHECK (current_revision_number > 0 AND pointer_version > 0);

COMMENT ON COLUMN flagforge.environment_publication_state.pointer_version IS
    'Monotonic publication version. Version zero represents an environment that has never been published.';
