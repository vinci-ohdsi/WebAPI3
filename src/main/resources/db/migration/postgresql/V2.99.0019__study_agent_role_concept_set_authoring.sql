-- Study Agent now covers both review-gated concept-set and cohort-definition
-- workflows. Extend the existing configurable role without changing ordinary
-- ownership or entity-level write rules.

INSERT INTO ${ohdsiSchema}.sec_role_permission (id, role_id, permission_id)
SELECT nextval('${ohdsiSchema}.sec_role_permission_sequence'), sr.id, sp.id
FROM ${ohdsiSchema}.sec_role sr
JOIN ${ohdsiSchema}.sec_permission sp
  ON sp.value IN (
      'study-agent:concept-set-assist',
      'create:conceptset',
      'study-agent:cohort-definition-assist',
      'create:cohort-definition'
  )
WHERE lower(sr.name) = lower('Study Agent')
  AND NOT EXISTS (
      SELECT 1
      FROM ${ohdsiSchema}.sec_role_permission srp
      WHERE srp.role_id = sr.id
        AND srp.permission_id = sp.id
  );
