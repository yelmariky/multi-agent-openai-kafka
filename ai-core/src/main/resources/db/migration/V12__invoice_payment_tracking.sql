-- Suivi du paiement des factures
ALTER TABLE invoice ADD COLUMN IF NOT EXISTS payment_status       VARCHAR(20)  DEFAULT 'EN_ATTENTE';
ALTER TABLE invoice ADD COLUMN IF NOT EXISTS payment_received_date DATE;
ALTER TABLE invoice ADD COLUMN IF NOT EXISTS sent_date             DATE;
ALTER TABLE invoice ADD COLUMN IF NOT EXISTS invoice_number        VARCHAR(50);

-- Index pour requêtes dashboard (factures en retard)
CREATE INDEX IF NOT EXISTS idx_invoice_payment_status ON invoice(tenant_id, payment_status);
CREATE INDEX IF NOT EXISTS idx_invoice_due_date       ON invoice(tenant_id, payment_due_date);
