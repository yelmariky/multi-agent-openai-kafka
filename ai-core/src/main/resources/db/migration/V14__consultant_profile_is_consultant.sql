-- Distingue les vrais consultants facturables du personnel interne (admin, manager)
-- Les consultants apparaissent dans le dashboard et les rapports ; le personnel interne non.
ALTER TABLE consultant_profile ADD COLUMN IF NOT EXISTS is_consultant BOOLEAN NOT NULL DEFAULT TRUE;

-- Marquer les profils admin/manager connus comme non-consultants
-- Critères : rôle contient admin/manager OU email commence par "admin@"
UPDATE consultant_profile
SET is_consultant = FALSE
WHERE role ILIKE '%admin%'
   OR role ILIKE '%manager%'
   OR role ILIKE '%gestionnaire%'
   OR email ILIKE 'admin@%';
