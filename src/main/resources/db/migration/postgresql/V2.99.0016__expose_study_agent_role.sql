-- Roles listed and managed by the Atlas configuration API are global/system roles.
-- V2.99.0015 created this role before that API contract was accounted for.
UPDATE ${ohdsiSchema}.sec_role
SET system_role = true
WHERE lower(name) = lower('Study Agent')
  AND system_role = false;
