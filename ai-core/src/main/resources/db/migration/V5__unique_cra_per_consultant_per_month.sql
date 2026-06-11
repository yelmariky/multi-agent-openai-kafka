-- Supprimer les doublons en gardant le CRA le plus récent par (tenant_id, consultant, billing_month)
DELETE FROM cra
WHERE id NOT IN (
    SELECT DISTINCT ON (tenant_id, consultant, billing_month) id
    FROM cra
    ORDER BY tenant_id, consultant, billing_month, submitted_at DESC NULLS LAST, id DESC
);

-- Contrainte unicité : 1 seul CRA par consultant et par mois
ALTER TABLE cra
    ADD CONSTRAINT uq_cra_tenant_consultant_month
    UNIQUE (tenant_id, consultant, billing_month);
