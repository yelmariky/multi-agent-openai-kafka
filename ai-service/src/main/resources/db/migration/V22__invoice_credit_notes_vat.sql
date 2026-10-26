-- Facturation conforme : une facture émise ne se supprime plus, elle s'annule par un avoir.
-- invoice_type : FACTURE | AVOIR ; credited_invoice_id : avoir -> facture d'origine.
-- Le statut ANNULEE réutilise la colonne payment_status existante.
ALTER TABLE invoice ADD COLUMN IF NOT EXISTS invoice_type        VARCHAR(10) NOT NULL DEFAULT 'FACTURE';
ALTER TABLE invoice ADD COLUMN IF NOT EXISTS credited_invoice_id UUID;

-- N° de TVA intracommunautaire (mention obligatoire B2B) : vendeur, client, et copie figée sur la facture.
ALTER TABLE invoice        ADD COLUMN IF NOT EXISTS seller_vat_number VARCHAR(30);
ALTER TABLE invoice        ADD COLUMN IF NOT EXISTS client_vat_number VARCHAR(30);
ALTER TABLE seller_profile ADD COLUMN IF NOT EXISTS vat_number        VARCHAR(30);
ALTER TABLE client         ADD COLUMN IF NOT EXISTS vat_number        VARCHAR(30);

-- Numéro de facture unique par vendeur dans un tenant (numérotation continue, jamais réutilisée).
CREATE UNIQUE INDEX IF NOT EXISTS uq_invoice_number
    ON invoice (tenant_id, lower(seller_company_name), lower(invoice_name));
