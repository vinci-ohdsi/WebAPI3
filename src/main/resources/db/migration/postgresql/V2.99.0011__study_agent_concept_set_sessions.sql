-- Durable, WebAPI-owned provenance for the review-gated Study Agent concept-set
-- authoring flow. ACP identifiers and credentials are never persisted here.

CREATE TABLE ${ohdsiSchema}.study_agent_concept_set_session (
    session_id UUID PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES ${ohdsiSchema}.sec_user(id),
    concept_set_id INTEGER NULL REFERENCES ${ohdsiSchema}.concept_set(concept_set_id),
    state VARCHAR(64) NOT NULL,
    narrative TEXT NOT NULL,
    ui_context TEXT NOT NULL,
    assistant_state TEXT NOT NULL,
    review_revision INTEGER NOT NULL DEFAULT 0,
    approved_expression_checksum VARCHAR(96) NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_active_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    archived_at TIMESTAMP WITH TIME ZONE NULL
);

CREATE INDEX idx_study_agent_concept_set_session_user_active
    ON ${ohdsiSchema}.study_agent_concept_set_session (user_id, last_active_at DESC);

CREATE TABLE ${ohdsiSchema}.study_agent_concept_set_review (
    id BIGSERIAL PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES ${ohdsiSchema}.study_agent_concept_set_session(session_id),
    revision INTEGER NOT NULL,
    expression_checksum VARCHAR(96) NOT NULL,
    reviewed_expression TEXT NOT NULL,
    review_manifest TEXT NOT NULL,
    acp_validation TEXT NULL,
    webapi_validation TEXT NULL,
    vocabulary_provenance TEXT NOT NULL,
    approved_at TIMESTAMP WITH TIME ZONE NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_study_agent_concept_set_review_revision UNIQUE (session_id, revision)
);

CREATE INDEX idx_study_agent_concept_set_review_session
    ON ${ohdsiSchema}.study_agent_concept_set_review (session_id, revision DESC);

INSERT INTO ${ohdsiSchema}.sec_permission (id, value, description)
SELECT nextval('${ohdsiSchema}.sec_permission_id_seq'),
       'study-agent:concept-set-assist',
       'Use the Study Agent concept-set authoring assistant'
WHERE NOT EXISTS (
    SELECT 1 FROM ${ohdsiSchema}.sec_permission
    WHERE lower(value) = lower('study-agent:concept-set-assist')
);

INSERT INTO ${ohdsiSchema}.sec_role_permission (id, role_id, permission_id)
SELECT nextval('${ohdsiSchema}.sec_role_permission_sequence'), sr.id, sp.id
FROM ${ohdsiSchema}.sec_role sr
JOIN ${ohdsiSchema}.sec_permission sp ON sp.value = 'study-agent:concept-set-assist'
WHERE sr.name = 'admin'
  AND NOT EXISTS (
      SELECT 1
      FROM ${ohdsiSchema}.sec_role_permission srp
      WHERE srp.role_id = sr.id AND srp.permission_id = sp.id
  );
