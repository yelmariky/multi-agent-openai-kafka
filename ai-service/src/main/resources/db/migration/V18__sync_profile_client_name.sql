-- Rattrapage : le champ texte libre consultant_profile.client_name (affiché sur la carte
-- consultant) pouvait diverger de l'affectation réelle (consultant_assignment → client).
-- On aligne sur l'affectation la plus récente ; désormais le backend synchronise à chaque
-- création/modification d'affectation (ConsultantAssignmentController).
UPDATE consultant_profile cp
SET client_name = sub.client_name
FROM (
    SELECT DISTINCT ON (ca.consultant_profile_id)
           ca.consultant_profile_id, c.name AS client_name
    FROM consultant_assignment ca
    JOIN client c ON c.id = ca.client_id
    ORDER BY ca.consultant_profile_id, ca.created_at DESC NULLS LAST
) sub
WHERE cp.id = sub.consultant_profile_id
  AND (cp.client_name IS DISTINCT FROM sub.client_name);
