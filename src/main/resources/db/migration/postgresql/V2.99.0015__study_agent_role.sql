-- Configurable default role for Study Agent cohort-definition authoring.
-- It intentionally grants only assistant access plus ordinary new-cohort creation;
-- users retain Atlas ownership rules for saved cohort definitions.

INSERT INTO ${ohdsiSchema}.sec_role (name, system_role)
SELECT 'Study Agent', false
WHERE NOT EXISTS (
    SELECT 1
    FROM ${ohdsiSchema}.sec_role
    WHERE lower(name) = lower('Study Agent')
);

INSERT INTO ${ohdsiSchema}.sec_role_permission (id, role_id, permission_id)
SELECT nextval('${ohdsiSchema}.sec_role_permission_sequence'), sr.id, sp.id
FROM ${ohdsiSchema}.sec_role sr
JOIN ${ohdsiSchema}.sec_permission sp
  ON sp.value IN ('study-agent:cohort-definition-assist', 'create:cohort-definition')
WHERE lower(sr.name) = lower('Study Agent')
  AND NOT EXISTS (
      SELECT 1
      FROM ${ohdsiSchema}.sec_role_permission srp
      WHERE srp.role_id = sr.id
        AND srp.permission_id = sp.id
  );
