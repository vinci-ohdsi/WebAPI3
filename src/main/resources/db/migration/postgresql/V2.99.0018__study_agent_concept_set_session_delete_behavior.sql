-- A Study Agent session records optional provenance for the saved concept set it
-- reviewed.  The session and its review history remain useful after the asset
-- is deleted, so do not let that optional link block normal concept-set delete.
ALTER TABLE ${ohdsiSchema}.study_agent_concept_set_session
    DROP CONSTRAINT IF EXISTS study_agent_concept_set_session_concept_set_id_fkey;

ALTER TABLE ${ohdsiSchema}.study_agent_concept_set_session
    ADD CONSTRAINT study_agent_concept_set_session_concept_set_id_fkey
    FOREIGN KEY (concept_set_id)
    REFERENCES ${ohdsiSchema}.concept_set(concept_set_id)
    ON DELETE SET NULL;
