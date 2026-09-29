-- Reviewable workflow model for /ohdsi cohort authoring. This is deliberately
-- separate from Circe: it records concept-set assets, criterion bindings, and
-- cohort logic before a final expression is emitted.
CREATE TABLE ${ohdsiSchema}.study_agent_cohort_definition_specification (
    id BIGSERIAL PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES ${ohdsiSchema}.study_agent_cohort_definition_session(session_id),
    revision INTEGER NOT NULL,
    state VARCHAR(64) NOT NULL,
    specification TEXT NOT NULL,
    specification_checksum VARCHAR(96) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    accepted_at TIMESTAMP WITH TIME ZONE NULL,
    CONSTRAINT uq_study_agent_cohort_definition_specification_revision UNIQUE (session_id, revision)
);

CREATE INDEX idx_study_agent_cohort_definition_specification_session
    ON ${ohdsiSchema}.study_agent_cohort_definition_specification (session_id, revision DESC);
