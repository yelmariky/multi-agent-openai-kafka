-- Traçabilité des relances de factures impayées (dunning).
-- Une ligne par relance envoyée : sert d'anti-doublon (un seul envoi par palier)
-- et de preuve d'envoi. Le palier suit l'échéancier J+3 / J+15 / J+30.
CREATE TABLE IF NOT EXISTS invoice_dunning_log (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES organization(id),
    invoice_id    UUID NOT NULL,
    invoice_name  VARCHAR(120),
    stage         INT  NOT NULL,               -- 1 = R1 rappel, 2 = R2 ferme, 3 = R3 mise en demeure
    recipient     VARCHAR(255),
    sent_at       TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_dunning_invoice ON invoice_dunning_log (invoice_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_dunning_invoice_stage ON invoice_dunning_log (invoice_id, stage);
