-- Immutable, WebAPI-owned dialogue history for resumed concept-set authoring.
-- Raw ACP transport data, credentials, and model diagnostics are never stored.

CREATE TABLE ${ohdsiSchema}.study_agent_concept_set_dialogue_message (
    id BIGSERIAL PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES ${ohdsiSchema}.study_agent_concept_set_session(session_id),
    actor VARCHAR(16) NOT NULL,
    message TEXT NOT NULL,
    structured_payload TEXT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_study_agent_concept_set_dialogue_actor
        CHECK (actor IN ('user', 'assistant'))
);

CREATE INDEX idx_study_agent_concept_set_dialogue_message_session
    ON ${ohdsiSchema}.study_agent_concept_set_dialogue_message (session_id, id);
