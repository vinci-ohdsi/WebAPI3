-- Durable, WebAPI-owned state for /ohdsi cohort-definition acquisition.
-- Browser clients communicate only with WebAPI; ACP/MCP credentials and internals are never persisted.

CREATE TABLE ${ohdsiSchema}.study_agent_cohort_definition_session (
    session_id UUID PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES ${ohdsiSchema}.sec_user(id),
    state VARCHAR(64) NOT NULL,
    acquisition_route VARCHAR(48) NOT NULL,
    narrative TEXT NOT NULL,
    assistant_state TEXT NOT NULL DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_active_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    archived_at TIMESTAMP WITH TIME ZONE NULL
);

CREATE INDEX idx_study_agent_cohort_definition_session_user_active
    ON ${ohdsiSchema}.study_agent_cohort_definition_session (user_id, last_active_at DESC);

CREATE TABLE ${ohdsiSchema}.study_agent_cohort_definition_review (
    id BIGSERIAL PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES ${ohdsiSchema}.study_agent_cohort_definition_session(session_id),
    revision INTEGER NOT NULL,
    source_type VARCHAR(48) NOT NULL,
    phenotype_id VARCHAR(256) NULL,
    phenotype_name TEXT NOT NULL,
    computability_status VARCHAR(48) NOT NULL,
    reviewed_expression TEXT NOT NULL,
    expression_checksum VARCHAR(96) NOT NULL,
    provenance TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    approved_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_study_agent_cohort_definition_review_revision UNIQUE (session_id, revision)
);

CREATE INDEX idx_study_agent_cohort_definition_review_session
    ON ${ohdsiSchema}.study_agent_cohort_definition_review (session_id, revision DESC);

INSERT INTO ${ohdsiSchema}.sec_permission (id, value, description)
SELECT nextval('${ohdsiSchema}.sec_permission_sequence'),
       'study-agent:cohort-definition-assist',
       'Use the Study Agent cohort-definition authoring assistant'
WHERE NOT EXISTS (
    SELECT 1 FROM ${ohdsiSchema}.sec_permission
    WHERE lower(value) = lower('study-agent:cohort-definition-assist')
);

INSERT INTO ${ohdsiSchema}.sec_role_permission (id, role_id, permission_id)
SELECT nextval('${ohdsiSchema}.sec_role_permission_sequence'), sr.id, sp.id
FROM ${ohdsiSchema}.sec_role sr
JOIN ${ohdsiSchema}.sec_permission sp ON sp.value = 'study-agent:cohort-definition-assist'
WHERE sr.name = 'admin'
  AND NOT EXISTS (
      SELECT 1 FROM ${ohdsiSchema}.sec_role_permission srp
      WHERE srp.role_id = sr.id AND srp.permission_id = sp.id
  );
