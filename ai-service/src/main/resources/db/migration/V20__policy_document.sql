-- Politique de remboursement par tenant (notes de frais), versionnée.
-- Une ligne = une version de la politique. Une seule version ACTIVE à la fois par
-- (tenant_id, policy_type) — activer une nouvelle version archive l'ancienne (voir
-- PolicyService). Les versions DRAFT ne sont jamais utilisées pour l'évaluation des dépenses.
CREATE TABLE policy_document (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID         NOT NULL REFERENCES organization(id),
    policy_type     VARCHAR(50)  NOT NULL,              -- 'EXPENSE_REIMBURSEMENT' (autres types futurs)
    title           VARCHAR(255),
    version         INTEGER      NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT', -- DRAFT | ACTIVE | ARCHIVED
    raw_text        TEXT         NOT NULL,
    created_by      VARCHAR(255),
    created_at      TIMESTAMP    DEFAULT NOW(),
    activated_at    TIMESTAMP
);

CREATE INDEX idx_policy_document_tenant ON policy_document(tenant_id);

-- Une seule version ACTIVE par tenant+type à la fois.
CREATE UNIQUE INDEX uq_policy_document_active
    ON policy_document(tenant_id, policy_type)
    WHERE status = 'ACTIVE';

-- Verdict de conformité (consultatif, voir RAGService) attaché à la dépense au moment de sa
-- création — n'affecte jamais approval_status, affiché à l'admin lors de l'approbation.
ALTER TABLE expense ADD COLUMN policy_verdict_json TEXT;
