-- Ajustement prorata en cours de période : on mémorise ce qui a été facturé
-- à la dernière facture de période pour détecter les consultants ajoutés depuis.
ALTER TABLE tenant_subscription ADD COLUMN IF NOT EXISTS billed_consultant_count INT;
ALTER TABLE tenant_subscription ADD COLUMN IF NOT EXISTS current_period_start DATE;
ALTER TABLE tenant_subscription ADD COLUMN IF NOT EXISTS current_period_end DATE;
