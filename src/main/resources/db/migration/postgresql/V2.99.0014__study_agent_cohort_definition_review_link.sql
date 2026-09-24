-- Link a reviewed /ohdsi acquisition to the cohort definition only after Atlas saves it.
ALTER TABLE ${ohdsiSchema}.study_agent_cohort_definition_review
    ADD COLUMN cohort_definition_id INTEGER NULL;

CREATE INDEX idx_study_agent_cohort_definition_review_cohort
    ON ${ohdsiSchema}.study_agent_cohort_definition_review (cohort_definition_id);
